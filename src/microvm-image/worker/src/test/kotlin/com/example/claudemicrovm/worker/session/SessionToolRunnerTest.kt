package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.ApiException
import com.example.claudemicrovm.worker.anthropic.SessionEvent.Companion.SESSION_DELETED
import com.example.claudemicrovm.worker.anthropic.SessionEvent.Companion.SESSION_STATUS_TERMINATED
import com.example.claudemicrovm.worker.anthropic.SessionEvent.Companion.USER_TOOL_RESULT
import com.example.claudemicrovm.worker.anthropic.SessionEvent.Ended
import com.example.claudemicrovm.worker.anthropic.SessionEvent.Idle
import com.example.claudemicrovm.worker.anthropic.SessionEvent.Other
import com.example.claudemicrovm.worker.anthropic.SessionEvent.ToolAnswered
import com.example.claudemicrovm.worker.anthropic.SessionEvent.ToolUse
import com.example.claudemicrovm.worker.anthropic.ToolResult
import com.example.claudemicrovm.worker.tools.ToolError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionToolRunnerTest {
    private val terminated = Ended(SESSION_STATUS_TERMINATED)
    private val echo get() = FakeTool("echo") { it.string("text").orEmpty() }

    private fun use(id: String, name: String = "echo", text: String = id, custom: Boolean = false) =
        ToolUse(id, name, mapOf("text" to text), custom)

    @Test
    fun `answers tool_use with tool_result and custom_tool_use with custom_tool_result`() = runTest {
        val api = FakeEvents()
        api.addStream(use("tu_1", text = "hi"), use("ctu_1", text = "yo", custom = true), terminated)
        val tool = echo
        val dispatched = mutableListOf<DispatchedToolCall>()

        SessionToolRunner(api, "sesn_1", listOf(tool), onDispatched = { dispatched += it }).run()

        assertEquals(listOf(ToolResult("tu_1", false, false, "hi"), ToolResult("ctu_1", true, false, "yo")), api.sent)
        assertEquals(listOf(true, true), dispatched.map { it.posted })
        assertTrue(tool.closed)
    }

    @Test
    fun `tool failures are reported to the model as error results`() = runTest {
        val api = FakeEvents()
        api.addStream(use("a", "fails"), use("b", "crashes"), use("c", "silent"), terminated)
        val tools = listOf(
            FakeTool("fails") { throw ToolError("bad input") },
            FakeTool("crashes") { throw IllegalStateException("boom") },
            FakeTool("silent") { "" },
        )

        SessionToolRunner(api, "s", tools).run()

        assertEquals(
            listOf(
                ToolResult("a", false, true, "bad input"),
                ToolResult("b", false, true, "Error: boom"),
                ToolResult("c", false, false, "(no output)"),
            ),
            api.sent,
        )
    }

    @Test
    fun `a tool that exceeds the per-call budget is reported as timed out`() = runTest {
        val api = FakeEvents()
        api.addStream(use("a", "slow"), terminated)
        val slow = FakeTool("slow") {
            delay(10_000)
            "late"
        }

        SessionToolRunner(api, "s", listOf(slow), toolTimeoutMs = 1_000).run()

        assertEquals(listOf(ToolResult("a", false, true, "Error: slow timed out after 1000ms")), api.sent)
    }

    @Test
    fun `calls for tools it does not own are left pending for their owner`() = runTest {
        val api = FakeEvents()
        api.addStream(use("x", "app_tool", custom = true), Ended(SESSION_DELETED))
        val dispatched = mutableListOf<DispatchedToolCall>()

        SessionToolRunner(api, "s", listOf(echo), onDispatched = { dispatched += it }).run()

        assertTrue(api.sent.isEmpty())
        assertEquals(listOf(DispatchedToolCall("x", "app_tool", isError = false, posted = false)), dispatched)
    }

    @Test
    fun `reconcile runs unanswered history once and skips answered calls`() = runTest {
        val api = FakeEvents()
        api.history += listOf(use("tu_1"), ToolAnswered("tu_1", USER_TOOL_RESULT), use("tu_2"))
        // The live stream replays tu_2, which must not run a second time.
        api.addStream(use("tu_2"), terminated)
        val tool = echo

        SessionToolRunner(api, "s", listOf(tool)).run()

        assertEquals(listOf("tu_2"), api.sent.map { it.toolUseId })
        assertEquals(1, tool.calls.size)
    }

    @Test
    fun `stops once the session has been idle after end_turn for maxIdleMs`() = runTest {
        val api = FakeEvents()
        api.addStream(Idle("end_turn"))

        SessionToolRunner(api, "s", emptyList(), maxIdleMs = 5_000).run()

        assertEquals(5_000, currentTime)
    }

    @Test
    fun `any other event cancels the idle countdown until the next end_turn`() = runTest {
        val api = FakeEvents()
        val live = api.addStream(Idle("end_turn"))
        val run = launch { SessionToolRunner(api, "s", emptyList(), maxIdleMs = 5_000).run() }

        advanceTimeBy(4_000)
        live.send(Other("agent.message"))
        advanceTimeBy(10_000)
        assertTrue(run.isActive)
        live.send(Idle("requires_action"))
        advanceTimeBy(10_000)
        assertTrue(run.isActive)
        live.send(Idle("end_turn"))
        run.join()

        assertEquals(29_000, currentTime)
    }

    @Test
    fun `history that ends idle after end_turn arms the idle timer`() = runTest {
        val api = FakeEvents()
        api.history += Idle("end_turn")
        api.addStream()

        SessionToolRunner(api, "s", emptyList(), maxIdleMs = 2_000).run()

        assertEquals(2_000, currentTime)
    }

    @Test
    fun `reconnects with backoff and gives up on a permanent 4xx`() = runTest {
        val api = FakeEvents()
        api.failStream(ApiException(503, "unavailable"))
        api.addStream(end = true)
        api.failStream(ApiException(401, "unauthorized"))
        val tool = echo

        SessionToolRunner(api, "s", listOf(tool)).run()

        assertEquals(3, api.streamCalls)
        // 500ms after the failure, then 1s (doubled) after the stream ended cleanly.
        assertEquals(1_500, currentTime)
        assertTrue(tool.closed)
    }

    @Test
    fun `retries a result post after transient errors`() = runTest {
        val api = FakeEvents()
        api.sendFailures.addAll(listOf(ApiException(500, "oops"), ApiException(429, "slow down")))
        api.addStream(use("a"), terminated)
        val dispatched = mutableListOf<DispatchedToolCall>()

        SessionToolRunner(api, "s", listOf(echo), onDispatched = { dispatched += it }).run()

        assertEquals(listOf("a"), api.sent.map { it.toolUseId })
        assertEquals(listOf(true), dispatched.map { it.posted })
        assertEquals(3, api.sendAttempts)
        // 1s after the first failure, 2s after the second.
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `gives up on a result post after a permanent 4xx or the last retry`() = runTest {
        val api = FakeEvents()
        api.sendFailures.addAll(listOf(ApiException(400, "bad request")) + List(3) { ApiException(503, "unavailable") })
        api.addStream(use("a"), use("b"), terminated)
        val dispatched = mutableListOf<DispatchedToolCall>()

        SessionToolRunner(api, "s", listOf(echo), onDispatched = { dispatched += it }).run()

        assertTrue(api.sent.isEmpty())
        assertEquals(listOf(false, false), dispatched.map { it.posted })
        // One attempt for the 400, three for the 503s.
        assertEquals(4, api.sendAttempts)
    }

    @Test
    fun `a runner can only run once`() = runTest {
        val api = FakeEvents()
        api.addStream(terminated)
        val runner = SessionToolRunner(api, "s", emptyList())
        runner.run()
        assertFailsWith<IllegalStateException> { runner.run() }
    }
}
