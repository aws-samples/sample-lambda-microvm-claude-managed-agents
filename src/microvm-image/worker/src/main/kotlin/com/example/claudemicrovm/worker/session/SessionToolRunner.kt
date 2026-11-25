package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.SessionEvent
import com.example.claudemicrovm.worker.anthropic.SessionEventsApi
import com.example.claudemicrovm.worker.anthropic.ToolResult
import com.example.claudemicrovm.worker.anthropic.isFatal4xx
import com.example.claudemicrovm.worker.tools.AgentTool
import com.example.claudemicrovm.worker.tools.ToolError
import com.example.claudemicrovm.worker.tools.ToolInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Default [SessionToolRunner] idle grace after an `end_turn`: 60 seconds. */
const val DEFAULT_MAX_IDLE_MS = 60_000L

/** Per-call budget for one tool execution. */
const val TOOL_TIMEOUT_MS = 120_000L

private const val STREAM_BACKOFF_START_MS = 500L
private const val STREAM_BACKOFF_CAP_MS = 10_000L
private const val SEND_RETRIES = 3
private const val SEND_RETRY_STEP_MS = 1_000L
private const val NO_OUTPUT = "(no output)"

private val log = LoggerFactory.getLogger(SessionToolRunner::class.java)

/** One tool call the runner dispatched; [posted] is false when no result was (or could be) sent. */
data class DispatchedToolCall(val toolUseId: String, val name: String, val isError: Boolean, val posted: Boolean)

/**
 * Attaches to a managed-agents session, executes every `agent.tool_use` and
 * `agent.custom_tool_use` event against the local [tools] registry, and posts
 * the matching result back (`user.tool_result` / `user.custom_tool_result`).
 * Calls for tools not in the registry are left pending for whichever client owns
 * them. Event-stream reconnects and result posting are handled internally.
 *
 * [run] returns when the session terminates (`session.status_terminated` /
 * `session.deleted`), on a permanent stream failure, or — once the session has
 * gone idle with `stop_reason.type == "end_turn"` — when [maxIdleMs] elapses
 * with no new event (any new event resets the countdown; `maxIdleMs <= 0`
 * disables it). Cancelling the caller stops it as well; either way every tool's
 * `close()` runs before [run] exits. It does *not* touch the work-item lease —
 * see [EnvironmentWorker].
 */
