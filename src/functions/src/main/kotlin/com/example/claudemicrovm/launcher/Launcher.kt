package com.example.claudemicrovm.launcher

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import software.amazon.lambda.powertools.idempotency.exceptions.IdempotencyAlreadyInProgressException

private const val SESSION_ID_PREFIX = "sesn_"
private const val SESSION_ID_MAX_LEN = 128

private val log = LoggerFactory.getLogger(Launcher::class.java)
private val MAPPER = ObjectMapper()

/** A launched session; also the response the idempotency store keeps per webhook event. */
data class LaunchResult @JsonCreator constructor(
    @param:JsonProperty("microvm_id") @get:JsonProperty("microvm_id") val microvmId: String,
)

/** An API Gateway proxy response. */
data class LauncherResponse(val statusCode: Int, val body: String)

/** Pure launch logic, testable without AWS. */
class Launcher(
    private val config: LauncherConfig,
    private val client: MicroVmClient,
    private val rateLimiter: TokenBucket = TokenBucket(config.launchTpsLimit.toDouble()),
    executor: ((WebhookEvent) -> LaunchResult)? = null,
) {
    private var executor: (WebhookEvent) -> LaunchResult = executor ?: ::launchAndDispatch

    /** Install the idempotency-wrapped executor. */
    fun setExecutor(executor: (WebhookEvent) -> LaunchResult) {
        this.executor = executor
    }

    /** Launch one MicroVM with the run hook payload. Throws [LaunchMicroVmError] on failure. */
    fun launchAndDispatch(event: WebhookEvent): LaunchResult {
        val runHookPayload = buildRunHookPayload(event, config)
        rateLimiter.acquire()
        val launched = client.launchMicroVm(
            MicroVmLaunch(
                imageIdentifier = config.imageIdentifier,
                runHookPayload = runHookPayload,
                maxLifetimeSeconds = config.maxLifetimeSeconds,
                executionRoleArn = config.executionRoleArn,
                idlePolicy = DEFAULT_IDLE_POLICY,
                logGroup = DEFAULT_LOG_GROUP,
                ingressNetworkConnectors = listOf(allIngressArn(config.awsRegion)),
                egressNetworkConnectors = listOf(internetEgressArn(config.awsRegion)),
            ),
        )
        log.info("launched microvm_id={} for session_id={}", launched.microvmId, event.sessionId)
        return LaunchResult(launched.microvmId)
    }

    /** Handle one parsed, verified webhook event. */
    fun handle(event: WebhookEvent): LauncherResponse {
        if (event.dataType != SESSION_RUN_STARTED) {
            log.info("ignoring non-start event type={}", event.dataType)
            return IGNORED
        }

        // Malformed events get a 200 (not a 4xx) so Anthropic doesn't retry:
        // a malformed event won't become valid.
        if (event.eventId.isEmpty()) {
            log.warn("ignoring event: missing event_id (cannot dedupe)")
            return IGNORED
        }
        if (event.sessionId.isEmpty()) {
            log.warn("ignoring event id={}: missing session_id", event.eventId)
            return IGNORED
        }

        // Loose shape sanity — log-only, never rejects.
        if (!(event.sessionId.startsWith(SESSION_ID_PREFIX) && event.sessionId.length <= SESSION_ID_MAX_LEN)) {
            log.warn("event id={} has an implausible session_id shape (proceeding): '{}'", event.eventId, event.sessionId)
        }

        val result = try {
            executor(event)
        } catch (e: IdempotencyAlreadyInProgressException) {
            log.info("event id={} already in progress; not re-launching", event.eventId)
            return LauncherResponse(200, "in progress")
        } catch (e: LaunchMicroVmError) {
            log.error("RunMicrovm failed for session_id={}: {}", event.sessionId, e.message)
            return LauncherResponse(
                502,
                MAPPER.writeValueAsString(mapOf("error" to "run_microvm_failed", "session_id" to event.sessionId)),
            )
        }
        return LauncherResponse(
            200,
            MAPPER.writeValueAsString(mapOf("microvm_id" to result.microvmId, "session_id" to event.sessionId)),
        )
    }

    private companion object {
        val IGNORED = LauncherResponse(200, "ignored")
    }
}
