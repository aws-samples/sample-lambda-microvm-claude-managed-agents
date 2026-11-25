package com.example.claudemicrovm.launcher

import com.fasterxml.jackson.databind.ObjectMapper

private val MAPPER = ObjectMapper()

/**
 * Build the per-session dispatch delivered to the MicroVM as `runHookPayload`
 * (the body of its `/run` lifecycle hook). It carries only non-secret data:
 * the session id, environment id, region, and a *reference* to the SSM
 * SecureString holding the environment key — never the key itself.
 */
fun buildRunHookPayload(event: WebhookEvent, config: LauncherConfig): String {
    val session = linkedMapOf(
        "ANTHROPIC_SESSION_ID" to event.sessionId,
        "ANTHROPIC_ENVIRONMENT_ID" to config.environmentId,
        "ENVIRONMENT_KEY_PARAM_NAME" to config.environmentKeyParamName,
        "AWS_REGION" to config.awsRegion,
    )
    if (config.baseUrl != null) session["ANTHROPIC_BASE_URL"] = config.baseUrl
    return MAPPER.writeValueAsString(mapOf("version" to RUN_HOOK_PAYLOAD_VERSION, "session" to session))
}
