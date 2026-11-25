package com.example.claudemicrovm.worker

import com.example.claudemicrovm.worker.anthropic.ManagedAgentsApis
import com.example.claudemicrovm.worker.anthropic.anthropicApis
import com.example.claudemicrovm.worker.aws.fetchEnvironmentKey
import com.example.claudemicrovm.worker.session.EnvironmentWorker
import com.example.claudemicrovm.worker.session.WorkPoller
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Where the agent's tools operate inside the MicroVM. */
const val WORKDIR = "/workspace"

/** Reclaim work items whose previous claimant stopped heartbeating more than 2s ago. */
private const val RECLAIM_OLDER_THAN_MS = 2_000L

private val log = LoggerFactory.getLogger(SessionHandler::class.java)

/**
 * Handles exactly the session named in a dispatch: fetches the environment key
 * from SSM Parameter Store, claims the session's item from the work queue, and
 * serves its tool calls until the session ends.
 */
class SessionHandler(
    private val environmentKey: suspend (parameterName: String, region: String?) -> String = ::fetchEnvironmentKey,
    private val apis: (environmentKey: String, baseUrl: String?) -> ManagedAgentsApis = ::anthropicApis,
    private val workdir: Path = Path.of(WORKDIR),
) {
    suspend fun handle(dispatch: Dispatch) {
        val sessionId = dispatch.sessionId
        val environmentId = requireNotNull(dispatch.environmentId) { "dispatch is missing ANTHROPIC_ENVIRONMENT_ID" }
        val parameterName = requireNotNull(dispatch.environmentKeyParamName) {
            "dispatch is missing ENVIRONMENT_KEY_PARAM_NAME"
        }

        val api = apis(environmentKey(parameterName, dispatch.region), dispatch.baseUrl)
        val worker = EnvironmentWorker(api.work, api.events, api.skills, workdir)

        log.info("worker: looking for work item for session {}", sessionId)
        val poller = WorkPoller(
            api.work,
            environmentId,
            reclaimOlderThanMs = RECLAIM_OLDER_THAN_MS,
            drain = true,
            // The worker force-stops the item itself once the session is served.
            autoStop = false,
        )
        var handled = false
        poller.works()
            .filter { it.type == "session" && it.sessionId == sessionId }
            .take(1)
            .collect { work ->
                log.info("worker: handling session {} (work {})", sessionId, work.id)
                worker.handleItem(work)
                log.info("worker: session {} complete", sessionId)
                handled = true
            }
        if (!handled) log.warn("worker: no work item found for session {}", sessionId)
    }
}
