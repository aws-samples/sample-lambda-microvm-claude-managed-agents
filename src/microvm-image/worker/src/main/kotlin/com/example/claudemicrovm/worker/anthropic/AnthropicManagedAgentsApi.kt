package com.example.claudemicrovm.worker.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.http.StreamResponse
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.environments.work.BetaSelfHostedWorkStopRequest
import com.anthropic.models.beta.environments.work.WorkAckParams
import com.anthropic.models.beta.environments.work.WorkHeartbeatParams
import com.anthropic.models.beta.environments.work.WorkPollParams
import com.anthropic.models.beta.environments.work.WorkStopParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserCustomToolResultEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserToolResultEventParams
import com.anthropic.models.beta.sessions.events.EventListParams
import com.anthropic.models.beta.sessions.events.EventSendParams
import com.anthropic.models.beta.skills.versions.VersionDownloadParams
import com.anthropic.models.beta.skills.versions.VersionListParams
import com.anthropic.models.beta.skills.versions.VersionRetrieveParams
import com.example.claudemicrovm.worker.util.withCancellationHook
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Skill-version calls opt into the `skills-2025-10-02` response shapes, which
 * carry the numeric `version` field that skill version resolution relies on (the
 * Node SDK the original worker used sends it by default). The Java SDK no longer
 * sends it on its own and deprecates the constant in favour of the GA shapes.
 */
@Suppress("DEPRECATION")
private val SKILLS_BETA = AnthropicBeta.SKILLS_2025_10_02

/**
 * A client authenticated *only* with [environmentKey] as a bearer token (no
 * `X-Api-Key`), which is what the work-queue and per-session endpoints expect.
 */
fun environmentClient(environmentKey: String, baseUrl: String? = null): AnthropicClient =
    AnthropicOkHttpClient.builder()
        .authToken(environmentKey)
        .apply { if (!baseUrl.isNullOrEmpty()) baseUrl(baseUrl) }
        .build()

/** All worker API surfaces over one environment-key client. */
fun anthropicApis(environmentKey: String, baseUrl: String? = null): ManagedAgentsApis =
    AnthropicManagedAgentsApi(environmentClient(environmentKey, baseUrl)).let { ManagedAgentsApis(it, it, it) }

/**
 * [WorkApi], [SessionEventsApi] and [SkillsApi] over the Anthropic Java SDK's
 * blocking client. The SDK adds the `managed-agents` beta header itself; skill
 * version calls add [SKILLS_BETA]. Blocking calls run on [io]; API errors
 * surface as [ApiException].
 */
