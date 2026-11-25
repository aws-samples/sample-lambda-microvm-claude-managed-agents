package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.WorkApi
import com.example.claudemicrovm.worker.anthropic.WorkItem
import com.example.claudemicrovm.worker.anthropic.isFatal4xx
import com.example.claudemicrovm.worker.anthropic.isStatus
import com.example.claudemicrovm.worker.util.applyJitter
import com.example.claudemicrovm.worker.util.backoff
import com.example.claudemicrovm.worker.util.jitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

/** The API caps `block_ms` at 999; client-side jitter spaces out empty polls. */
const val POLL_BLOCK_MS = 999L

private const val POLL_BACKOFF_BASE_MS = 1_000L
private const val POLL_BACKOFF_CAP_MS = 60_000L

private val log = LoggerFactory.getLogger(WorkPoller::class.java)

/** Exponential poll backoff: 1s, 2s, 4s … clamped to a 60s cap. */
internal fun pollBackoff(attempt: Int): Long = backoff(attempt, POLL_BACKOFF_BASE_MS, POLL_BACKOFF_CAP_MS)

/**
 * Long-polls a self-hosted environment for work: [works] claims (acks) each
 * item and emits it; once the collector has processed the item the poller posts
 * `stop` for it, unless [autoStop] is off (the consumer then owns the stop).
 *
 * With [drain], the flow completes as soon as the queue is empty; otherwise it
 * keeps polling. A permanent 4xx (bad environment key, unknown environment)
 * fails the flow; other errors back off with jittered exponential delays.
 */
class WorkPoller(
    private val api: WorkApi,
    private val environmentId: String,
    /** Unique per poller (the API uses it for consumer groups). */
    val workerId: String = defaultWorkerId(),
    /** `null` omits `block_ms` for a non-blocking poll. */
    private val blockMs: Long? = POLL_BLOCK_MS,
    private val reclaimOlderThanMs: Long? = null,
    private val drain: Boolean = false,
    private val autoStop: Boolean = true,
) {
    fun works(): Flow<WorkItem> = flow {
        log.info("poller starting (environment_id={})", environmentId)
        var attempt = 0
        while (true) {
            val work = try {
                api.poll(environmentId, workerId, blockMs, reclaimOlderThanMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                // A bad environment key / missing environment never recovers — surface it
                // instead of spinning forever at the backoff cap.
                if (isFatal4xx(e)) {
                    log.error("poll failed permanently, stopping poller: {}", e.toString())
                    throw e
                }
                val wait = applyJitter(pollBackoff(attempt))
                log.warn("poll failed, backing off (error={}, backoff_ms={})", e.toString(), wait)
                attempt++
                delay(wait)
                continue
            }
            attempt = 0
            if (work == null) {
                if (drain) return@flow
                delay(jitter(1_000, 3_000))
                continue
            }
            log.info(
                "claimed work (environment_id={}, work_id={}, work_type={})",
                environmentId, work.id, work.type,
            )
            try {
                api.ack(work.id, work.environmentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("ack failed (work_id={}, error={})", work.id, e.toString())
                continue
            }
            try {
                emit(work)
            } finally {
                if (autoStop) withContext(NonCancellable) { stopQuietly(work) }
            }
        }
    }

    private suspend fun stopQuietly(work: WorkItem) {
        try {
            api.stop(work.id, work.environmentId, force = false)
        } catch (e: Exception) {
            if (!isStatus(e, 409)) log.warn("stop failed (work_id={}, error={})", work.id, e.toString())
        }
    }
}

/** Unique even when several pollers share a host; prefixed with the hostname when one is set. */
fun defaultWorkerId(): String {
    val host = System.getenv("HOSTNAME")
    return if (host.isNullOrEmpty()) UUID.randomUUID().toString() else "$host-${UUID.randomUUID()}"
}
