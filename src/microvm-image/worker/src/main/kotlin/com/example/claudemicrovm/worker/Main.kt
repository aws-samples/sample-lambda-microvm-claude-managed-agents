// In-MicroVM worker for the Claude self-hosted sandbox.
//
// Serves the Lambda MicroVMs lifecycle hooks on port 9000 (see HookServer). The
// /run hook receives the dispatch payload (session id, environment id, parameter
// reference, region). It acknowledges immediately (200) then:
//   1. Fetches the environment key from SSM Parameter Store (VM execution role).
//   2. Polls the work queue for the matching session.
//   3. Handles the session's tool calls.
//   4. Terminates the MicroVM (TerminateMicrovm) to release compute at once;
//      the idle policy is only the fallback if the call can't be made.
package com.example.claudemicrovm.worker

import com.example.claudemicrovm.worker.aws.terminateSelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private const val DEFAULT_HOOK_PORT = 9000
private const val HOOK_HOST = "0.0.0.0"

private val log = LoggerFactory.getLogger("com.example.claudemicrovm.worker.Main")

fun main() {
    val port = System.getenv("HOOK_PORT")?.toIntOrNull() ?: DEFAULT_HOOK_PORT
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessions = SessionHandler()
    val server = HookServer(port, HOOK_HOST) { request ->
        scope.launch { exitProcess(serveSession(request, sessions)) }
    }
    server.start()
    log.info("worker: hook server listening on {}:{}", HOOK_HOST, server.port)
}

/** Serve the dispatched session, then terminate this VM; returns the process exit code. */
internal suspend fun serveSession(
    request: RunRequest,
    sessions: SessionHandler,
    terminate: suspend (microvmId: String?, region: String?) -> Unit = ::terminateSelf,
): Int {
    val exitCode = try {
        sessions.handle(request.dispatch)
        0
    } catch (e: Exception) {
        log.error("worker: session failed", e)
        1
    }
    terminate(request.microvmId, request.dispatch.region)
    return exitCode
}
