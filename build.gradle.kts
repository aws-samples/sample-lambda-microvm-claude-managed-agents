// Root build: shared plugin versions only. Each module lives under src/ (see
// settings.gradle.kts) and applies the Kotlin JVM plugin itself.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
