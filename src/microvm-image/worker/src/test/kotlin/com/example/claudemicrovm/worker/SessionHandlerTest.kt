package com.example.claudemicrovm.worker

import com.example.claudemicrovm.worker.anthropic.ManagedAgentsApis
import com.example.claudemicrovm.worker.anthropic.SessionEvent
import com.example.claudemicrovm.worker.session.FakeEvents
import com.example.claudemicrovm.worker.session.FakeSkills
import com.example.claudemicrovm.worker.session.FakeWork
import com.example.claudemicrovm.worker.session.workItem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionHandlerTest {
    @TempDir
    lateinit var workdir: Path

    private val dispatch = Dispatch("sesn_mine", "env_1", "/claude/env-key", "us-west-2", null)

    @Test
    fun `fetches the key, claims the dispatched session and serves it`() = runTest {
        val work = FakeWork(workItem("w1", "sesn_other"), workItem("w2", "sesn_mine"))
        val events = FakeEvents()
        events.addStream(SessionEvent.Ended(SessionEvent.SESSION_STATUS_TERMINATED))
        val keyRequests = mutableListOf<Pair<String, String?>>()
        val clients = mutableListOf<Pair<String, String?>>()
        val handler = SessionHandler(
            environmentKey = { name, region ->
                keyRequests += name to region
                "env-key"
            },
            apis = { key, baseUrl ->
                clients += key to baseUrl
                ManagedAgentsApis(work, events, FakeSkills(emptyList()))
            },
            workdir = workdir,
        )

        handler.handle(dispatch)

        assertEquals(listOf<Pair<String, String?>>("/claude/env-key" to "us-west-2"), keyRequests)
        assertEquals(listOf<Pair<String, String?>>("env-key" to null), clients)
        assertEquals(listOf("poll", "ack(w1)", "poll", "ack(w2)", "stop(w2, force=true)"), work.calls)
        assertEquals(2_000L, work.pollParams.first().second)
    }

    @Test
    fun `returns when the queue drains without the session`() = runTest {
        val work = FakeWork(workItem("w1", "sesn_other"), null)
        val handler = SessionHandler({ _, _ -> "k" }, { _, _ -> ManagedAgentsApis(work, FakeEvents(), FakeSkills(emptyList())) }, workdir)

        handler.handle(dispatch)

        assertEquals(listOf("poll", "ack(w1)", "poll"), work.calls)
    }

    @Test
    fun `an incomplete dispatch fails before touching any API`() = runTest {
        val handler = SessionHandler({ _, _ -> error("unexpected") }, { _, _ -> error("unexpected") }, workdir)
        assertFailsWith<IllegalArgumentException> { handler.handle(dispatch.copy(environmentId = null)) }
        assertFailsWith<IllegalArgumentException> { handler.handle(dispatch.copy(environmentKeyParamName = null)) }
    }

    @Test
    fun `serveSession terminates the VM after success and after failure`() = runTest {
        val terminated = mutableListOf<Pair<String?, String?>>()
        val ok = SessionHandler({ _, _ -> "k" }, { _, _ -> ManagedAgentsApis(FakeWork(null), FakeEvents(), FakeSkills(emptyList())) }, workdir)
        val failing = SessionHandler({ _, _ -> throw IllegalStateException("SSM parameter has no value") }, { _, _ -> error("unused") }, workdir)

        assertEquals(0, serveSession(RunRequest(dispatch, "mvm_1"), ok) { id, region -> terminated += id to region })
        assertEquals(1, serveSession(RunRequest(dispatch, null), failing) { id, region -> terminated += id to region })

        assertEquals(listOf<Pair<String?, String?>>("mvm_1" to "us-west-2", null to "us-west-2"), terminated)
    }
}
