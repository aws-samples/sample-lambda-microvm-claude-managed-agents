package com.example.claudemicrovm.worker

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

private val MAPPER = ObjectMapper()

/**
 * The per-session dispatch the launcher sends through `runHookPayload`. Only
 * non-secret data: the environment key is fetched by the VM itself from the
 * SSM parameter [environmentKeyParamName].
 */
data class Dispatch(
    val sessionId: String,
    val environmentId: String?,
    val environmentKeyParamName: String?,
    val region: String?,
    val baseUrl: String?,
)

/** A parsed `/run` hook call; [microvmId] comes from the envelope, not the inner dispatch. */
data class RunRequest(val dispatch: Dispatch, val microvmId: String?)

sealed interface RunParseResult {
    data class Ok(val request: RunRequest) : RunParseResult

    data object MissingSessionId : RunParseResult

    data class Invalid(val reason: String) : RunParseResult
}

/**
 * Parse a `/run` hook body. The service wraps the launcher's payload as
 * `{ "microvmId": ..., "runHookPayload": "<JSON>" }`; the payload itself is
 * `{ "version": "1", "session": { "ANTHROPIC_SESSION_ID": ..., ... } }`. A bare
 * dispatch (no envelope, no `session` wrapper) is accepted too.
 */
fun parseRunRequest(raw: String): RunParseResult {
    val envelope = try {
        if (raw.isEmpty()) MAPPER.createObjectNode() else MAPPER.readTree(raw)
    } catch (e: JacksonException) {
        return RunParseResult.Invalid(e.originalMessage)
    }
    if (envelope !is ObjectNode) return RunParseResult.Invalid("payload is not a JSON object")
    val payload = envelope.get("runHookPayload")
    val inner = when {
        payload == null || payload.isNull || (payload.isTextual && payload.asText().isEmpty()) -> envelope
        payload.isTextual -> try {
            MAPPER.readTree(payload.asText())
        } catch (e: JacksonException) {
            return RunParseResult.Invalid("runHookPayload: ${e.originalMessage}")
        }
        else -> payload
    }
    if (inner !is ObjectNode) return RunParseResult.Invalid("runHookPayload is not a JSON object")
    val dispatch = inner.get("session") as? ObjectNode ?: inner
    val sessionId = dispatch.text("ANTHROPIC_SESSION_ID") ?: return RunParseResult.MissingSessionId
    return RunParseResult.Ok(
        RunRequest(
            Dispatch(
                sessionId = sessionId,
                environmentId = dispatch.text("ANTHROPIC_ENVIRONMENT_ID"),
                environmentKeyParamName = dispatch.text("ENVIRONMENT_KEY_PARAM_NAME"),
                region = dispatch.text("AWS_REGION"),
                baseUrl = dispatch.text("ANTHROPIC_BASE_URL"),
            ),
            microvmId = envelope.text("microvmId"),
        ),
    )
}

/** The non-empty scalar value of [field], as text. */
private fun JsonNode.text(field: String): String? =
    get(field)?.takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotEmpty() }
