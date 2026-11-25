// Operator tooling, run from the repo root:
//
//   ./gradlew :scripts:buildImage [--args="<stack-name>"]
//       Package the worker image, upload it to the stack's artifact bucket and
//       create the MicroVM image (see BuildImage.kt for environment overrides).
//
//   ./gradlew :scripts:verify --args="--create"
//       Create a session to exercise the webhook flow (operator credentials only).
plugins {
    alias(libs.plugins.kotlin.jvm)
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

val microvmImage = configurations.dependencyScope("microvmImage")
val microvmImageFiles = configurations.resolvable("microvmImageFiles") {
    extendsFrom(microvmImage.get())
    attributes { attribute(Category.CATEGORY_ATTRIBUTE, objects.named("microvm-image")) }
}

dependencies {
    implementation(libs.anthropic.java)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.cloudformation)
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.lambdamicrovms)
    implementation(libs.aws.sdk.url.connection.client)
    runtimeOnly(libs.slf4j.simple)

    add(microvmImage.name, project(":worker"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("buildImage") {
    group = "microvm"
    description = "Packages the worker image, uploads it to the stack's artifact bucket and creates the MicroVM image."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.example.claudemicrovm.scripts.BuildImageKt"
    val imageZip = microvmImageFiles.map { it.singleFile }
    inputs.files(microvmImageFiles)
    doFirst { systemProperty("microvm.image.zip", imageZip.get().absolutePath) }
}

tasks.register<JavaExec>("verify") {
    group = "microvm"
    description = "Creates a session targeting the self-hosted environment (pass --args=\"--create\")."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.example.claudemicrovm.scripts.VerifyKt"
}
