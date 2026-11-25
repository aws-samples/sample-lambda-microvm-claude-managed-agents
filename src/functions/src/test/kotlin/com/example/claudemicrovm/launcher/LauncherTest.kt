package com.example.claudemicrovm.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient
import software.amazon.awssdk.services.lambdamicrovms.model.LambdaMicrovmsException
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmRequest
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmResponse
import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyAlreadyInProgressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val TEST_CONFIG = LauncherConfig(
    environmentId = "env_1",
    imageIdentifier = "arn:aws:lambda:us-east-1:123456789012:microvm-image:worker",
    environmentKeyParamName = "/claude/env-key",
    executionRoleArn = "arn:aws:iam::123456789012:role/vm",
    awsRegion = "us-east-1",
)

/** Records every launch and answers with sequential MicroVM ids. */
class RecordingMicroVmClient(private val fail: Boolean = false) : MicroVmClient {
    val launches = mutableListOf<MicroVmLaunch>()

    override fun launchMicroVm(launch: MicroVmLaunch): LaunchedMicroVm {
        launches += launch
        if (fail) throw LaunchMicroVmError("RunMicrovm request failed: throttled")
        return LaunchedMicroVm("mvm_${launches.size}", "https://mvm-${launches.size}.example.test")
    }
}

class LauncherTest {
    private val mapper = ObjectMapper()
    private val start = WebhookEvent("wevt_1", SESSION_RUN_STARTED, "sesn_1")

    private fun launcher(client: MicroVmClient) = Launcher(TEST_CONFIG, client, TokenBucket(1_000.0))

    @Test
    fun `launches one MicroVM per started session with the full launch configuration`() {
        val client = RecordingMicroVmClient()

        val response = launcher(client).handle(start)

        assertEquals(200, response.statusCode)
        assertEquals(mapper.readTree("""{"microvm_id":"mvm_1","session_id":"sesn_1"}"""), mapper.readTree(response.body))
        val launch = client.launches.single()
        assertEquals(
            MicroVmLaunch(
                imageIdentifier = TEST_CONFIG.imageIdentifier,
                runHookPayload = buildRunHookPayload(start, TEST_CONFIG),
                maxLifetimeSeconds = 28_800,
                executionRoleArn = TEST_CONFIG.executionRoleArn,
                idlePolicy = IdlePolicySpec(300, 60, false),
                logGroup = "/aws/lambda/microvms/claude-self-hosted-worker",
                ingressNetworkConnectors = listOf("arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:ALL_INGRESS"),
                egressNetworkConnectors = listOf("arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:INTERNET_EGRESS"),
            ),
            launch,
        )
    }

    @Test
    fun `ignores other event types and malformed events without launching`() {
        val client = RecordingMicroVmClient()
        val launcher = launcher(client)

        for (event in listOf(
            start.copy(dataType = "session.status_idle"),
            start.copy(eventId = ""),
            start.copy(sessionId = ""),
        )) {
            assertEquals(LauncherResponse(200, "ignored"), launcher.handle(event))
        }
        assertTrue(client.launches.isEmpty())
    }

    @Test
    fun `an implausible session id is logged but still launched`() {
        val client = RecordingMicroVmClient()
        assertEquals(200, launcher(client).handle(start.copy(sessionId = "not-a-session")).statusCode)
        assertEquals(1, client.launches.size)
    }

    @Test
    fun `a failed launch returns 502 so Anthropic retries`() {
        val response = launcher(RecordingMicroVmClient(fail = true)).handle(start)
        assertEquals(502, response.statusCode)
        assertEquals(mapper.readTree("""{"error":"run_microvm_failed","session_id":"sesn_1"}"""), mapper.readTree(response.body))
    }

    @Test
    fun `an in-progress duplicate is acknowledged without launching`() {
        val launcher = Launcher(TEST_CONFIG, RecordingMicroVmClient(), executor = {
            throw IdempotencyAlreadyInProgressException("in progress")
        })
        assertEquals(LauncherResponse(200, "in progress"), launcher.handle(start))
    }
}

