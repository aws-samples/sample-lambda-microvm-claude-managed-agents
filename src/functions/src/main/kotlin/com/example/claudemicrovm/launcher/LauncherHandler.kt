// Launcher Lambda: launch one ephemeral MicroVM per started session.
//
// On a `session.status_run_started` webhook event, verifies the Anthropic webhook
// signature in-process, then launches one MicroVM via RunMicrovm with the session
// dispatch delivered through `runHookPayload`.
//
// Security model:
// - The launcher passes only a *reference* to the environment-key secret into the
//   MicroVM. The environment key is fetched by the VM's own execution role.
// - The organization API key never reaches AWS compute.
//
// Behavior:
// - Rejects deliveries that fail signature verification (401).
// - Ignores non-`session.status_run_started` events (200).
// - Dedupes by webhook event id (DynamoDB-backed idempotency).
// - Enforces the RunMicrovm 5 TPS rate limit.
// - On RunMicrovm failure, returns non-2xx so Anthropic retries.
package com.example.claudemicrovm.launcher

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.lambda.powertools.idempotency.Idempotency
import software.amazon.lambda.powertools.logging.PowertoolsLogging
import software.amazon.lambda.powertools.parameters.ssm.SSMProvider
import java.time.temporal.ChronoUnit
import java.util.Base64

private const val SECRET_CACHE_SECONDS = 300

private val log = LoggerFactory.getLogger(LauncherHandler::class.java)
private val MAPPER = ObjectMapper()

/** Lambda entry point (API Gateway REST proxy integration). */
class LauncherHandler internal constructor(
    configProvider: () -> LauncherConfig,
    /** Reads (and caches) an SSM SecureString by name. */
    private val secrets: (parameterName: String) -> String,
    private val verifier: WebhookVerifier,
    private val microVmClient: (LauncherConfig) -> MicroVmClient,
    /** Wraps the launch in idempotency; `null` launches without dedupe. */
    private val idempotentExecutor: ((Launcher) -> (WebhookEvent) -> LaunchResult)?,
) : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    /** Used by the Lambda runtime: configuration from the environment, real AWS clients. */
    constructor() : this(
        configProvider = { LauncherConfig.fromEnv() },
        secrets = ssmSecrets(),
        verifier = AnthropicWebhookVerifier(),
        microVmClient = { AwsMicroVmClient(it.awsRegion) },
        idempotentExecutor = System.getenv("IDEMPOTENCY_TABLE")?.takeIf { it.isNotEmpty() }?.let { table ->
            configureIdempotency(dynamoDbIdempotencyStore(table))
            ::idempotentExecutor
        },
    )

    // Built once per execution environment, so the rate limiter spans invocations.
    private val config by lazy(configProvider)
    private val launcher by lazy {
        Launcher(config, microVmClient(config)).also { launcher ->
            idempotentExecutor?.let { wrap -> launcher.setExecutor(wrap(launcher)) }
        }
    }

    override fun handleRequest(input: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent =
        PowertoolsLogging.withLogging(context) {
            val response = handle(input, context)
            APIGatewayProxyResponseEvent().withStatusCode(response.statusCode).withBody(response.body)
        }

    internal fun handle(request: APIGatewayProxyRequestEvent, context: Context?): LauncherResponse {
        val rawBody = request.rawBody()

        config.signingParamName?.let { parameterName ->
            if (!verifier.verify(rawBody, request.headers.orEmpty(), secrets(parameterName))) {
                log.info("denying webhook: signature verification failed")
                return LauncherResponse(401, "signature verification failed")
            }
        }

        if (idempotentExecutor != null && context != null) Idempotency.registerLambdaContext(context)

        val payload = if (rawBody.isEmpty()) MAPPER.createObjectNode() else MAPPER.readTree(rawBody)
        return launcher.handle(WebhookEvent.fromPayload(payload))
    }
}

/** The body exactly as delivered (the signature covers these bytes). */
private fun APIGatewayProxyRequestEvent.rawBody(): String {
    val body = body ?: return ""
    return if (isBase64Encoded == true) String(Base64.getDecoder().decode(body), Charsets.UTF_8) else body
}

/** SecureString reads through Powertools Parameters, cached for five minutes. */
private fun ssmSecrets(): (String) -> String {
    val provider by lazy {
        SSMProvider.builder()
            .withClient(SsmClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build())
            .build()
    }
    return { name -> provider.withDecryption().withMaxAge(SECRET_CACHE_SECONDS, ChronoUnit.SECONDS).get(name) }
}
