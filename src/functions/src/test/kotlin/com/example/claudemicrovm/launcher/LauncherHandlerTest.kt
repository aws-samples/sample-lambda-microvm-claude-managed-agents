package com.example.claudemicrovm.launcher

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherHandlerTest {
    private val mapper = ObjectMapper()
    private val secret = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val body = """{"type":"event","id":"wevt_1","created_at":"2026-11-18T20:19:00Z","data":{"type":"session.status_run_started","id":"sesn_1"}}"""
    private val requestedSecrets = mutableListOf<String>()
    private val client = RecordingMicroVmClient()

    private fun handler(
        config: LauncherConfig = TEST_CONFIG.copy(signingParamName = "/claude/signing"),
        verifier: WebhookVerifier = AnthropicWebhookVerifier(),
        idempotent: Boolean = false,
    ) = LauncherHandler(
        configProvider = { config },
        secrets = { name ->
            requestedSecrets += name
            secret
        },
        verifier = verifier,
        microVmClient = { client },
        idempotentExecutor = if (idempotent) ::idempotentExecutor else null,
    )

    /** Standard Webhooks signature headers for [payload], as Anthropic sends them. */
    private fun signed(
        payload: String,
        signingSecret: String = secret,
        timestamp: Long = Instant.now().epochSecond,
        id: String = "msg_1",
    ): Map<String, String> {
        val key = Base64.getDecoder().decode(signingSecret.removePrefix("whsec_"))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val signature = Base64.getEncoder().encodeToString(mac.doFinal("$id.$timestamp.$payload".toByteArray()))
        return mapOf(
            "Content-Type" to "application/json",
            "Webhook-Id" to id,
            "Webhook-Timestamp" to timestamp.toString(),
            "Webhook-Signature" to "v1,$signature",
        )
    }

    private fun request(payload: String, headers: Map<String, String>, base64: Boolean = false) =
        APIGatewayProxyRequestEvent()
            .withBody(if (base64) Base64.getEncoder().encodeToString(payload.toByteArray()) else payload)
            .withHeaders(headers)
            .withIsBase64Encoded(base64)

    @Test
    fun `a correctly signed start event launches a MicroVM`() {
        val response = handler().handle(request(body, signed(body)), null)

        assertEquals(200, response.statusCode, response.body)
        assertEquals(mapper.readTree("""{"microvm_id":"mvm_1","session_id":"sesn_1"}"""), mapper.readTree(response.body))
        assertEquals(listOf("/claude/signing"), requestedSecrets)
        assertEquals(1, client.launches.size)
    }

    @Test
    fun `base64-encoded bodies are verified over the decoded bytes`() {
        val response = handler().handle(request(body, signed(body), base64 = true), null)
        assertEquals(200, response.statusCode, response.body)
    }

    @Test
    fun `tampered, stale or wrongly signed deliveries are denied before any launch`() {
        val otherSecret = "whsec_" + Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val tampered = body.replace("sesn_1", "sesn_2")
        val cases = listOf(
            request(tampered, signed(body)),
            request(body, signed(body, timestamp = Instant.now().epochSecond - 3_600)),
            request(body, signed(body, signingSecret = otherSecret)),
            request(body, mapOf("Content-Type" to "application/json")),
        )

        for (case in cases) {
            assertEquals(LauncherResponse(401, "signature verification failed"), handler().handle(case, null))
        }
        assertTrue(client.launches.isEmpty())
    }

    @Test
    fun `verification is skipped when no signing secret is configured`() {
        val response = handler(config = TEST_CONFIG, verifier = { _, _, _ -> error("must not verify") })
            .handle(request(body, emptyMap()), null)

        assertEquals(200, response.statusCode)
        assertTrue(requestedSecrets.isEmpty())
    }

    @Test
    fun `verified non-start events are ignored`() {
        val idle = body.replace("session.status_run_started", "session.status_idle")
        assertEquals(LauncherResponse(200, "ignored"), handler().handle(request(idle, signed(idle)), null))
        assertTrue(client.launches.isEmpty())
    }

    @Test
    fun `handleRequest runs with the Lambda context and dedupes redeliveries`() {
        configureIdempotency(InMemoryIdempotencyStore())
        val handler = handler(idempotent = true)

        val first = handler.handleRequest(request(body, signed(body)), FakeContext)
        val retry = handler.handleRequest(request(body, signed(body, id = "msg_2")), FakeContext)

        assertEquals(200, first.statusCode)
        assertEquals(first.body, retry.body)
        assertEquals(1, client.launches.size)
    }
}

object FakeContext : Context {
    override fun getAwsRequestId() = "req-1"

    override fun getLogGroupName() = "/aws/lambda/launcher"

    override fun getLogStreamName() = "stream"

    override fun getFunctionName() = "claude-microvm-sandbox-launcher"

    override fun getFunctionVersion() = "\$LATEST"

    override fun getInvokedFunctionArn() = "arn:aws:lambda:us-east-1:123456789012:function:claude-microvm-sandbox-launcher"

    override fun getIdentity(): CognitoIdentity? = null

    override fun getClientContext(): ClientContext? = null

    override fun getRemainingTimeInMillis() = 30_000

    override fun getMemoryLimitInMB() = 1024

    override fun getLogger(): LambdaLogger = object : LambdaLogger {
        override fun log(message: String) = println(message)

        override fun log(message: ByteArray) = println(String(message))
    }
}
