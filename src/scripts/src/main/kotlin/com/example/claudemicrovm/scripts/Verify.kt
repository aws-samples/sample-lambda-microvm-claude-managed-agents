// Operator-side verification for the Claude MicroVM Sandbox (webhook model).
//
// Run this from OUTSIDE the control plane using your organization API key. It
// creates a session targeting the self-hosted environment, which (once the
// session reaches the running state) causes Anthropic to deliver a
// `session.status_run_started` webhook to your API Gateway endpoint. The launcher
// Lambda verifies the signature in-process and starts one MicroVM.
//
// You then confirm a MicroVM reached the RUNNING state with the AWS CLI:
//
//     aws lambda-microvms list-microvms --image-identifier <image>
//     aws lambda-microvms get-microvm --microvm-identifier <id>
//
// Credentials (operator scope only — never on the control plane):
//
//   ANTHROPIC_API_KEY          Organization-scoped key, used to create sessions.
//   ANTHROPIC_ENVIRONMENT_ID   The self-hosted environment id.
//   AGENT_ID                   The agent to run in the session.
//
// Usage:
//   ./gradlew :scripts:verify --args="--create"   # create a session to exercise the webhook flow
package com.example.claudemicrovm.scripts

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.sessions.SessionCreateParams
import kotlin.system.exitProcess

private const val USAGE = """usage: verify [-h] [--create]

Verify the Claude MicroVM Sandbox (webhook).

options:
  -h, --help  show this help message and exit
  --create    Create a session to exercise the flow."""

fun main(args: Array<String>) {
    var create = false
    for (arg in args) {
        when (arg) {
            "--create" -> create = true
            "-h", "--help" -> {
                println(USAGE)
                return
            }
            else -> {
                System.err.println("$USAGE\n\nverify: error: unrecognized arguments: $arg")
                exitProcess(2)
            }
        }
    }
    val env = System.getenv()

    val environmentId = env.nonEmpty("ANTHROPIC_ENVIRONMENT_ID") ?: fail("ANTHROPIC_ENVIRONMENT_ID must be set.")

    if (!create) {
        println("Nothing to do. Pass --create to create a session and exercise the webhook flow.")
        return
    }

    val agentId = env.nonEmpty("AGENT_ID") ?: fail("AGENT_ID must be set to create a session.")
    val apiKey = env.nonEmpty("ANTHROPIC_API_KEY")
        ?: fail("ANTHROPIC_API_KEY (organization-scoped) must be set for the operator.")

    // Authenticated with the ORG API key; the SDK sets the managed-agents beta header itself.
    val client = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { env.nonEmpty("ANTHROPIC_BASE_URL")?.let { baseUrl(it) } }
        .build()
    try {
        createSession(client, environmentId, agentId)
    } finally {
        client.close()
    }
}

/**
 * Create a session targeting the self-hosted environment. When it reaches the
 * running state, Anthropic delivers a `session.status_run_started` webhook to the
 * API Gateway endpoint, which leads to a MicroVM launch.
 */
fun createSession(client: AnthropicClient, environmentId: String, agentId: String): String {
    val session = client.beta().sessions().create(
        SessionCreateParams.builder().agent(agentId).environmentId(environmentId).build(),
    )
    println("created session id=${session.id()}")
    println(
        "If the webhook endpoint is registered, a session.status_run_started " +
            "event will trigger a MicroVM launch. Confirm with:\n" +
            "  aws lambda-microvms list-microvms --image-identifier <image>\n" +
            "  aws lambda-microvms get-microvm --microvm-identifier <id>",
    )
    return session.id()
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
