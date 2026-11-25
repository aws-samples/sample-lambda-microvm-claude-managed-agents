package com.example.claudemicrovm.worker.anthropic

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

private val MAPPER = ObjectMapper()
private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}

/** The subset of managed-agents session events the session tool runner acts on. */
sealed class SessionEvent {
    abstract val type: String

    /** `agent.tool_use` (or `agent.custom_tool_use` when [custom]): a call to execute locally. */
    data class ToolUse(val id: String, val name: String, val input: Map<String, Any?>, val custom: Boolean) :
        SessionEvent() {
        override val type: String get() = if (custom) AGENT_CUSTOM_TOOL_USE else AGENT_TOOL_USE
    }

    /** `user.tool_result` / `user.custom_tool_result`: the call [toolUseId] has been answered. */
    data class ToolAnswered(val toolUseId: String, override val type: String) : SessionEvent()

    /** `session.status_idle`; [stopReason] is `stop_reason.type` (e.g. `end_turn`). */
    data class Idle(val stopReason: String?) : SessionEvent() {
        override val type: String get() = SESSION_STATUS_IDLE
        val isEndTurn: Boolean get() = stopReason == "end_turn"
    }

    /** `session.status_terminated` / `session.deleted`: the session is over. */
    data class Ended(override val type: String) : SessionEvent()

    /** Any other event. */
    data class Other(override val type: String) : SessionEvent()

    companion object {
        const val AGENT_TOOL_USE = "agent.tool_use"
        const val AGENT_CUSTOM_TOOL_USE = "agent.custom_tool_use"
        const val USER_TOOL_RESULT = "user.tool_result"
        const val USER_CUSTOM_TOOL_RESULT = "user.custom_tool_result"
        const val SESSION_STATUS_IDLE = "session.status_idle"
        const val SESSION_STATUS_TERMINATED = "session.status_terminated"
        const val SESSION_DELETED = "session.deleted"

        /** Map an event's wire JSON onto a [SessionEvent]. */
        fun fromJson(node: JsonNode): SessionEvent = when (val type = node.path("type").asText("")) {
            AGENT_TOOL_USE, AGENT_CUSTOM_TOOL_USE -> ToolUse(
                id = node.path("id").asText(""),
                name = node.path("name").asText(""),
                input = node.get("input")?.takeIf { it.isObject }?.let { MAPPER.convertValue(it, MAP_TYPE) }.orEmpty(),
                custom = type == AGENT_CUSTOM_TOOL_USE,
            )
            USER_TOOL_RESULT -> ToolAnswered(node.path("tool_use_id").asText(""), type)
            USER_CUSTOM_TOOL_RESULT -> ToolAnswered(node.path("custom_tool_use_id").asText(""), type)
            SESSION_STATUS_IDLE -> Idle(node.path("stop_reason").get("type")?.takeIf { it.isTextual }?.asText())
            SESSION_STATUS_TERMINATED, SESSION_DELETED -> Ended(type)
            else -> Other(type)
        }

        /** Parse a single event from its JSON text. */
        fun fromJson(json: String): SessionEvent = fromJson(MAPPER.readTree(json))
    }
}
