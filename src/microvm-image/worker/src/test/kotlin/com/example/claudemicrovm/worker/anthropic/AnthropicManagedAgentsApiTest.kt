package com.example.claudemicrovm.worker.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the SDK adapter against a local stand-in for the Anthropic API. */
class AnthropicManagedAgentsApiTest {
    private data class Recorded(val method: String, val path: String, val query: String?, val headers: Map<String, List<String>>, val body: String) {
        /** Every `anthropic-beta` value sent, comma-joined. */
        val betas: String get() = headers["anthropic-beta"].orEmpty().joinToString(",")
    }

    private val mapper = ObjectMapper()
    private val requests = CopyOnWriteArrayList<Recorded>()
    private val routes = mutableListOf<Pair<(Recorded) -> Boolean, (HttpExchange) -> Unit>>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val recorded = Recorded(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestURI.rawQuery,
                    exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v },
                    exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                )
                requests += recorded
                val route = routes.firstOrNull { it.first(recorded) }?.second
                if (route == null) exchange.sendResponseHeaders(404, -1) else route(exchange)
            }
        }
        start()
    }
    private val client = AnthropicOkHttpClient.builder()
        .authToken("env-key")
        .baseUrl("http://127.0.0.1:${server.address.port}")
        .maxRetries(0)
        .build()
    private val api = AnthropicManagedAgentsApi(client)

    @AfterEach
    fun stop() {
        client.close()
        server.stop(0)
    }

    private fun route(method: String, pathSuffix: String, handler: (HttpExchange) -> Unit) {
        routes += { r: Recorded -> r.method == method && r.path.endsWith(pathSuffix) } to handler
    }

    private fun HttpExchange.json(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.set("content-type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.sse(vararg events: Pair<String, String>) {
        responseHeaders.set("content-type", "text/event-stream")
        sendResponseHeaders(200, 0)
        responseBody.use { out ->
            for ((event, data) in events) out.write("event: $event\ndata: $data\n\n".toByteArray())
        }
    }

    @Test
    fun `posts tool results as user_tool_result or user_custom_tool_result with only the bearer credential`() {
        route("POST", "/v1/sessions/sesn_1/events") { it.json(200, """{"data":[]}""") }

        runBlocking {
            api.send("sesn_1", ToolResult("tu_1", custom = false, isError = true, text = "boom"))
            api.send("sesn_1", ToolResult("ctu_1", custom = true, isError = false, text = "ok"))
        }

        assertEquals(
            mapper.readTree(
                """{"events":[{"type":"user.tool_result","tool_use_id":"tu_1","is_error":true,"content":[{"type":"text","text":"boom"}]}]}""",
            ),
            mapper.readTree(requests[0].body),
        )
        assertEquals(
            mapper.readTree(
                """{"events":[{"type":"user.custom_tool_result","custom_tool_use_id":"ctu_1","is_error":false,"content":[{"type":"text","text":"ok"}]}]}""",
            ),
            mapper.readTree(requests[1].body),
        )
        val headers = requests[0].headers
        assertEquals(listOf("Bearer env-key"), headers["authorization"])
        assertNull(headers["x-api-key"])
        assertTrue(headers["anthropic-beta"].orEmpty().any { "managed-agents-2026-04-01" in it }, "beta header: $headers")
    }

    @Test
    fun `streams session events and maps them`() {
        route("GET", "/v1/sessions/sesn_1/events/stream") {
            it.sse(
                "agent.tool_use" to """{"type":"agent.tool_use","id":"tu_1","name":"bash","input":{"command":"ls","timeout_ms":500}}""",
                "agent.custom_tool_use" to """{"type":"agent.custom_tool_use","id":"ctu_1","name":"lookup","input":{}}""",
                "user.tool_result" to """{"type":"user.tool_result","id":"ev_3","tool_use_id":"tu_1","content":[]}""",
                "session.status_idle" to """{"type":"session.status_idle","id":"ev_4","stop_reason":{"type":"end_turn"}}""",
                "session.status_terminated" to """{"type":"session.status_terminated","id":"ev_5"}""",
            )
        }

        val events = runBlocking {
            api.stream("sesn_1").use { stream -> generateSequence { runBlocking { stream.next() } }.toList() }
        }

        assertEquals(
            listOf(
                SessionEvent.ToolUse("tu_1", "bash", mapOf("command" to "ls", "timeout_ms" to 500), custom = false),
                SessionEvent.ToolUse("ctu_1", "lookup", emptyMap(), custom = true),
                SessionEvent.ToolAnswered("tu_1", SessionEvent.USER_TOOL_RESULT),
                SessionEvent.Idle("end_turn"),
                SessionEvent.Ended(SessionEvent.SESSION_STATUS_TERMINATED),
            ),
            events,
        )
    }

    @Test
    fun `lists the full history across pages`() {
        route("GET", "/v1/sessions/sesn_1/events") { exchange ->
            if (exchange.requestURI.rawQuery.orEmpty().contains("page=p2")) {
                exchange.json(200, """{"data":[{"type":"session.status_idle","id":"ev_2","stop_reason":{"type":"end_turn"}}],"next_page":null}""")
            } else {
                exchange.json(200, """{"data":[{"type":"agent.tool_use","id":"tu_1","name":"read","input":{"file_path":"a"}}],"next_page":"p2"}""")
            }
        }

        val history = runBlocking { api.history("sesn_1") }

        assertEquals(
            listOf(SessionEvent.ToolUse("tu_1", "read", mapOf("file_path" to "a"), false), SessionEvent.Idle("end_turn")),
            history,
        )
        assertTrue(requests[0].query.orEmpty().contains("limit=1000"), "query: ${requests[0].query}")
    }

    @Test
    fun `polls the work queue, treating null and 204 as empty`() {
        var calls = 0
        route("GET", "/v1/environments/env_1/work/poll") { exchange ->
            when (calls++) {
                0 -> exchange.json(
                    200,
                    """{"id":"work_1","environment_id":"env_1","data":{"type":"session","id":"sesn_1"},"state":"starting"}""",
                )
                1 -> exchange.json(200, "null")
                else -> exchange.sendResponseHeaders(204, -1)
            }
        }

        val polled = runBlocking { List(3) { api.poll("env_1", "worker-1", 999, 2000) } }

        assertEquals(listOf(WorkItem("work_1", "env_1", "session", "sesn_1"), null, null), polled)
        assertEquals(listOf("worker-1"), requests[0].headers["anthropic-worker-id"])
        val query = requests[0].query.orEmpty()
        assertTrue("block_ms=999" in query && "reclaim_older_than_ms=2000" in query, "query: $query")
    }

    @Test
    fun `acks, heartbeats and stops work items`() {
        val work = """{"id":"work_1","environment_id":"env_1","data":{"type":"session","id":"sesn_1"}}"""
        route("POST", "/v1/environments/env_1/work/work_1/ack") { it.json(200, work) }
        route("POST", "/v1/environments/env_1/work/work_1/stop") { it.json(200, work) }
        route("POST", "/v1/environments/env_1/work/work_1/heartbeat") {
            it.json(200, """{"last_heartbeat":"2026-11-18T00:00:00Z","lease_extended":true,"state":"stopping","ttl_seconds":40}""")
        }

        val heartbeat = runBlocking {
            api.ack("work_1", "env_1")
            api.stop("work_1", "env_1", force = true)
            api.stop("work_1", "env_1", force = false)
            api.heartbeat("work_1", "env_1", "NO_HEARTBEAT")
        }

        assertEquals(Heartbeat("2026-11-18T00:00:00Z", 40, "stopping", leaseExtended = true), heartbeat)
        assertEquals(mapper.readTree("""{"force":true}"""), mapper.readTree(requests[1].body))
        assertEquals(mapper.readTree("{}"), mapper.readTree(requests[2].body.ifEmpty { "{}" }))
        assertEquals("expected_last_heartbeat=NO_HEARTBEAT", requests[3].query)
    }

    @Test
    fun `API errors surface with their HTTP status`() {
        route("POST", "/ack") { it.json(404, """{"type":"error","error":{"type":"not_found_error","message":"no such work"}}""") }

        val error = assertFailsWith<ApiException> { runBlocking { api.ack("work_9", "env_1") } }

        assertEquals(404, error.status)
        assertTrue(isFatal4xx(error))
    }

    @Test
    fun `reads session skills and skill versions`() {
        route("GET", "/v1/sessions/sesn_1") {
            it.json(
                200,
                """{"id":"sesn_1","agent":{"id":"agent_1","skills":[{"type":"custom","skill_id":"skill_1","version":"latest"},{"type":"anthropic","skill_id":"pdf","version":"20251013"}]}}""",
            )
        }
        route("GET", "/v1/skills/skill_1/versions") {
            it.json(200, """{"data":[{"id":"skillver_1","version":"1759178010641129","name":"one"},{"id":"skillver_2","version":"1759178010641130","name":"two"}],"next_page":null}""")
        }
        route("GET", "/v1/skills/skill_1/versions/1759178010641130") { it.json(200, """{"id":"skillver_2","name":"two"}""") }

        val (skills, versions, name) = runBlocking {
            Triple(api.sessionSkills("sesn_1"), api.versions("skill_1"), api.versionName("skill_1", "1759178010641130"))
        }

        assertEquals(listOf(SkillRef("skill_1", "latest"), SkillRef("pdf", "20251013")), skills)
        assertEquals(listOf("1759178010641129", "1759178010641130"), versions)
        assertEquals("two", name)
        assertTrue("managed-agents-2026-04-01" in requests[0].betas, "session beta: ${requests[0].betas}")
        val skillCalls = requests.filter { it.path.startsWith("/v1/skills/") }
        assertEquals(2, skillCalls.size)
        // Like the Node SDK, skill-version calls request the skills beta shapes (with `version`).
        skillCalls.forEach { assertTrue("skills-2025-10-02" in it.betas, "${it.path} beta: ${it.betas}") }
    }

    @Test
    fun `downloads a skill version archive to a file`(@TempDir tmp: Path) {
        val archive = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x0a, 0x00, 0x7f)
        route("GET", "/v1/skills/skill_1/versions/42/content") { exchange ->
            exchange.responseHeaders.set("content-type", "application/zip")
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        val dest = tmp.resolve("skill.zip")

        runBlocking { api.download("skill_1", "42", dest) }

        assertContentEquals(archive, Files.readAllBytes(dest))
        val request = requests.single()
        assertTrue("skills-2025-10-02" in request.betas, "beta: ${request.betas}")
        assertEquals(listOf("Bearer env-key"), request.headers["authorization"])
    }
}
