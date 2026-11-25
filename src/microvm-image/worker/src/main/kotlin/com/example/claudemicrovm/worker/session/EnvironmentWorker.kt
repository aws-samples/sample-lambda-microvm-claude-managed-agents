package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.SessionEventsApi
import com.example.claudemicrovm.worker.anthropic.SkillsApi
import com.example.claudemicrovm.worker.anthropic.WorkApi
import com.example.claudemicrovm.worker.anthropic.WorkItem
import com.example.claudemicrovm.worker.anthropic.isFatal4xx
import com.example.claudemicrovm.worker.anthropic.isStatus
import com.example.claudemicrovm.worker.tools.AgentTool
import com.example.claudemicrovm.worker.tools.AgentToolContext
import com.example.claudemicrovm.worker.tools.agentToolset20260401
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Heartbeat interval cap (and the interval until the API reports a lease TTL). */
internal const val HEARTBEAT_DEFAULT_MS = 30_000L

private const val HEARTBEAT_MIN_MS = 1_000L
private const val NO_HEARTBEAT_SENTINEL = "NO_HEARTBEAT"

private val log = LoggerFactory.getLogger(EnvironmentWorker::class.java)

/**
 * Serves one claimed `session` work item: downloads the session agent's skills
 * into the workdir, runs a [SessionToolRunner] for the session *while*
 * heartbeating the work-item lease, and on exit cleans up the skills and
 * force-stops the work item. A heartbeat reporting `stopping` / `stopped`, a
 * lease that was not extended, or a permanent heartbeat failure stops the runner.
 */
class EnvironmentWorker(
    private val work: WorkApi,
    private val events: SessionEventsApi,
    private val skills: SkillsApi,
    private val workdir: Path,
    /** Tool registry factory; defaults to the `agent_toolset_20260401` tools. */
    private val tools: ((AgentToolContext) -> List<AgentTool>)? = null,
    private val maxIdleMs: Long = DEFAULT_MAX_IDLE_MS,
) {
    suspend fun handleItem(item: WorkItem) {
        val sessionId = item.sessionId
        val ctx = AgentToolContext(workdir)
        val cleanupSkills: suspend () -> Unit = try {
            setupSkills(skills, sessionId, workdir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("skill setup failed (session_id={}, work_id={}, error={})", sessionId, item.id, e.toString())
            NO_CLEANUP
        }
        val toolset = tools?.invoke(ctx) ?: agentToolset20260401(ctx)
        try {
            coroutineScope {
                val runner = launch { SessionToolRunner(events, sessionId, toolset, maxIdleMs).run() }
                val heartbeat = launch {
                    try {
                        heartbeatLoop(work, item) { runner.cancel() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (runner.isActive) log.error("heartbeat loop failed (work_id={}, error={})", item.id, e.toString())
                        runner.cancel()
                    }
                }
                runner.join()
                heartbeat.cancelAndJoin()
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    cleanupSkills()
                } catch (e: Exception) {
                    log.warn("skill cleanup failed (session_id={}, work_id={}, error={})", sessionId, item.id, e.toString())
                }
                forceStop(work, item)
            }
        }
    }
}

private val NO_CLEANUP: suspend () -> Unit = {}

/** Force-stop a claimed work item, swallowing the 409 that means it is already stopped. */
internal suspend fun forceStop(api: WorkApi, item: WorkItem) {
    try {
        api.stop(item.id, item.environmentId, force = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isStatus(e, 409)) log.error("force-stop on exit failed (work_id={}, error={})", item.id, e.toString())
    }
}

/**
 * Keep the lease on [item] alive until cancelled. Calls [onShutdown] (and
 * returns) when the control plane reports the work `stopping` / `stopped` or the
 * lease was not extended; calls it and throws on a permanent heartbeat failure.
 */
internal suspend fun heartbeatLoop(api: WorkApi, item: WorkItem, onShutdown: () -> Unit) {
    var intervalMs = HEARTBEAT_DEFAULT_MS
    var last = NO_HEARTBEAT_SENTINEL

    /** One heartbeat; returns false once the work should shut down. */
    suspend fun beat(): Boolean {
        try {
            val response = api.heartbeat(item.id, item.environmentId, last)
            last = response.lastHeartbeat
            if (response.ttlSeconds > 0) {
                intervalMs = (response.ttlSeconds * 1000 / 2).coerceIn(HEARTBEAT_MIN_MS, HEARTBEAT_DEFAULT_MS)
            }
            var keepGoing = true
            if (response.state == "stopping" || response.state == "stopped") {
                log.info("heartbeat signals shutdown (work_id={}, state={})", item.id, response.state)
                keepGoing = false
            }
            if (!response.leaseExtended) {
                log.warn("lease not extended, shutting down (work_id={})", item.id)
                keepGoing = false
            }
            if (!keepGoing) onShutdown()
            return keepGoing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (isFatal4xx(e)) {
                log.error("permanent heartbeat failure (work_id={}, error={})", item.id, e.toString())
                onShutdown()
                throw e
            }
            log.warn("transient heartbeat failure (work_id={}, error={})", item.id, e.toString())
            return true
        }
    }

    if (!beat()) return
    while (true) {
        delay(intervalMs)
        if (!beat()) return
    }
}
