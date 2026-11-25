package com.example.claudemicrovm.scripts

import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse
import software.amazon.awssdk.services.cloudformation.model.Output
import software.amazon.awssdk.services.cloudformation.model.Stack
import software.amazon.awssdk.services.lambdamicrovms.model.HookState
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildImageTest {
    @Test
    fun `the image request enables every lifecycle hook the worker serves`() {
        val request = createImageRequest("claude-self-hosted-worker", "s3://bucket/deployments/app.zip", "arn:base", "arn:role")

        assertEquals("claude-self-hosted-worker", request.name())
        assertEquals("s3://bucket/deployments/app.zip", request.codeArtifact().uri())
        assertEquals("arn:base", request.baseImageArn())
        assertEquals("arn:role", request.buildRoleArn())
        val hooks = request.hooks()
        assertEquals(9000, hooks.port())
        with(hooks.microvmImageHooks()) {
            assertEquals(listOf(HookState.ENABLED, HookState.ENABLED), listOf(ready(), validate()))
            assertEquals(listOf(300, 300), listOf(readyTimeoutInSeconds(), validateTimeoutInSeconds()))
        }
        with(hooks.microvmHooks()) {
            assertEquals(List(4) { HookState.ENABLED }, listOf(run(), resume(), suspend(), terminate()))
            assertEquals(
                List(4) { 5 },
                listOf(runTimeoutInSeconds(), resumeTimeoutInSeconds(), suspendTimeoutInSeconds(), terminateTimeoutInSeconds()),
            )
        }
    }

    @Test
    fun `reads the stack outputs by key`() {
        var requested: String? = null
        val cloudFormation = object : CloudFormationClient {
            override fun serviceName(): String = CloudFormationClient.SERVICE_NAME

            override fun close() = Unit

            override fun describeStacks(request: DescribeStacksRequest): DescribeStacksResponse {
                requested = request.stackName()
                return DescribeStacksResponse.builder()
                    .stacks(
                        Stack.builder()
                            .outputs(
                                Output.builder().outputKey("ArtifactBucketName").outputValue("bucket").build(),
                                Output.builder().outputKey("BuildRoleArn").outputValue("arn:role").build(),
                            )
                            .build(),
                    )
                    .build()
            }
        }

        assertEquals(mapOf("ArtifactBucketName" to "bucket", "BuildRoleArn" to "arn:role"), stackOutputs(cloudFormation, "my-stack"))
        assertEquals("my-stack", requested)
    }
}
