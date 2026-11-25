package com.example.claudemicrovm.worker

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HookServerTest {
    private val runs = LinkedBlockingQueue<RunRequest>()
    private val server = HookServer(0, "127.0.0.1") { runs += it }.also { it.start() }
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    @AfterEach
    fun stop() = server.close()

    private fun request(path: String, method: String = "POST", body: String = ""): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun hook(name: String, body: String = "") = request("$HOOK_PREFIX/$name", body = body)

    @Test
    fun `lifecycle hooks answer ok`() {
        for (name in listOf("ready", "validate", "resume", "suspend", "terminate")) {
            val response = hook(name)
            assertEquals(200, response.statusCode(), name)
            assertEquals("""{"status":"ok"}""", response.body())
        }
    }

    @Test
    fun `other paths and methods are not found`() {
        assertEquals(404, hook("nope").statusCode())
        assertEquals(404, request("$HOOK_PREFIX/ready", method = "GET").statusCode())
        assertEquals(404, request("/ready").statusCode())
        assertEquals(404, request(HOOK_PREFIX).statusCode())
        assertEquals(404, request("${HOOK_PREFIX}x/ready").statusCode())
    }

    @Test
    fun `run acknowledges the dispatch and starts the session exactly once`() {
        val payload = mapper.writeValueAsString(
            mapOf(
                "version" to "1",
                "session" to mapOf(
                    "ANTHROPIC_SESSION_ID" to "sesn_1",
                    "ANTHROPIC_ENVIRONMENT_ID" to "env_1",
                    "ENVIRONMENT_KEY_PARAM_NAME" to "/claude/env-key",
                    "AWS_REGION" to "us-west-2",
                ),
            ),
        )
        val envelope = mapper.writeValueAsString(mapOf("microvmId" to "mvm_1", "runHookPayload" to payload))

        val first = hook("run", envelope)
        val second = hook("run", envelope)

        assertEquals(200, first.statusCode())
        assertEquals("""{"status":"accepted"}""", first.body())
        assertEquals(200, second.statusCode())
        assertEquals(
            RunRequest(Dispatch("sesn_1", "env_1", "/claude/env-key", "us-west-2", null), "mvm_1"),
            runs.poll(5, TimeUnit.SECONDS),
        )
        assertNull(runs.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `run rejects payloads that are not JSON or lack a session id`() {
        val invalid = hook("run", "{not json")
        assertEquals(400, invalid.statusCode())
        assertEquals("""{"error":"invalid run payload"}""", invalid.body())

        val missing = hook("run", """{"microvmId":"m","runHookPayload":"{\"session\":{}}"}""")
        assertEquals(400, missing.statusCode())
        assertEquals("""{"error":"missing ANTHROPIC_SESSION_ID"}""", missing.body())

        assertNull(runs.poll(200, TimeUnit.MILLISECONDS))
    }
}
