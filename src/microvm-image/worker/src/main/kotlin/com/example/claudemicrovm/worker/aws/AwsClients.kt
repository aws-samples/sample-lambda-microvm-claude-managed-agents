package com.example.claudemicrovm.worker.aws

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient
import software.amazon.awssdk.services.ssm.SsmClient

private val log = LoggerFactory.getLogger("com.example.claudemicrovm.worker.aws.AwsClients")

/**
 * Read the environment key from the SSM SecureString [parameterName] with the
 * VM's execution role. [region] defaults to the SDK's region provider chain.
 */
suspend fun fetchEnvironmentKey(parameterName: String, region: String?): String = withContext(Dispatchers.IO) {
    SsmClient.builder()
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .apply { if (!region.isNullOrEmpty()) region(Region.of(region)) }
        .build()
        .use { ssm ->
            val value = ssm.getParameter { it.name(parameterName).withDecryption(true) }.parameter()?.value()
            if (value.isNullOrEmpty()) throw IllegalStateException("SSM parameter $parameterName has no value")
            value
        }
}

/**
 * Terminate this MicroVM to release compute as soon as the session finishes.
 * Best effort: on failure (or without a [microvmId]) the idle policy reclaims the VM.
 */
suspend fun terminateSelf(microvmId: String?, region: String?) {
    if (microvmId.isNullOrEmpty()) {
        log.warn("worker: no microvmId in /run envelope; leaving termination to the idle policy")
        return
    }
    try {
        withContext(Dispatchers.IO) {
            LambdaMicrovmsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .apply { if (!region.isNullOrEmpty()) region(Region.of(region)) }
                .build()
                .use { client -> client.terminateMicrovm { it.microvmIdentifier(microvmId) } }
        }
        log.info("worker: requested termination of microvm {}", microvmId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("worker: terminate-microvm failed; idle policy will reclaim the VM", e)
    }
}
