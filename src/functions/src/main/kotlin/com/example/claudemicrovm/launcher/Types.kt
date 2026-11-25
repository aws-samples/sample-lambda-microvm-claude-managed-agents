package com.example.claudemicrovm.launcher

import com.fasterxml.jackson.databind.JsonNode

/** Configuration for the launcher Lambda. */
data class LauncherConfig(
    val environmentId: String,
    val imageIdentifier: String,
    val environmentKeyParamName: String,
    val executionRoleArn: String,
    val awsRegion: String,
    val signingParamName: String? = null,
    val baseUrl: String? = null,
    val maxLifetimeSeconds: Int = DEFAULT_MAX_LIFETIME_SECONDS,
    val launchTpsLimit: Int = DEFAULT_LAUNCH_TPS_LIMIT,
) {
    companion object {
        /** Read the configuration from the Lambda environment (set by the SAM template). */
        fun fromEnv(env: Map<String, String> = System.getenv()): LauncherConfig {
            fun required(name: String) = env[name] ?: throw IllegalStateException("$name must be set")
            fun optional(name: String) = env[name]?.takeIf { it.isNotEmpty() }
            return LauncherConfig(
                environmentId = required("ANTHROPIC_ENVIRONMENT_ID"),
                imageIdentifier = required("MICROVM_IMAGE_IDENTIFIER"),
                environmentKeyParamName = required("ENVIRONMENT_KEY_PARAM_NAME"),
                executionRoleArn = required("MICROVM_EXECUTION_ROLE_ARN"),
                awsRegion = env["AWS_REGION"] ?: "us-west-2",
                signingParamName = optional("SIGNING_PARAM_NAME"),
                baseUrl = optional("ANTHROPIC_BASE_URL"),
                maxLifetimeSeconds = env["MAX_LIFETIME_SECONDS"]?.toInt() ?: DEFAULT_MAX_LIFETIME_SECONDS,
                launchTpsLimit = env["LAUNCH_TPS_LIMIT"]?.toInt() ?: DEFAULT_LAUNCH_TPS_LIMIT,
            )
        }
    }
}

/** A parsed Anthropic webhook event; missing fields are empty strings. */
data class WebhookEvent(val eventId: String, val dataType: String, val sessionId: String) {
    companion object {
        fun fromPayload(payload: JsonNode): WebhookEvent {
            val data = payload.path("data")
            return WebhookEvent(eventId = payload.text("id"), dataType = data.text("type"), sessionId = data.text("id"))
        }
    }
}

private fun JsonNode.text(field: String): String = get(field)?.takeIf { it.isTextual }?.asText() ?: ""
