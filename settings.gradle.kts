plugins {
    // Provisions the JDK 21 toolchain the modules compile against when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "claude-microvm-sandbox"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Launcher Lambda (packaged by `sam build` from CodeUri: src/functions/).
include(":launcher")
project(":launcher").projectDir = file("src/functions")

// In-MicroVM worker (lifecycle-hook server + session tool runner).
include(":worker")
project(":worker").projectDir = file("src/microvm-image/worker")

// Operator tooling: MicroVM image build and end-to-end verification.
include(":scripts")
project(":scripts").projectDir = file("src/scripts")
