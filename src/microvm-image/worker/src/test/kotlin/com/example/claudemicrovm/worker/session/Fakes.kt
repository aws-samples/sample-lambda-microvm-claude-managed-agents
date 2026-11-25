package com.example.claudemicrovm.worker.session

import com.example.claudemicrovm.worker.anthropic.EventStream
import com.example.claudemicrovm.worker.anthropic.Heartbeat
import com.example.claudemicrovm.worker.anthropic.SessionEvent
import com.example.claudemicrovm.worker.anthropic.SessionEventsApi
import com.example.claudemicrovm.worker.anthropic.SkillRef
import com.example.claudemicrovm.worker.anthropic.SkillsApi
import com.example.claudemicrovm.worker.anthropic.ToolResult
import com.example.claudemicrovm.worker.anthropic.WorkApi
import com.example.claudemicrovm.worker.anthropic.WorkItem
import com.example.claudemicrovm.worker.tools.AgentTool
import com.example.claudemicrovm.worker.tools.ToolInput
import kotlinx.coroutines.channels.Channel
import java.nio.file.Files
import java.nio.file.Path

/** A tool that records its calls and whether it was closed. */
class FakeTool(override val name: String, private val block: suspend (ToolInput) -> String) : AgentTool {
    val calls = mutableListOf<ToolInput>()
    var closed = false

    override suspend fun run(input: ToolInput): String {
        calls += input
        return block(input)
    }

    override fun close() {
        closed = true
    }
}

/** A live stream fed from [events]; it ends when the channel is closed and otherwise waits for more. */
class ScriptedStream(private val events: Channel<SessionEvent>) : EventStream {
    override suspend fun next(): SessionEvent? = events.receiveCatching().getOrNull()

    override fun close() = Unit
}

/** Scripted session events: [history], one stream per `stream()` call, and recorded results. */
class FakeEvents : SessionEventsApi {
    val history = mutableListOf<SessionEvent>()
    val sent = mutableListOf<ToolResult>()
    val sendFailures = ArrayDeque<Exception>()
    var sendAttempts = 0
    var streamCalls = 0
    private val streams = ArrayDeque<() -> EventStream>()

    /** Queue a stream yielding [events]; it then stays open unless [end]. Returns its channel for more events. */
    fun addStream(vararg events: SessionEvent, end: Boolean = false): Channel<SessionEvent> {
        val channel = Channel<SessionEvent>(Channel.UNLIMITED)
        events.forEach { channel.trySend(it) }
        if (end) channel.close()
        streams.addLast { ScriptedStream(channel) }
        return channel
    }

    /** Queue a `stream()` call that fails with [error]. */
    fun failStream(error: Exception) {
        streams.addLast { throw error }
    }

    override suspend fun stream(sessionId: String): EventStream {
        streamCalls++
        return (streams.removeFirstOrNull() ?: { ScriptedStream(Channel()) }).invoke()
    }

    override suspend fun history(sessionId: String): List<SessionEvent> = history.toList()

    override suspend fun send(sessionId: String, result: ToolResult) {
        sendAttempts++
        sendFailures.removeFirstOrNull()?.let { throw it }
        sent += result
    }
}

/** Scripted work queue: [polls] are work items, `null` (empty queue) or exceptions. */
class FakeWork(vararg polls: Any?) : WorkApi {
    private val polls = ArrayDeque(polls.toList())

    /** Heartbeat responses (or exceptions); once exhausted every beat reports an active, extended lease. */
    val heartbeats = ArrayDeque<Any>()
    val expectedHeartbeats = mutableListOf<String>()
    val calls = mutableListOf<String>()
    val failAck = mutableSetOf<String>()
    val pollParams = mutableListOf<Pair<Long?, Long?>>()

    override suspend fun poll(environmentId: String, workerId: String, blockMs: Long?, reclaimOlderThanMs: Long?): WorkItem? {
        calls += "poll"
        pollParams += blockMs to reclaimOlderThanMs
        return when (val next = polls.removeFirstOrNull()) {
            is Exception -> throw next
            else -> next as WorkItem?
        }
    }

    override suspend fun ack(workId: String, environmentId: String) {
        if (workId in failAck) throw IllegalStateException("ack failed")
        calls += "ack($workId)"
    }

    override suspend fun heartbeat(workId: String, environmentId: String, expectedLastHeartbeat: String): Heartbeat {
        expectedHeartbeats += expectedLastHeartbeat
        return when (val next = heartbeats.removeFirstOrNull()) {
            is Exception -> throw next
            is Heartbeat -> next
            else -> Heartbeat("hb-${expectedHeartbeats.size}", 60, "active", leaseExtended = true)
        }
    }

    override suspend fun stop(workId: String, environmentId: String, force: Boolean) {
        calls += "stop($workId, force=$force)"
    }
}

/** Skills backed by in-memory archives keyed by `skillId@version`. */
class FakeSkills(
    private val skills: List<SkillRef>,
    private val versions: Map<String, List<String>> = emptyMap(),
    private val names: Map<String, String> = emptyMap(),
    private val archives: Map<String, ByteArray> = emptyMap(),
) : SkillsApi {
    override suspend fun sessionSkills(sessionId: String): List<SkillRef> = skills

    override suspend fun versions(skillId: String): List<String> = versions[skillId].orEmpty()

    override suspend fun versionName(skillId: String, version: String): String =
        names["$skillId@$version"] ?: error("unknown skill $skillId@$version")

    override suspend fun download(skillId: String, version: String, dest: Path) {
        Files.write(dest, archives["$skillId@$version"] ?: error("no archive for $skillId@$version"))
    }
}

fun workItem(id: String, sessionId: String = "sesn_$id", type: String = "session") =
    WorkItem(id, "env_1", type, sessionId)
