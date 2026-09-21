import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(environmentName: String, localPropertyName: String): String? =
    providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localPropertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue(
    environmentName = "PAGEHARBOR_RELEASE_STORE_FILE",
    localPropertyName = "pageharbor.release.storeFile",
)
val releaseStorePassword = releaseSigningValue(
    environmentName = "PAGEHARBOR_RELEASE_STORE_PASSWORD",
    localPropertyName = "pageharbor.release.storePassword",
)
val releaseKeyAlias = releaseSigningValue(
    environmentName = "PAGEHARBOR_RELEASE_KEY_ALIAS",
    localPropertyName = "pageharbor.release.keyAlias",
)
val releaseKeyPassword = releaseSigningValue(
    environmentName = "PAGEHARBOR_RELEASE_KEY_PASSWORD",
    localPropertyName = "pageharbor.release.keyPassword",
)
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

fun runGitCommand(vararg args: String): String? {
    if (!rootProject.layout.projectDirectory.file(".git").asFile.exists()) return null

    return runCatching {
        val process = ProcessBuilder("git", *args)
            .directory(rootProject.layout.projectDirectory.asFile)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0 && output.isNotBlank()) output else null
    }.getOrNull()
}

fun gitRevisionForDebugBuild(): String {
    val revision = runGitCommand("rev-parse", "--short=7", "HEAD") ?: return "unknown"
    val hasChanges = runGitCommand("status", "--porcelain").orEmpty().isNotBlank()
    return if (hasChanges) "$revision-dirty" else revision
}

android {
    namespace = "org.synapseworks.pageharbor"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.synapseworks.pageharbor"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_REVISION", "\"unknown\"")
        buildConfigField("String", "BUILD_TYPE_LABEL", "\"release\"")
        buildConfigField("boolean", "SHOW_BUILD_DETAILS", "false")
    }

    signingConfigs {
        create("playUpload") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "GIT_REVISION", "\"${gitRevisionForDebugBuild()}\"")
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"debug\"")
            buildConfigField("boolean", "SHOW_BUILD_DETAILS", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("playUpload")
            }
        }

        create("releaseVerification") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "GIT_REVISION", "\"${gitRevisionForDebugBuild()}\"")
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"releaseVerification\"")
            buildConfigField("boolean", "SHOW_BUILD_DETAILS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.register("verifyReleaseSigning") {
    group = "distribution"
    description = "Verifies that production Play upload signing credentials are configured."
    doLast {
        check(releaseSigningConfigured) {
            "Production release signing requires PAGEHARBOR_RELEASE_STORE_FILE, " +
                "PAGEHARBOR_RELEASE_STORE_PASSWORD, PAGEHARBOR_RELEASE_KEY_ALIAS, and " +
                "PAGEHARBOR_RELEASE_KEY_PASSWORD, or their documented local.properties equivalents. " +
                "Use bundleRelease or bundleReleaseVerification for unsigned/debug-signed local verification."
        }
    }
}

tasks.register("bundleReleaseForPlay") {
    group = "distribution"
    description = "Builds a production-upload-signed release bundle after credential verification."
    dependsOn("verifyReleaseSigning", "bundleRelease")
}

tasks.register("assembleReleaseForPlay") {
    group = "distribution"
    description = "Builds a production-upload-signed release APK after credential verification."
    dependsOn("verifyReleaseSigning", "assembleRelease")
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    mustRunAfter("verifyReleaseSigning")
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    mustRunAfter("verifyReleaseSigning")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.play.services.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition.latin)
    // Required for local invisible Unicode text layers; no networking or native binaries.
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.play.review)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