class AwsMicroVmClientTest {
    private var captured: RunMicrovmRequest? = null

    private fun sdk(response: () -> RunMicrovmResponse) = object : LambdaMicrovmsClient {
        override fun serviceName(): String = LambdaMicrovmsClient.SERVICE_NAME

        override fun close() = Unit

        override fun runMicrovm(request: RunMicrovmRequest): RunMicrovmResponse {
            captured = request
            return response()
        }
    }

    private val launch = MicroVmLaunch(
        imageIdentifier = "arn:image",
        runHookPayload = "{}",
        maxLifetimeSeconds = 600,
        executionRoleArn = "arn:role",
        idlePolicy = IdlePolicySpec(300, 60, false),
        logGroup = "/aws/lambda/microvms/x",
        ingressNetworkConnectors = listOf("arn:in"),
        egressNetworkConnectors = listOf("arn:out"),
    )

    @Test
    fun `maps the launch onto RunMicrovm`() {
        val client = AwsMicroVmClient { sdk { RunMicrovmResponse.builder().microvmId("mvm_1").endpoint("https://e").build() } }

        assertEquals(LaunchedMicroVm("mvm_1", "https://e"), client.launchMicroVm(launch))

        val request = captured!!
        assertEquals("arn:image", request.imageIdentifier())
        assertEquals("{}", request.runHookPayload())
        assertEquals(600, request.maximumDurationInSeconds())
        assertEquals("arn:role", request.executionRoleArn())
        assertEquals(300, request.idlePolicy().maxIdleDurationSeconds())
        assertEquals(60, request.idlePolicy().suspendedDurationSeconds())
        assertEquals(false, request.idlePolicy().autoResumeEnabled())
        assertEquals("/aws/lambda/microvms/x", request.logging().cloudWatch().logGroup())
        assertEquals(listOf("arn:in"), request.ingressNetworkConnectors())
        assertEquals(listOf("arn:out"), request.egressNetworkConnectors())
    }

    @Test
    fun `omits unset optional fields`() {
        val client = AwsMicroVmClient { sdk { RunMicrovmResponse.builder().microvmId("mvm_1").endpoint("https://e").build() } }

        client.launchMicroVm(MicroVmLaunch(imageIdentifier = "arn:image"))

        val request = captured!!
        assertEquals(null, request.runHookPayload())
        assertEquals(null, request.idlePolicy())
        assertEquals(null, request.logging())
        assertTrue(!request.hasIngressNetworkConnectors())
    }

    @Test
    fun `validates the launch before calling AWS`() {
        val client = AwsMicroVmClient { error("must not be created") }
        assertFailsWith<LaunchMicroVmError> { client.launchMicroVm(MicroVmLaunch(imageIdentifier = "")) }
        assertFailsWith<LaunchMicroVmError> { client.launchMicroVm(MicroVmLaunch("arn:image", maxLifetimeSeconds = 0)) }
        assertFailsWith<LaunchMicroVmError> { client.launchMicroVm(MicroVmLaunch("arn:image", maxLifetimeSeconds = 28_801)) }
    }

    @Test
    fun `wraps SDK failures and incomplete responses`() {
        val throttled = AwsMicroVmClient { sdk { throw LambdaMicrovmsException.builder().message("Rate exceeded").build() } }
        val error = assertFailsWith<LaunchMicroVmError> { throttled.launchMicroVm(launch) }
        assertTrue(error.message!!.contains("Rate exceeded"), error.message)
        assertTrue(error.cause is LambdaMicrovmsException)

        val incomplete = AwsMicroVmClient { sdk { RunMicrovmResponse.builder().microvmId("mvm_1").build() } }
        assertFailsWith<LaunchMicroVmError> { incomplete.launchMicroVm(launch) }
    }
}
