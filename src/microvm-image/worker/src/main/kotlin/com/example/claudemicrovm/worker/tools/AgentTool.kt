package com.example.claudemicrovm.worker.tools

import java.nio.file.Path

/**
 * Thrown from [AgentTool.run] to return [message] to the model as the tool's
 * error result (`is_error: true`). Any other exception is reported to the model
 * as `Error: <message>`.
 */
class ToolError(message: String) : Exception(message)

/**
 * A tool executed locally on behalf of a managed-agents session: the session
 * tool runner dispatches every `agent.tool_use` / `agent.custom_tool_use` event
 * whose name matches [name] to [run] and posts the returned text back.
 */
interface AgentTool : AutoCloseable {
    /** The name the model addresses this tool by. */
    val name: String

    /** Runs one call and returns its text result; throws [ToolError] for an error result. */
    suspend fun run(input: ToolInput): String

    /** Releases state kept across calls (e.g. the persistent shell). */
    override fun close() {}
}

/** Read-only view over a tool call's JSON `input` object, decoded into plain Kotlin values. */
class ToolInput(val values: Map<String, Any?>) {
    /** The value of [key] as a string; non-string scalars are stringified. */
    fun string(key: String): String? = when (val v = values[key]) {
        null -> null
        is String -> v
        else -> v.toString()
    }

    /** JavaScript-style truthiness of [key]: missing, `false`, `0` and `""` are false. */
    fun truthy(key: String): Boolean = when (val v = values[key]) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }

    /** The value of [key] as an integer, or `null` when missing or not numeric. */
    fun long(key: String): Long? = when (val v = values[key]) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    companion object {
        fun of(vararg entries: Pair<String, Any?>): ToolInput = ToolInput(mapOf(*entries))
    }
}

/** Default size cap for `read` / `edit`, which both load the whole file into memory. */
const val DEFAULT_MAX_FILE_BYTES: Long = 256L * 1024

/**
 * The per-session context the toolset is bound to.
 *
 * Trust model: the file tools (`read`, `write`, `edit`, `glob`, `grep`) confine
 * every path to [workdir] — symlink-aware — unless [unrestrictedPaths] is set.
 * `bash` is an unrestricted shell; the MicroVM is its sandbox.
 */
data class AgentToolContext(
    val workdir: Path,
    /** Allow absolute paths and paths outside [workdir]. */
    val unrestrictedPaths: Boolean = false,
    /** Size cap for `read` / `edit`; `null` disables it. */
    val maxFileBytes: Long? = DEFAULT_MAX_FILE_BYTES,
    /** Full replacement for the bash environment; `null` uses the process env minus `ANTHROPIC_*`. */
    val env: Map<String, String>? = null,
)

/** The `agent_toolset_20260401` tools — `bash`, `read`, `write`, `edit`, `glob`, `grep` — bound to [ctx]. */
fun agentToolset20260401(ctx: AgentToolContext): List<AgentTool> = listOf(
    BashTool(ctx),
    ReadTool(ctx),
    WriteTool(ctx),
    EditTool(ctx),
    GlobTool(ctx),
    GrepTool(ctx),
)
