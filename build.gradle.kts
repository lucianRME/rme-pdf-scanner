// Retain the existing Kotlin/Compose compiler version with AGP built-in Kotlin.
// AGP 9.0 otherwise supplies Kotlin 2.2.10.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
