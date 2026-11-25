package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.ApiException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkPollerTest {
    @Test
    fun `drain mode acks and emits each item, stops it after processing, and completes when empty`() = runTest {
        val api = FakeWork(workItem("w1"), workItem("w2"), null)

        WorkPoller(api, "env_1", drain = true).works().collect { api.calls += "handle(${it.id})" }

        assertEquals(
            listOf(
                "poll", "ack(w1)", "handle(w1)", "stop(w1, force=false)",
                "poll", "ack(w2)", "handle(w2)", "stop(w2, force=false)",
                "poll",
            ),
            api.calls,
        )
        assertEquals(listOf(POLL_BLOCK_MS to null), api.pollParams.distinct())
    }

    @Test
    fun `with autoStop off the consumer owns the stop`() = runTest {
        val api = FakeWork(workItem("w1"), null)

        val items = WorkPoller(api, "env_1", reclaimOlderThanMs = 2_000, drain = true, autoStop = false).works().toList()

        assertEquals(listOf("w1"), items.map { it.id })
        assertEquals(listOf("poll", "ack(w1)", "poll"), api.calls)
        assertEquals(POLL_BLOCK_MS to 2_000L, api.pollParams.first())
    }

    @Test
    fun `items whose ack fails are skipped`() = runTest {
        val api = FakeWork(workItem("w1"), workItem("w2"), null)
        api.failAck += "w1"

        val items = WorkPoller(api, "env_1", drain = true).works().toList()

        assertEquals(listOf("w2"), items.map { it.id })
    }

    @Test
    fun `transient poll errors back off and a permanent 4xx fails the flow`() = runTest {
        val api = FakeWork(ApiException(503, "unavailable"), workItem("w1"), ApiException(401, "bad key"))
        val handled = mutableListOf<String>()

        val error = assertFailsWith<ApiException> {
            WorkPoller(api, "env_1", drain = true).works().collect { handled += it.id }
        }

        assertEquals(401, error.status)
        assertEquals(listOf("w1"), handled)
        // One jittered 1s backoff: between 750ms and 1s.
        assertTrue(currentTime in 750..1_000, "waited $currentTime ms")
    }

    @Test
    fun `retryable 4xx statuses are not fatal`() = runTest {
        val api = FakeWork(ApiException(429, "slow down"), ApiException(409, "conflict"), null)

        val items = WorkPoller(api, "env_1", drain = true).works().toList()

        assertTrue(items.isEmpty())
        assertEquals(listOf("poll", "poll", "poll"), api.calls)
    }

    @Test
    fun `the consumer can pick one session and stop polling`() = runTest {
        val api = FakeWork(workItem("w1", "sesn_other"), workItem("w2", "sesn_mine"), workItem("w3", "sesn_mine"))

        val picked = WorkPoller(api, "env_1", drain = true, autoStop = false).works()
            .filter { it.sessionId == "sesn_mine" }
            .take(1)
            .toList()

        assertEquals(listOf("w2"), picked.map { it.id })
        assertEquals(listOf("poll", "ack(w1)", "poll", "ack(w2)"), api.calls)
    }

    @Test
    fun `poll backoff doubles up to a cap`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 32_000L, 60_000L, 60_000L), listOf(0, 1, 2, 5, 6, 40).map(::pollBackoff))
    }
}
