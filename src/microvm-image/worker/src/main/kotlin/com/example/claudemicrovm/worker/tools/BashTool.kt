package com.example.claudemicrovm.worker.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.UUID

/** Output kept per command; older output is dropped (and flagged as truncated). */
internal const val BASH_OUTPUT_LIMIT = 100 * 1024

/** Default per-command timeout when the call does not pass `timeout_ms`. */
internal const val BASH_DEFAULT_TIMEOUT_MS = 120_000L

private val ANSI_RE = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")
private val EXIT_CODE_RE = Regex("^-?\\d+")

/** The shell could not complete a command: it timed out or the shell died. */
class BashSessionException(message: String) : Exception(message)

/** Result of one [BashSession.exec] call: combined stdout/stderr and the exit status. */
data class ExecResult(val output: String, val exitCode: Int)

/**
 * The environment for the spawned shell: the process environment minus the
 * whole `ANTHROPIC_*` namespace, so commands the agent runs cannot read the
 * worker's Anthropic credentials.
 */
internal fun scrubbedShellEnv(source: Map<String, String> = System.getenv()): Map<String, String> =
    source.filterKeys { !it.startsWith("ANTHROPIC_") }

/**
 * A persistent `/bin/bash` process. State (cwd, env vars, background jobs)
 * survives across [exec] calls. Uses pipes rather than a PTY so input is never
 * echoed; each command's exit status is framed by a per-call random sentinel.
 */
class BashSession(dir: Path, env: Map<String, String> = scrubbedShellEnv()) : AutoCloseable {
    private class Waiter(val sentinel: String, val done: CompletableDeferred<Unit>)

    private val lock = Any()
    private val buffer = StringBuilder()
    private var truncated = false
    private var waiting: Waiter? = null

    @Volatile
    private var closedFlag = false

    private val process: Process = ProcessBuilder("/bin/bash", "--noprofile", "--norc")
        .directory(dir.toFile())
        .redirectErrorStream(true)
        .also { builder ->
            builder.environment().apply {
                clear()
                putAll(env)
                // Shell-control settings the sentinel parsing relies on.
                put("PS1", "")
                put("PS2", "")
                put("TERM", "dumb")
            }
        }
        .start()

    init {
        Thread(::pumpOutput, "bash-session-output").apply {
            isDaemon = true
            start()
        }
    }

    /** Whether the underlying shell process has exited (or the session was closed). */
    val closed: Boolean get() = closedFlag

    /**
     * Run [command] in the shell and wait for it to finish, at most [timeoutMs].
     * Throws [BashSessionException] on timeout or when the shell dies; after
     * either, the session must be discarded (a late sentinel would confuse it).
     */
    suspend fun exec(command: String, timeoutMs: Long = BASH_DEFAULT_TIMEOUT_MS): ExecResult {
        // The `''` split keeps the literal sentinel out of what we write to stdin,
        // so a command that echoes its own input cannot spoof the framing.
        val sentinel = "__ANT_CMD_${UUID.randomUUID()}_DONE__"
        val sentinelSplit = sentinel.substring(0, 8) + "''" + sentinel.substring(8)
        // </dev/null: a stdin-reading command (`cat`, `read`) gets EOF instead of
        // blocking on the shared pipe until the timeout.
        val wrapped = "{ $command\n} </dev/null 2>&1; printf '\\n$sentinelSplit%d\\n' \$?\n"
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (closedFlag) throw BashSessionException("bash session terminated")
            buffer.setLength(0)
            truncated = false
            waiting = Waiter(sentinel, done)
        }
        try {
            try {
                withContext(Dispatchers.IO) {
                    process.outputStream.write(wrapped.toByteArray(Charsets.UTF_8))
                    process.outputStream.flush()
                }
            } catch (_: IOException) {
                throw BashSessionException("bash session terminated")
            }
            withTimeoutOrNull(timeoutMs) { done.await() }
                ?: throw BashSessionException("bash command timed out after ${timeoutMs}ms")
        } finally {
            synchronized(lock) {
                if (waiting?.done === done) waiting = null
            }
        }
        synchronized(lock) {
            val idx = buffer.indexOf(sentinel)
            // The shell closed (or was killed) before emitting the sentinel.
            if (idx < 0) throw BashSessionException("bash session terminated")
            val exitCode = EXIT_CODE_RE.find(buffer.substring(idx + sentinel.length))?.value?.toIntOrNull() ?: -1
            var output = ANSI_RE.replace(buffer.substring(0, idx), "").trimEnd('\n')
            if (truncated) output = "[output truncated]\n$output"
            return ExecResult(output, exitCode)
        }
    }

    /** Kill the shell together with every process it started. Idempotent. */
    override fun close() {
        val waiter = synchronized(lock) {
            closedFlag = true
            waiting.also { waiting = null }
        }
        waiter?.done?.complete(Unit)
        // Kill the whole tree so foreground and background jobs die with the shell.
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
    }

    private fun pumpOutput() {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                val chunk = CharArray(8192)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    append(chunk, n)
                }
            }
        } catch (_: IOException) {
            // The stream was closed by close().
        } finally {
            // Wake an in-flight exec so it fails fast instead of waiting for its deadline.
            val waiter = synchronized(lock) {
                closedFlag = true
                waiting.also { waiting = null }
            }
            waiter?.done?.complete(Unit)
        }
    }

    private fun append(chunk: CharArray, n: Int) {
        val ready = synchronized(lock) {
            buffer.appendRange(chunk, 0, n)
            if (buffer.length > BASH_OUTPUT_LIMIT) {
                buffer.delete(0, buffer.length - BASH_OUTPUT_LIMIT)
                truncated = true
            }
            val waiter = waiting ?: return
            // Only the region touched by this chunk can complete the sentinel.
            val from = maxOf(0, buffer.length - n - waiter.sentinel.length)
            if (buffer.indexOf(waiter.sentinel, from) < 0) return
            waiting = null
            waiter
        }
        ready.done.complete(Unit)
    }
}

/**
 * `bash`: run a command in a persistent shell rooted at the workdir. Calls are
 * serialized so concurrent commands cannot interleave on the shell's stdin.
 */
class BashTool(private val ctx: AgentToolContext) : AgentTool {
    override val name = "bash"

    private val mutex = Mutex()
    private var session: BashSession? = null

    override suspend fun run(input: ToolInput): String = mutex.withLock {
        val restart = input.truthy("restart")
        val command = input.string("command")
        if (restart) {
            session?.close()
            session = null
        }
        if (command.isNullOrEmpty()) {
            if (restart) return@withLock "bash session restarted"
            throw ToolError("bash: command is required")
        }
        val shell = session ?: BashSession(ctx.workdir, ctx.env ?: scrubbedShellEnv()).also { session = it }
        val result = try {
            shell.exec(command, input.long("timeout_ms") ?: BASH_DEFAULT_TIMEOUT_MS)
        } catch (e: Exception) {
            // Timeout, cancellation or a dead shell: the still-running command would
            // emit a stale sentinel, so discard this session; the next call starts fresh.
            shell.close()
            session = null
            if (e is CancellationException) throw e
            throw ToolError("bash: ${e.message}")
        }
        if (result.exitCode != 0) throw ToolError(result.output.ifEmpty { "exit ${result.exitCode}" })
        result.output
    }

    override fun close() {
        session?.close()
        session = null
    }
}