class SessionToolRunner(
    private val api: SessionEventsApi,
    private val sessionId: String,
    private val tools: List<AgentTool>,
    private val maxIdleMs: Long = DEFAULT_MAX_IDLE_MS,
    private val toolTimeoutMs: Long = TOOL_TIMEOUT_MS,
    private val onDispatched: (DispatchedToolCall) -> Unit = {},
) {
    private val toolByName = tools.associateBy { it.name }

    /** Tool-use ids already dispatched (or queued for dispatch) — dedups history replays on the live stream. */
    private val seen = HashSet<String>()

    /** Tool-use ids that have a posted result, whether from us or another client. */
    private val answered = HashSet<String>()

    private val consumed = AtomicBoolean(false)

    @Volatile
    private var aborted = false

    @Volatile
    private var idleTimer: Job? = null
    private lateinit var scope: CoroutineScope
    private lateinit var loop: Job

    suspend fun run() {
        check(consumed.compareAndSet(false, true)) { "Cannot run a consumed SessionToolRunner" }
        log.info("session tool runner starting (session_id={})", sessionId)
        try {
            coroutineScope {
                scope = this
                loop = launch {
                    try {
                        streamLoop()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!aborted) log.error("stream loop failed (session_id={}): {}", sessionId, e.toString())
                    }
                }
                loop.join()
                // Disarm a pending idle timer so the scope can complete.
                abort()
            }
        } finally {
            withContext(NonCancellable) { closeTools() }
        }
    }

    /** Stop the runner: cancels the stream loop (and any in-flight tool call) and the idle timer. */
    private fun abort() {
        aborted = true
        idleTimer?.cancel()
        loop.cancel()
    }

    private suspend fun streamLoop() {
        var backoff = STREAM_BACKOFF_START_MS
        while (true) {
            try {
                // Attach the live stream *before* reconciling history, so an event emitted
                // between listing and attaching is buffered on the stream rather than lost;
                // `seen` / `answered` dedup events that show up in both.
                api.stream(sessionId).use { stream ->
                    reconcile()
                    while (true) {
                        val event = stream.next() ?: break
                        backoff = STREAM_BACKOFF_START_MS
                        if (handleStreamEvent(event)) return
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (isFatal4xx(e)) {
                    log.error("permanent stream failure, shutting down (session_id={}): {}", sessionId, e.toString())
                    abort()
                    throw e
                }
                log.warn("stream disconnected, reconnecting (error={}, backoff_ms={})", e.toString(), backoff)
            }
            delay(backoff)
            backoff = minOf(backoff * 2, STREAM_BACKOFF_CAP_MS)
        }
    }

    /**
     * Read the full history before dispatching, so a tool use whose result
     * appears later in the same history is not re-executed. A list failure is
     * non-fatal: the live stream carries on.
     */
    private suspend fun reconcile() {
        val history = try {
            api.history(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            log.warn("reconcile list failed (session_id={}): {}", sessionId, e.toString())
            return
        }
        val pending = mutableListOf<SessionEvent.ToolUse>()
        for (event in history) {
            when (event) {
                is SessionEvent.ToolUse -> {
                    // Mark it seen so a replay on the live stream is not dispatched twice, but
                    // decide whether it still needs executing from `answered`: a call whose
                    // result post failed is seen-but-unanswered and is retried here.
                    seen += event.id
                    if (event.id !in answered) pending += event
                }
                is SessionEvent.ToolAnswered -> answered += event.toolUseId
                else -> Unit
            }
        }
        val unanswered = pending.filter { it.id !in answered }
        // History ending in an `end_turn` idle with no outstanding tool work means the
        // session is done — arm the idle timer even if that idle arrived while disconnected.
        val lastWasEndTurn = (history.lastOrNull() as? SessionEvent.Idle)?.isEndTurn == true
        if (lastWasEndTurn && unanswered.isEmpty()) armIdleTimer() else disarmIdleTimer()
        for (event in unanswered) execute(event)
    }

    /** Returns true when the runner should exit. */
    private suspend fun handleStreamEvent(event: SessionEvent): Boolean {
        // An `end_turn` idle starts the grace countdown; any other event cancels it.
        if (event is SessionEvent.Idle && event.isEndTurn) armIdleTimer() else disarmIdleTimer()
        when (event) {
            is SessionEvent.ToolUse -> if (seen.add(event.id)) execute(event)
            is SessionEvent.ToolAnswered -> answered += event.toolUseId
            is SessionEvent.Ended -> {
                log.info("session terminated (session_id={})", sessionId)
                abort()
                return true
            }
            else -> Unit
        }
        return false
    }

    private fun armIdleTimer() {
        disarmIdleTimer()
        if (maxIdleMs <= 0) return
        idleTimer = scope.launch {
            delay(maxIdleMs)
            log.info("session idle after end_turn; stopping (session_id={}, max_idle_ms={})", sessionId, maxIdleMs)
            abort()
        }
    }

    private fun disarmIdleTimer() {
        idleTimer?.cancel()
        idleTimer = null
    }

    private suspend fun execute(event: SessionEvent.ToolUse) {
        if (event.id in answered) return
        log.info("executing tool (session_id={}, tool={}, tool_use_id={})", sessionId, event.name, event.id)
        val tool = toolByName[event.name]
        if (tool == null) {
            // Not ours (split-client fulfilment, e.g. a custom tool served by the app
            // backend): post nothing and leave the id unanswered for its owner.
            log.info(
                "tool not owned by this runner; leaving the tool_use_id pending for its owner " +
                    "(session_id={}, tool={}, tool_use_id={})",
                sessionId, event.name, event.id,
            )
            onDispatched(DispatchedToolCall(event.id, event.name, isError = false, posted = false))
            return
        }
        val (content, isError) = runTool(tool, event)
        // Answer with the result kind that matches the call kind; the wrong one leaves
        // the call unanswered and the session stuck.
        val posted = sendResult(ToolResult(event.id, event.custom, isError, content.ifEmpty { NO_OUTPUT }))
        onDispatched(DispatchedToolCall(event.id, event.name, isError, posted))
    }

    /** Run [tool]; a [ToolError] carries its own message, anything else becomes `Error: <message>`. */
    private suspend fun runTool(tool: AgentTool, event: SessionEvent.ToolUse): Pair<String, Boolean> = try {
        val output = withTimeoutOrNull(toolTimeoutMs) { tool.run(ToolInput(event.input)) }
        if (output == null) "Error: ${tool.name} timed out after ${toolTimeoutMs}ms" to true else output to false
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolError) {
        e.message.orEmpty() to true
    } catch (e: Exception) {
        "Error: ${e.message ?: e.toString()}" to true
    }

    private suspend fun sendResult(result: ToolResult): Boolean {
        var lastError: Exception? = null
        for (attempt in 0 until SEND_RETRIES) {
            currentCoroutineContext().ensureActive()
            try {
                api.send(sessionId, result)
                answered += result.toolUseId
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                // Only short-circuit on a permanent 4xx; 408/409/429 get the remaining retries.
                if (isFatal4xx(e)) break
                if (attempt < SEND_RETRIES - 1) delay((attempt + 1) * SEND_RETRY_STEP_MS)
            }
        }
        log.error("failed to send tool result (tool_use_id={}, error={})", result.toolUseId, lastError.toString())
        return false
    }

    private fun closeTools() {
        for (tool in tools) {
            try {
                tool.close()
            } catch (e: Exception) {
                log.warn("tool.close failed (tool={}, error={})", tool.name, e.toString())
            }
        }
    }
}
