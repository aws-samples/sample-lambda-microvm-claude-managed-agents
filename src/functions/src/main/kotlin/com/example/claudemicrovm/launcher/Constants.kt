package com.example.claudemicrovm.launcher

/** AWS-managed ALL_INGRESS network connector ARN for [region]. */
fun allIngressArn(region: String): String =
    "arn:aws:lambda:$region:aws:network-connector:aws-network-connector:ALL_INGRESS"

/** AWS-managed INTERNET_EGRESS network connector ARN for [region]. */
fun internetEgressArn(region: String): String =
    "arn:aws:lambda:$region:aws:network-connector:aws-network-connector:INTERNET_EGRESS"

/** 8 hours: the MicroVM maximum duration. */
const val DEFAULT_MAX_LIFETIME_SECONDS = 28_800

/** The RunMicrovm API rate limit. */
const val DEFAULT_LAUNCH_TPS_LIMIT = 5

const val RUN_HOOK_PAYLOAD_VERSION = "1"

val DEFAULT_IDLE_POLICY = IdlePolicySpec(
    maxIdleDurationSeconds = 300,
    suspendedDurationSeconds = 60,
    autoResumeEnabled = false,
)

const val DEFAULT_LOG_GROUP = "/aws/lambda/microvms/claude-self-hosted-worker"

const val SESSION_RUN_STARTED = "session.status_run_started"
