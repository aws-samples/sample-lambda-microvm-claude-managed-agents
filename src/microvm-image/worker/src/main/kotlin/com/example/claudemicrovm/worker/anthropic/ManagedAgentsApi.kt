package com.example.claudemicrovm.worker.anthropic

import java.nio.file.Path

/** An HTTP error from the Anthropic API, reduced to what the retry decisions need. */
class ApiException(val status: Int, message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

/** True when [e] is an [ApiException] whose HTTP status equals [code]. */
fun isStatus(e: Throwable, code: Int): Boolean = e is ApiException && e.status == code

/** True when [e] is an [ApiException] with a 4xx status. */
fun is4xx(e: Throwable): Boolean = e is ApiException && e.status in 400..499

/**
 * True for a 4xx that the SDK's retry policy would *not* retry, i.e. a permanent
 * client error. 408 (request timeout), 409 (lock timeout) and 429 (rate limit)
 * are retryable, so they are not treated as fatal.
 */
fun isFatal4xx(e: Throwable): Boolean = is4xx(e) && !isStatus(e, 408) && !isStatus(e, 409) && !isStatus(e, 429)

/** A claimed self-hosted work item; for `session` work, [sessionId] names the session to serve. */
data class WorkItem(val id: String, val environmentId: String, val type: String, val sessionId: String)

/** A work-item lease heartbeat response. [state] is the raw API value (`active`, `stopping`, `stopped`, ...). */
data class Heartbeat(val lastHeartbeat: String, val ttlSeconds: Long, val state: String, val leaseExtended: Boolean)

/** The self-hosted environment work queue (`/v1/environments/{id}/work`). */
interface WorkApi {
    /** Long-poll for the next work item; `null` when the queue is empty. */
    suspend fun poll(environmentId: String, workerId: String, blockMs: Long?, reclaimOlderThanMs: Long?): WorkItem?

    suspend fun ack(workId: String, environmentId: String)

    suspend fun heartbeat(workId: String, environmentId: String, expectedLastHeartbeat: String): Heartbeat

    suspend fun stop(workId: String, environmentId: String, force: Boolean)
}

/**
 * The result posted back for one tool call: a `user.tool_result`, or a
 * `user.custom_tool_result` when [custom] (answering an `agent.custom_tool_use`).
 */
data class ToolResult(val toolUseId: String, val custom: Boolean, val isError: Boolean, val text: String)

/** A live session event stream. */
interface EventStream : AutoCloseable {
    /** The next event, or `null` once the stream has ended. Cancelling the caller closes the stream. */
    suspend fun next(): SessionEvent?
}

/** A managed-agents session's event log (`/v1/sessions/{id}/events`). */
interface SessionEventsApi {
    /** Attach to the live event stream. */
    suspend fun stream(sessionId: String): EventStream

    /** The session's full event history, oldest first. */
    suspend fun history(sessionId: String): List<SessionEvent>

    suspend fun send(sessionId: String, result: ToolResult)
}

/** A skill attached to the session's agent; [version] may be an alias such as `latest`. */
data class SkillRef(val skillId: String, val version: String?)

/** Skill lookup and download for the session's agent. */
interface SkillsApi {
    suspend fun sessionSkills(sessionId: String): List<SkillRef>

    /** The version identifiers published for [skillId]. */
    suspend fun versions(skillId: String): List<String>

    /** The display name of [skillId] at the concrete [version]. */
    suspend fun versionName(skillId: String, version: String): String

    /** Download the archive of [skillId] at the concrete [version] into the file [dest]. */
    suspend fun download(skillId: String, version: String, dest: Path)
}

/** The API surfaces a worker needs, all authenticated with the environment key. */
data class ManagedAgentsApis(val work: WorkApi, val events: SessionEventsApi, val skills: SkillsApi)
