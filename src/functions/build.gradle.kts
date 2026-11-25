// Launcher Lambda: verifies the Anthropic webhook and launches one MicroVM per
// started session.
//
// Packaged by `sam build` (java-gradle workflow, CodeUri: src/functions/): SAM
// runs `gradlew build` in this directory and ships the compiled classes and
// resources plus the runtime classpath as `lib/*.jar`.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

configurations.all {
    // AWS calls use the lightweight URL-connection HTTP client (faster cold start).
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

dependencies {
    implementation(libs.aws.lambda.java.core)
    implementation(libs.aws.lambda.java.events)
    // Webhook signature verification (client.beta().webhooks().unwrap).
    implementation(libs.anthropic.java)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.lambdamicrovms)
    implementation(libs.aws.sdk.ssm)
    implementation(libs.aws.sdk.url.connection.client)
    // Durable webhook dedupe, cached SecureString reads and structured JSON logs.
    implementation(libs.powertools.idempotency.dynamodb)
    implementation(libs.powertools.parameters.ssm)
    implementation(libs.powertools.logging.log4j)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