class AnthropicManagedAgentsApi(
    private val client: AnthropicClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WorkApi, SessionEventsApi, SkillsApi {
    private val work get() = client.beta().environments().work()
    private val events get() = client.beta().sessions().events()
    private val skillVersions get() = client.beta().skills().versions()

    override suspend fun poll(
        environmentId: String,
        workerId: String,
        blockMs: Long?,
        reclaimOlderThanMs: Long?,
    ): WorkItem? = call {
        val params = WorkPollParams.builder().environmentId(environmentId).anthropicWorkerId(workerId)
        if (blockMs != null) params.blockMs(blockMs)
        if (reclaimOlderThanMs != null) params.reclaimOlderThanMs(reclaimOlderThanMs)
        val response = work.withRawResponse().poll(params.build())
        // An empty queue is a JSON `null` (parsed to an empty Optional) or a bodiless 204.
        if (response.statusCode() == 204) {
            response.close()
            return@call null
        }
        response.parse().orElse(null)?.let {
            WorkItem(
                id = it.id(),
                environmentId = it.environmentId(),
                type = it.data()._type().asString().orElse(""),
                sessionId = it.data().id(),
            )
        }
    }

    override suspend fun ack(workId: String, environmentId: String) {
        call { work.ack(WorkAckParams.builder().workId(workId).environmentId(environmentId).build()) }
    }

    override suspend fun heartbeat(workId: String, environmentId: String, expectedLastHeartbeat: String): Heartbeat =
        call {
            val params = WorkHeartbeatParams.builder()
                .workId(workId)
                .environmentId(environmentId)
                .expectedLastHeartbeat(expectedLastHeartbeat)
                .build()
            val response = work.heartbeat(params)
            Heartbeat(
                lastHeartbeat = response.lastHeartbeat(),
                ttlSeconds = response.ttlSeconds(),
                state = response.state().asString(),
                leaseExtended = response.leaseExtended(),
            )
        }

    override suspend fun stop(workId: String, environmentId: String, force: Boolean) {
        call {
            val body = BetaSelfHostedWorkStopRequest.builder().apply { if (force) force(true) }.build()
            work.stop(
                WorkStopParams.builder()
                    .workId(workId)
                    .environmentId(environmentId)
                    .betaSelfHostedWorkStopRequest(body)
                    .build(),
            )
        }
    }

    override suspend fun stream(sessionId: String): EventStream = SdkEventStream(call { events.streamStreaming(sessionId) })

    override suspend fun history(sessionId: String): List<SessionEvent> = call {
        events.list(EventListParams.builder().sessionId(sessionId).limit(1000).build())
            .autoPager()
            .map(::toSessionEvent)
    }

    override suspend fun send(sessionId: String, result: ToolResult) {
        call {
            val params = EventSendParams.builder().sessionId(sessionId)
            if (result.custom) {
                params.addEvent(
                    BetaManagedAgentsUserCustomToolResultEventParams.builder()
                        .customToolUseId(result.toolUseId)
                        .isError(result.isError)
                        .addTextContent(result.text)
                        .build(),
                )
            } else {
                params.addEvent(
                    BetaManagedAgentsUserToolResultEventParams.builder()
                        .toolUseId(result.toolUseId)
                        .isError(result.isError)
                        .addTextContent(result.text)
                        .build(),
                )
            }
            events.send(params.build())
        }
    }

    override suspend fun sessionSkills(sessionId: String): List<SkillRef> = call {
        client.beta().sessions().retrieve(sessionId).agent().skills().map { skill ->
            val node = jsonMapper().valueToTree<JsonNode>(skill)
            SkillRef(node.path("skill_id").asText(""), node.get("version")?.takeIf { it.isValueNode && !it.isNull }?.asText())
        }
    }

    override suspend fun versions(skillId: String): List<String> = call {
        skillVersions.list(VersionListParams.builder().skillId(skillId).addBeta(SKILLS_BETA).build()).autoPager().map { version ->
            // The concrete version is the `version` field; the SDK model may not
            // expose it as an accessor, so read it from the raw JSON.
            jsonMapper().valueToTree<JsonNode>(version).get("version")
                ?.takeIf { it.isValueNode && !it.isNull }
                ?.asText()
                ?: version.id()
        }
    }

    override suspend fun versionName(skillId: String, version: String): String = call {
        skillVersions.retrieve(
            VersionRetrieveParams.builder().skillId(skillId).version(version).addBeta(SKILLS_BETA).build(),
        ).name()
    }

    override suspend fun download(skillId: String, version: String, dest: Path) {
        call {
            val params = VersionDownloadParams.builder().skillId(skillId).version(version).addBeta(SKILLS_BETA).build()
            skillVersions.download(params).use { response ->
                response.body().use { body -> Files.copy(body, dest, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    private suspend fun <T> call(block: () -> T): T = withContext(io) { translateErrors(block) }

    private inner class SdkEventStream(
        private val response: StreamResponse<BetaManagedAgentsStreamSessionEvents>,
    ) : EventStream {
        private var iterator: Iterator<BetaManagedAgentsStreamSessionEvents>? = null

        override suspend fun next(): SessionEvent? = withCancellationHook({ response.close() }) {
            runInterruptible(io) {
                translateErrors {
                    val events = iterator ?: response.iterator().also { iterator = it }
                    if (events.hasNext()) toSessionEvent(events.next()) else null
                }
            }
        }

        override fun close() = response.close()
    }
}

private inline fun <T> translateErrors(block: () -> T): T = try {
    block()
} catch (e: AnthropicServiceException) {
    throw ApiException(e.statusCode(), e.message, e)
}

private fun toSessionEvent(event: Any): SessionEvent = SessionEvent.fromJson(jsonMapper().valueToTree<JsonNode>(event))
