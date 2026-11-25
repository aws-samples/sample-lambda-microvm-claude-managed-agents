package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.ApiException
import com.example.claudemicrovm.worker.anthropic.Heartbeat
import com.example.claudemicrovm.worker.anthropic.SessionEvent
import com.example.claudemicrovm.worker.anthropic.SkillRef
import com.example.claudemicrovm.worker.anthropic.SkillsApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnvironmentWorkerTest {
    @TempDir
    lateinit var workdir: Path

    private val item = workItem("w1", "sesn_1")

    @Test
    fun `heartbeats every ttl-half capped at 30s and shuts down when the work is stopping`() = runTest {
        val api = FakeWork()
        api.heartbeats.addAll(
            listOf(
                Heartbeat("t1", 120, "active", true),
                Heartbeat("t2", 4, "active", true),
                Heartbeat("t3", 4, "stopping", true),
            ),
        )
        var shutdowns = 0

        heartbeatLoop(api, item) { shutdowns++ }

        assertEquals(1, shutdowns)
        assertEquals(listOf("NO_HEARTBEAT", "t1", "t2"), api.expectedHeartbeats)
        // 30s (capped) after the first beat, then 2s (ttl 4s / 2) after the second.
        assertEquals(32_000, currentTime)
    }

    @Test
    fun `a lease that was not extended shuts down`() = runTest {
        val api = FakeWork()
        api.heartbeats.add(Heartbeat("t1", 60, "active", leaseExtended = false))
        var shutdowns = 0

        heartbeatLoop(api, item) { shutdowns++ }

        assertEquals(1, shutdowns)
    }

    @Test
    fun `transient heartbeat failures keep beating and a permanent one shuts down`() = runTest {
        val api = FakeWork()
        api.heartbeats.addAll(listOf(ApiException(503, "unavailable"), ApiException(404, "gone")))
        var shutdowns = 0

        assertFailsWith<ApiException> { heartbeatLoop(api, item) { shutdowns++ } }

        assertEquals(1, shutdowns)
        assertEquals(2, api.expectedHeartbeats.size)
    }

    @Test
    fun `serves the session, then force-stops the work item`() = runTest {
        val work = FakeWork()
        val events = FakeEvents()
        events.addStream(SessionEvent.ToolUse("tu_1", "echo", mapOf("text" to "ok"), false), SessionEvent.Ended("session.status_terminated"))
        val tool = FakeTool("echo") { it.string("text").orEmpty() }

        EnvironmentWorker(work, events, FakeSkills(emptyList()), workdir, tools = { listOf(tool) }).handleItem(item)

        assertEquals(listOf("tu_1"), events.sent.map { it.toolUseId })
        assertEquals(listOf("stop(w1, force=true)"), work.calls)
        assertTrue(tool.closed)
    }

    @Test
    fun `a stopping heartbeat ends the session runner`() = runTest {
        val work = FakeWork()
        work.heartbeats.addAll(listOf(Heartbeat("t1", 60, "active", true), Heartbeat("t2", 60, "stopping", true)))
        val events = FakeEvents()
        events.addStream()
        val tool = FakeTool("echo") { "" }

        EnvironmentWorker(work, events, FakeSkills(emptyList()), workdir, tools = { listOf(tool) }).handleItem(item)

        assertEquals(30_000, currentTime)
        assertEquals(listOf("stop(w1, force=true)"), work.calls)
        assertTrue(tool.closed)
    }

    @Test
    fun `skill setup failures do not prevent serving the session`() = runTest {
        val work = FakeWork()
        val events = FakeEvents()
        events.addStream(SessionEvent.Ended("session.deleted"))
        val broken = object : SkillsApi by FakeSkills(emptyList()) {
            override suspend fun sessionSkills(sessionId: String): List<SkillRef> = throw ApiException(500, "boom")
        }

        EnvironmentWorker(work, events, broken, workdir, tools = { emptyList() }).handleItem(item)

        assertEquals(listOf("stop(w1, force=true)"), work.calls)
    }
}
