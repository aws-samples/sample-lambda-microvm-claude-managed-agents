// In-MicroVM worker: lifecycle-hook HTTP server + Anthropic session tool runner.
//
// `./gradlew :worker:imageZip` packages the MicroVM image source as
// build/image/app.zip: the Dockerfile at the archive root (the image build
// requires it there) and the worker's runtime jars under worker/lib/, so the
// image itself only needs a JRE. `./gradlew :scripts:buildImage` builds it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

configurations.all {
    // AWS calls use the lightweight URL-connection HTTP client.
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.anthropic.java)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.ssm)
    implementation(libs.aws.sdk.lambdamicrovms)
    implementation(libs.aws.sdk.url.connection.client)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "com.example.claudemicrovm.worker.MainKt"
    applicationName = "worker"
}

tasks.test {
    useJUnitPlatform()
}

val imageZip = tasks.register<Zip>("imageZip") {
    group = "microvm"
    description = "Packages the MicroVM image source (Dockerfile + worker jars) as app.zip."
    archiveFileName = "app.zip"
    destinationDirectory = layout.buildDirectory.dir("image")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(layout.projectDirectory.file("../Dockerfile"))
    into("worker/lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
}

// Shares app.zip with the operator tooling (`:scripts:buildImage`).
val microvmImage = configurations.consumable("microvmImage") {
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("microvm-image")) }
}
artifacts {
    add(microvmImage.name, imageZip)
}
