// Build the Claude self-hosted worker MicroVM image.
//
// Steps:
//   1. Packages the image source as app.zip (Dockerfile at the root, worker jars
//      under worker/lib/) — done by Gradle (`:worker:imageZip`) before this runs.
//   2. Uploads it to the stack's S3 artifact bucket.
//   3. Creates the MicroVM image with lifecycle hooks enabled.
//
// Usage:
//   ./gradlew :scripts:buildImage [--args="<stack-name>"]
//
// Environment overrides:
//   IMAGE_NAME     MicroVM image name        (default: claude-self-hosted-worker)
//   BASE_IMAGE_ARN Managed base image ARN    (default: auto-discovered)
//   S3_KEY         Artifact key in bucket    (default: deployments/app-<timestamp>.zip)
//   AWS_REGION     Target region             (default: from the AWS config / profile)
package com.example.claudemicrovm.scripts

import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient
import software.amazon.awssdk.services.lambdamicrovms.model.CodeArtifact
import software.amazon.awssdk.services.lambdamicrovms.model.CreateMicrovmImageRequest
import software.amazon.awssdk.services.lambdamicrovms.model.HookState
import software.amazon.awssdk.services.lambdamicrovms.model.Hooks
import software.amazon.awssdk.services.lambdamicrovms.model.MicrovmHooks
import software.amazon.awssdk.services.lambdamicrovms.model.MicrovmImageHooks
import software.amazon.awssdk.services.s3.S3Client
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

const val DEFAULT_STACK_NAME = "claude-microvm-sandbox"
const val DEFAULT_IMAGE_NAME = "claude-self-hosted-worker"

/** Port the worker serves its lifecycle hooks on. */
private const val HOOK_PORT = 9000

/** Image build: time for the worker to start (ready) and pass the post-snapshot smoke test (validate). */
private const val IMAGE_HOOK_TIMEOUT_SECONDS = 300

/** Run/resume/suspend/terminate hooks: the worker acknowledges immediately. */
private const val MICROVM_HOOK_TIMEOUT_SECONDS = 5

/** An operator-facing failure: printed to stderr, exit status 1. */
class ScriptFailure(message: String) : Exception(message)

fun main(args: Array<String>) {
    try {
        buildImage(args.firstOrNull() ?: DEFAULT_STACK_NAME, System.getenv())
    } catch (e: ScriptFailure) {
        System.err.println(e.message)
        exitProcess(1)
    }
}

private fun buildImage(stackName: String, env: Map<String, String>) {
    val imageName = env.nonEmpty("IMAGE_NAME") ?: DEFAULT_IMAGE_NAME
    val region = env.nonEmpty("AWS_REGION")?.let(Region::of)
    val imageZip = System.getProperty("microvm.image.zip")?.let { Path.of(it) }
        ?.takeIf { Files.isRegularFile(it) }
        ?: throw ScriptFailure("The image archive is missing; run this through `./gradlew :scripts:buildImage`.")

    println("Resolving artifact bucket and build role from stack '$stackName'...")
    val outputs = CloudFormationClient.builder().configure(region).build().use { stackOutputs(it, stackName) }
    val bucket = outputs["ArtifactBucketName"]
        ?: throw ScriptFailure("Could not resolve ArtifactBucketName from stack '$stackName'.")
    val buildRoleArn = outputs["BuildRoleArn"]
        ?: throw ScriptFailure("Could not resolve BuildRoleArn from stack '$stackName'.")

    LambdaMicrovmsClient.builder().configure(region).build().use { microvms ->
        val baseImageArn = env.nonEmpty("BASE_IMAGE_ARN") ?: run {
            println("Discovering a managed base image via list-managed-microvm-images...")
            microvms.listManagedMicrovmImages { }.items().firstOrNull()?.imageArn()
                ?: throw ScriptFailure("Could not discover a managed base image. Set BASE_IMAGE_ARN explicitly.")
        }
        println("Using base image: $baseImageArn")

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val key = env.nonEmpty("S3_KEY") ?: "deployments/app-$timestamp.zip"
        println("Uploading $imageZip to s3://$bucket/$key...")
        S3Client.builder().configure(region).build().use { s3 ->
            s3.putObject({ it.bucket(bucket).key(key) }, RequestBody.fromFile(imageZip))
        }

        println("Creating MicroVM image '$imageName'...")
        microvms.createMicrovmImage(createImageRequest(imageName, "s3://$bucket/$key", baseImageArn, buildRoleArn))
    }

    println()
    println("Image build started. Monitor build logs in CloudWatch:")
    println("  /aws/lambda/microvms/$imageName")
    println("The image transitions CREATING -> CREATED on success.")
}

/** The stack's outputs by key. */
fun stackOutputs(cloudFormation: CloudFormationClient, stackName: String): Map<String, String> =
    cloudFormation.describeStacks { it.stackName(stackName) }
        .stacks()
        .firstOrNull()
        ?.outputs()
        .orEmpty()
        .filter { it.outputValue() != null }
        .associate { it.outputKey() to it.outputValue() }

/** The CreateMicrovmImage request, with every lifecycle hook the worker serves enabled. */
fun createImageRequest(imageName: String, codeUri: String, baseImageArn: String, buildRoleArn: String): CreateMicrovmImageRequest =
    CreateMicrovmImageRequest.builder()
        .codeArtifact(CodeArtifact.fromUri(codeUri))
        .name(imageName)
        .baseImageArn(baseImageArn)
        .buildRoleArn(buildRoleArn)
        .hooks(
            Hooks.builder()
                .port(HOOK_PORT)
                .microvmImageHooks(
                    MicrovmImageHooks.builder()
                        .ready(HookState.ENABLED)
                        .readyTimeoutInSeconds(IMAGE_HOOK_TIMEOUT_SECONDS)
                        .validate(HookState.ENABLED)
                        .validateTimeoutInSeconds(IMAGE_HOOK_TIMEOUT_SECONDS)
                        .build(),
                )
                .microvmHooks(
                    MicrovmHooks.builder()
                        .run(HookState.ENABLED)
                        .runTimeoutInSeconds(MICROVM_HOOK_TIMEOUT_SECONDS)
                        .resume(HookState.ENABLED)
                        .resumeTimeoutInSeconds(MICROVM_HOOK_TIMEOUT_SECONDS)
                        .suspend(HookState.ENABLED)
                        .suspendTimeoutInSeconds(MICROVM_HOOK_TIMEOUT_SECONDS)
                        .terminate(HookState.ENABLED)
                        .terminateTimeoutInSeconds(MICROVM_HOOK_TIMEOUT_SECONDS)
                        .build(),
                )
                .build(),
        )
        .build()

/** Region override (else the SDK's default chain: env, profile, ...) and the URL-connection HTTP client. */
private fun <B, C> B.configure(region: Region?): B where B : AwsClientBuilder<B, C>, B : AwsSyncClientBuilder<B, C> =
    apply {
        httpClientBuilder(UrlConnectionHttpClient.builder())
        if (region != null) region(region)
    }

internal fun Map<String, String>.nonEmpty(name: String): String? = this[name]?.takeIf { it.isNotEmpty() }
