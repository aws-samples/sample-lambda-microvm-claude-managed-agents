package com.example.claudemicrovm.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TypesTest {
    private val mapper = ObjectMapper()
    private val requiredEnv = mapOf(
        "ANTHROPIC_ENVIRONMENT_ID" to "env_1",
        "MICROVM_IMAGE_IDENTIFIER" to "arn:aws:lambda:us-east-1:123456789012:microvm-image:worker",
        "ENVIRONMENT_KEY_PARAM_NAME" to "/claude/env-key",
        "MICROVM_EXECUTION_ROLE_ARN" to "arn:aws:iam::123456789012:role/vm",
    )

    @Test
    fun `loads the launcher configuration from the environment with defaults`() {
        assertEquals(
            LauncherConfig(
                environmentId = "env_1",
                imageIdentifier = "arn:aws:lambda:us-east-1:123456789012:microvm-image:worker",
                environmentKeyParamName = "/claude/env-key",
                executionRoleArn = "arn:aws:iam::123456789012:role/vm",
                awsRegion = "us-west-2",
            ),
            LauncherConfig.fromEnv(requiredEnv + ("SIGNING_PARAM_NAME" to "") + ("ANTHROPIC_BASE_URL" to "")),
        )
        val custom = LauncherConfig.fromEnv(
            requiredEnv + mapOf(
                "AWS_REGION" to "eu-west-1",
                "SIGNING_PARAM_NAME" to "/claude/signing",
                "ANTHROPIC_BASE_URL" to "https://api.example.test",
                "MAX_LIFETIME_SECONDS" to "3600",
                "LAUNCH_TPS_LIMIT" to "2",
            ),
        )
        assertEquals("eu-west-1", custom.awsRegion)
        assertEquals("/claude/signing", custom.signingParamName)
        assertEquals("https://api.example.test", custom.baseUrl)
        assertEquals(3600, custom.maxLifetimeSeconds)
        assertEquals(2, custom.launchTpsLimit)
    }

    @Test
    fun `missing required settings fail loudly`() {
        val error = assertFailsWith<IllegalStateException> { LauncherConfig.fromEnv(requiredEnv - "MICROVM_EXECUTION_ROLE_ARN") }
        assertEquals("MICROVM_EXECUTION_ROLE_ARN must be set", error.message)
    }

    @Test
    fun `parses webhook events, defaulting missing fields to empty`() {
        assertEquals(
            WebhookEvent("wevt_1", "session.status_run_started", "sesn_1"),
            WebhookEvent.fromPayload(
                mapper.readTree("""{"type":"event","id":"wevt_1","data":{"type":"session.status_run_started","id":"sesn_1"}}"""),
            ),
        )
        assertEquals(WebhookEvent("", "", ""), WebhookEvent.fromPayload(mapper.readTree("""{"data":null}""")))
        assertEquals(WebhookEvent("", "", ""), WebhookEvent.fromPayload(mapper.readTree("[]")))
    }

    @Test
    fun `the run hook payload carries only non-secret references`() {
        val config = LauncherConfig.fromEnv(requiredEnv + ("AWS_REGION" to "us-east-1"))
        val event = WebhookEvent("wevt_1", SESSION_RUN_STARTED, "sesn_1")

        val payload = mapper.readTree(buildRunHookPayload(event, config))

        assertEquals(
            mapper.readTree(
                """{"version":"1","session":{"ANTHROPIC_SESSION_ID":"sesn_1","ANTHROPIC_ENVIRONMENT_ID":"env_1",
                   "ENVIRONMENT_KEY_PARAM_NAME":"/claude/env-key","AWS_REGION":"us-east-1"}}""",
            ),
            payload,
        )
        val withBaseUrl = mapper.readTree(buildRunHookPayload(event, config.copy(baseUrl = "https://api.example.test")))
        assertEquals("https://api.example.test", withBaseUrl["session"]["ANTHROPIC_BASE_URL"].asText())
        for (forbidden in listOf("ANTHROPIC_API_KEY", "ANTHROPIC_ENVIRONMENT_KEY")) {
            assertFalse(forbidden in buildRunHookPayload(event, config), "$forbidden leaked into the payload")
        }
    }
}
