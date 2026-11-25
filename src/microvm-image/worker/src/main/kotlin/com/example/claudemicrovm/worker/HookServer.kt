package com.example.claudemicrovm.worker

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Path prefix of the Lambda MicroVMs lifecycle hooks. */
const val HOOK_PREFIX = "/aws/lambda-microvms/runtime/v1"

private val log = LoggerFactory.getLogger(HookServer::class.java)
private val MAPPER = ObjectMapper()

/**
 * Serves the MicroVM lifecycle hooks as HTTP endpoints:
 *
 * - `POST …/ready`     image build: app initialized, safe to snapshot
 * - `POST …/validate`  post-build smoke test of the snapshot
 * - `POST …/run`       once, after run from snapshot; carries the session dispatch
 * - `POST …/resume`, `…/suspend`, `…/terminate`  state transitions
 *
 * `/run` is acknowledged immediately (200) and then handed to [onRun] — at most
 * once per VM, however often the hook is delivered.
 */
class HookServer(
    port: Int,
    host: String = "0.0.0.0",
    private val onRun: (RunRequest) -> Unit,
) : AutoCloseable {
    private val sessionStarted = AtomicBoolean(false)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        executor = Executors.newVirtualThreadPerTaskExecutor()
    }

    /** The bound port (useful when constructed with port 0). */
    val port: Int get() = server.address.port

    fun start() = server.start()

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.rawPath
            val rest = path.removePrefix(HOOK_PREFIX)
            if (exchange.requestMethod != "POST" || rest == path || !rest.startsWith("/")) {
                respond(exchange, 404)
                return
            }
            when (rest.substring(1)) {
                "ready", "validate", "resume", "suspend", "terminate" -> respond(exchange, 200, mapOf("status" to "ok"))
                "run" -> handleRun(exchange)
                else -> respond(exchange, 404)
            }
        } catch (e: Exception) {
            log.error("worker: hook handler failed", e)
        } finally {
            exchange.close()
        }
    }

    private fun handleRun(exchange: HttpExchange) {
        val raw = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        when (val parsed = parseRunRequest(raw)) {
            is RunParseResult.Invalid -> {
                log.error("worker: /run hook error: {}", parsed.reason)
                respond(exchange, 400, mapOf("error" to "invalid run payload"))
            }
            RunParseResult.MissingSessionId -> {
                log.error("worker: /run hook missing ANTHROPIC_SESSION_ID in payload: {}", raw)
                respond(exchange, 400, mapOf("error" to "missing ANTHROPIC_SESSION_ID"))
            }
            is RunParseResult.Ok -> {
                respond(exchange, 200, mapOf("status" to "accepted"))
                if (sessionStarted.compareAndSet(false, true)) onRun(parsed.request)
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Map<String, String>? = null) {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        val bytes = MAPPER.writeValueAsBytes(body)
        exchange.responseHeaders.set("content-type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
