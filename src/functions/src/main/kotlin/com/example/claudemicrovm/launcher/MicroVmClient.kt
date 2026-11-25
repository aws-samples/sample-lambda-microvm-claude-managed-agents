package com.example.claudemicrovm.launcher

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient
import software.amazon.awssdk.services.lambdamicrovms.model.CloudWatchLogging
import software.amazon.awssdk.services.lambdamicrovms.model.IdlePolicy
import software.amazon.awssdk.services.lambdamicrovms.model.Logging
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmRequest

const val MIN_DURATION_SECONDS = 1
const val MAX_DURATION_SECONDS = 28_800

/**
 * Raised when RunMicrovm fails. Unchecked on purpose: the idempotency wrapper
 * rethrows unchecked exceptions unchanged (and wraps checked ones).
 */
class LaunchMicroVmError(message: String, val sessionId: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** A MicroVM idle policy (all three fields are required by the API). */
data class IdlePolicySpec(val maxIdleDurationSeconds: Int, val suspendedDurationSeconds: Int, val autoResumeEnabled: Boolean)

/** A RunMicrovm request; `null` fields are omitted. */
data class MicroVmLaunch(
    val imageIdentifier: String,
    val runHookPayload: String? = null,
    val maxLifetimeSeconds: Int? = null,
    val executionRoleArn: String? = null,
    val idlePolicy: IdlePolicySpec? = null,
    /** CloudWatch Logs group for the MicroVM's output. */
    val logGroup: String? = null,
    val ingressNetworkConnectors: List<String>? = null,
    val egressNetworkConnectors: List<String>? = null,
)

/** Result of a successful RunMicrovm call. */
data class LaunchedMicroVm(val microvmId: String, val endpoint: String)

/** Launches MicroVMs. */
fun interface MicroVmClient {
    fun launchMicroVm(launch: MicroVmLaunch): LaunchedMicroVm
}

/** [MicroVmClient] over the AWS SDK `lambda-microvms` client (created on first use). */
class AwsMicroVmClient(clientFactory: () -> LambdaMicrovmsClient) : MicroVmClient {
    constructor(region: String) : this({
        LambdaMicrovmsClient.builder()
            .region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()
    })

    private val client by lazy(clientFactory)

    override fun launchMicroVm(launch: MicroVmLaunch): LaunchedMicroVm {
        if (launch.imageIdentifier.isEmpty()) throw LaunchMicroVmError("image_identifier is required to launch a MicroVM")
        val lifetime = launch.maxLifetimeSeconds
        if (lifetime != null && lifetime !in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
            throw LaunchMicroVmError("max_lifetime_seconds must be between $MIN_DURATION_SECONDS and $MAX_DURATION_SECONDS")
        }

        val request = RunMicrovmRequest.builder().imageIdentifier(launch.imageIdentifier)
        launch.runHookPayload?.let { request.runHookPayload(it) }
        lifetime?.let { request.maximumDurationInSeconds(it) }
        launch.executionRoleArn?.let { request.executionRoleArn(it) }
        launch.idlePolicy?.let {
            request.idlePolicy(
                IdlePolicy.builder()
                    .maxIdleDurationSeconds(it.maxIdleDurationSeconds)
                    .suspendedDurationSeconds(it.suspendedDurationSeconds)
                    .autoResumeEnabled(it.autoResumeEnabled)
                    .build(),
            )
        }
        launch.logGroup?.let {
            request.logging(Logging.builder().cloudWatch(CloudWatchLogging.builder().logGroup(it).build()).build())
        }
        launch.ingressNetworkConnectors?.let { request.ingressNetworkConnectors(it) }
        launch.egressNetworkConnectors?.let { request.egressNetworkConnectors(it) }

        val response = try {
            client.runMicrovm(request.build())
        } catch (e: Exception) {
            throw LaunchMicroVmError("RunMicrovm request failed for image '${launch.imageIdentifier}': ${e.message}", cause = e)
        }
        val microvmId = response.microvmId()
        val endpoint = response.endpoint()
        if (microvmId.isNullOrEmpty() || endpoint.isNullOrEmpty()) {
            throw LaunchMicroVmError("RunMicrovm response missing id/endpoint: $response")
        }
        return LaunchedMicroVm(microvmId, endpoint)
    }
}
