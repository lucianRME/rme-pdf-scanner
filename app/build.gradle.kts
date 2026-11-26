import java.util.Properties
import java.security.MessageDigest
import java.nio.file.Files

val productionApplicationId = "org.synapseworks.pageharbor"
val phase2TestApplicationId = "$productionApplicationId.phase2test"

// Local, explicitly provisioned qualified M35 bundle. No download or fallback.
val officeBundle = providers.gradleProperty("rmeOfficeBundleRoot").orNull?.let { file(it) }
fun officeSha(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(65536)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
if (officeBundle != null) {
    val lock = groovy.json.JsonSlurper().parse(officeBundle.resolve("bundle.json")) as Map<*, *>
    val qualified = groovy.json.JsonSlurper().parse(rootProject.file("docs/milestones/evidence/m35-product15-qualification.json")) as Map<*, *>
    require(lock["qualificationSha256"] == officeSha(rootProject.file("docs/milestones/evidence/m35-product15-qualification.json")))
    require(lock["nativeSha256"] == qualified["nativeSha256"])
    val packages = qualified["packages"] as Map<*, *>
    require(lock["sourceWorkerSha256"] == (packages["ordinary"] as Map<*, *>)["worker"])
    val payload = groovy.json.JsonSlurper().parse(rootProject.file("tools/m35/product/runtime-payload-lock.json")) as Map<*, *>
    require(lock["files"] == payload["files"] && lock["sourceWorkerSha256"] == payload["sourceWorkerSha256"])
    val expectedFiles = (lock["files"] as List<*>).map { (it as Map<*, *>)["path"] as String }.toSet() + "bundle.json"
    require(officeBundle.walkTopDown().filter { it.isFile }.map { it.relativeTo(officeBundle).invariantSeparatorsPath }.toSet() == expectedFiles)
    (lock["files"] as List<*>).forEach { value ->
        val row = value as Map<*, *>; val name = row["path"] as String
        require(!name.startsWith('/') && !name.contains("..") && !name.contains('\\') && !name.contains("fixtures"))
        val source = officeBundle.resolve(name)
        require(source.isFile && !Files.isSymbolicLink(source.toPath()) && officeSha(source) == row["sha256"])
    }
}

// Optional exact EN→RO runtime; no resolution/download of upstream AARs.
val modelBundle = providers.gradleProperty("rmeModelRuntimeRoot").orNull?.let { file(it) }
val translationPlayDelivery = providers.gradleProperty("rmeTranslationPlayDelivery").orNull == "true"
require(!translationPlayDelivery || modelBundle != null) { "Translation delivery requires the pinned local translation runtime." }
if(modelBundle != null) {
    require(officeBundle == null) { "Combined Office/model native namespace is unqualified; provision only one runtime profile. No libc++ replacement is permitted." }
    val expected = rootProject.file("tools/m36/model-runtime-lock.json")
    require(officeSha(modelBundle.resolve("bundle.json")) == officeSha(expected))
    val rows=(groovy.json.JsonSlurper().parse(expected) as Map<*,*>)["files"] as List<*>
    val names=rows.map{(it as Map<*,*>)["path"] as String}.toSet()+"bundle.json"
    require(modelBundle.walkTopDown().filter{it.isFile}.map{it.relativeTo(modelBundle).invariantSeparatorsPath}.toSet()==names)
    rows.forEach{v->val row=v as Map<*,*>;val source=modelBundle.resolve(row["path"] as String);require(!Files.isSymbolicLink(source.toPath()) && officeSha(source)==row["sha256"])}
}

val officeSourceNotices = layout.buildDirectory.dir("generated/m35/notices")
val prepareOfficeSources = tasks.register("prepareM35OfficeSources") {
    val source = rootProject.file("tools/m35/prototype/worker")
    val lock = rootProject.file("tools/m35/product/runtime-sources-lock.json")
    val target = layout.buildDirectory.dir("generated/m35/java")
    inputs.dir(source); inputs.file(lock); outputs.dir(target); outputs.dir(officeSourceNotices)
    doLast {
        val rows = (groovy.json.JsonSlurper().parse(lock) as Map<*, *>)["files"] as List<*>
        rows.forEach { value ->
            val row = value as Map<*, *>; val name = row["path"] as String
            val input = source.resolve(name)
            require(officeSha(input) == row["sha256"]) { "Qualified M35 Java source changed" }
            val output = target.get().file(name).asFile
            output.parentFile.mkdirs()
            // Pin qualified source, adding explicit consumer preflight/metrics
            // hooks. The private adapter owns authorization and resource totals.
            val text = input.readText().let {
                if (input.name == "Coordinator.java") {
                    val marker = "Admission.validate(source,j.kind);guard(j);"
                    require(it.windowed(marker.length).count { part -> part == marker } == 1)
                    it.replace("public final class Coordinator extends Service", "public class Coordinator extends Service")
                        .replace(marker, "OfficePreflight.validate(source,()->guard(j));" + marker)
                        .replace("finish(j,ex.code);", "finish(j,j.cancelled.get()?j.cancellation:ex.code);")
                        .replace("Os.fcntlInt(j.input.getFileDescriptor(),OsConstants.F_GETFL,0)", "OfficeFd.flags(j.input)")
                        .replace("Os.fcntlInt(j.output.getFileDescriptor(),OsConstants.F_GETFL,0)", "OfficeFd.flags(j.output)")
                } else if (input.name == "NativeService.java") {
                    it.replace("android.system.Os.fcntlInt(caps.get(0).getFileDescriptor(),android.system.OsConstants.F_GETFL,0)", "OfficeFd.flags(caps.get(0))")
                } else if (input.name == "IsolatedJob.java") {
                    val marker = "job.pssPeak=Math.max(job.pssPeak,pss);"
                    require(it.windowed(marker.length).count { part -> part == marker } == 1)
                    it.replace(marker, marker + "if(host instanceof ProductionCoordinator)((ProductionCoordinator)host).nativePeak(job,pss);")
                } else it
            }
            output.writeText(text)
        }
        // Distribute the exact MPL-covered source compiled into the bundle.
        val bridgeSource = target.get().file("org/libreoffice/kit/LibreOfficeKit.java").asFile
        bridgeSource.copyTo(
            officeSourceNotices.get().file("licenses/LibreOfficeKit.java").asFile,
            overwrite = true,
        )
    }
}

plugins {
    alias(libs.plugins.android.application)
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
val productionSigningOptedIn = providers.gradleProperty("pageharbor.enableProductionSigning")
    .orNull
    ?.toBooleanStrictOrNull() == true
val requestedTaskNames = gradle.startParameter.taskNames
    .map { taskName -> taskName.substringAfterLast(':') }
    .toSet()
val productionPlayTaskRequested = requestedTaskNames.any { taskName ->
    taskName == "bundleReleaseForPlay" || taskName == "assembleReleaseForPlay"
}
val productionSigningAuthorized = productionSigningOptedIn && productionPlayTaskRequested

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
    testBuildType = when {
        providers.gradleProperty("rmeM35OptimizedTests").orNull == "true" -> "m04R8Smoke"
        providers.gradleProperty("rmeM00Benchmark").orNull == "true" -> "m00Benchmark"
        else -> "phase2Test"
    }

    defaultConfig {
        applicationId = productionApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "1.8.0"

        // Gradle generates the test manifest's instrumentation entry. Explicit
        // SDK runner builds are synthetic tests only; the default stays AndroidX.
        testInstrumentationRunner = if (providers.gradleProperty("rmeM35SdkTests").orNull == "true")
            "org.synapseworks.pageharbor.validation.M35ConsumerSmokeInstrumentation"
        else "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_REVISION", "\"unknown\"")
        buildConfigField("String", "BUILD_TYPE_LABEL", "\"release\"")
        buildConfigField("boolean", "SHOW_BUILD_DETAILS", "false")
        buildConfigField("boolean", "M35_OFFICE_ENABLED", (officeBundle != null).toString())
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
        // Compressed DEX creates private decompressed mappings in each Office
        // process. File-backed DEX shares code pages under the same PSS ceiling.
        // The default unprovisioned build retains its existing packaging.
        if (officeBundle != null) dex.useLegacyPackaging = false
        jniLibs {
            useLegacyPackaging = true
            // Preserve qualified already-stripped bytes; unrelated native libraries
            // retain their existing packaging/strip behavior.
            if (officeBundle != null) {
                officeBundle.resolve("jniLibs/arm64-v8a").listFiles().orEmpty().forEach {
                    keepDebugSymbols.add("**/${it.name}")
                }
            }
        }
    }

    if(modelBundle != null) {
        sourceSets.getByName("main").java.srcDir("src/modelRuntime/java")
        sourceSets.getByName("main").assets.srcDir(modelBundle.resolve("assets"))
        sourceSets.getByName("main").jniLibs.srcDir(modelBundle.resolve("jniLibs"))
        packaging.jniLibs.keepDebugSymbols.add("**/libonnxruntime*.so")
        packaging.jniLibs.keepDebugSymbols.add("**/libdjl_tokenizer.so")
        packaging.jniLibs.keepDebugSymbols.add("**/libc++_shared.so")
    }
    if (translationPlayDelivery) {
        sourceSets.getByName("main").kotlin.directories.add("src/playPackDelivery/java")
        assetPacks += listOf(":translation_ro", ":translation_multi", ":translation_ga")
    }
    sourceSets.getByName("main") {
        java.directories.add(layout.buildDirectory.dir("generated/m35/java").get().asFile.absolutePath)
        assets.directories.add(officeSourceNotices.get().asFile.absolutePath)
        if (officeBundle != null) {
            assets.directories.add(officeBundle.resolve("assets").absolutePath)
            jniLibs.directories.add(officeBundle.resolve("jniLibs").absolutePath)
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "GIT_REVISION", "\"${gitRevisionForDebugBuild()}\"")
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"debug\"")
            buildConfigField("boolean", "SHOW_BUILD_DETAILS", "true")
        }

        create("phase2Test") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".phase2test"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "GIT_REVISION", "\"${gitRevisionForDebugBuild()}\"")
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"phase2Test\"")
            buildConfigField("boolean", "SHOW_BUILD_DETAILS", "true")
        }

        // Dedicated synthetic-data package: never replace the installed production or phase2 apps.
        create("m00Benchmark") {
            initWith(getByName("phase2Test"))
            applicationIdSuffix = ".m00benchmark"
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"m00Benchmark\"")
            matchingFallbacks += listOf("debug")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (productionSigningAuthorized && releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("playUpload")
            }
        }

        // Release optimization with a separate synthetic-data application identity.
        create("m04R8Smoke") {
            initWith(getByName("release"))
            applicationIdSuffix = ".m04r8smoke"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }

        // M36 lifecycle tests coexist with production and retained M35 candidates.
        create("m36PackSmoke") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".m36packsmoke"
            matchingFallbacks += listOf("debug")
        }
        create("m36PackR8") {
            initWith(getByName("release"))
            applicationIdSuffix = ".m36packr8"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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

    for (name in listOf("m36PackSmoke", "m36PackR8")) {
        sourceSets.getByName(name).kotlin.directories.add("src/m36Test/java")
        sourceSets.getByName(name).assets.srcDir("src/m36Test/assets")
        sourceSets.getByName(name).manifest.srcFile("src/m36Test/AndroidManifest.xml")
        providers.gradleProperty("rmeM36CmuFixtures").orNull?.let { sourceSets.getByName(name).assets.srcDir(it) }
    }

    sourceSets.getByName("m00Benchmark") {
        kotlin.directories.add("src/phase2Test/java")
        manifest.srcFile("src/phase2Test/AndroidManifest.xml")
    }
    // The full M35 optimized instrumentation suite references the existing M05
    // and migration fault providers. Reuse them only in this synthetic package.
    sourceSets.getByName("m04R8Smoke").kotlin.directories.add("src/phase2Test/java")
    // SDK-only Office driver also supports ordinary API37, where the external
    // Espresso hidden-input API is absent. Never included in production.
    for (variant in listOf("androidTest", "m04R8Smoke"))
        sourceSets.getByName(variant).kotlin.directories.add("src/m35ConsumerTest/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets {
        getByName("test").kotlin.directories.add("$projectDir/src/testSharedDocx/java")
        getByName("test").kotlin.directories.add("$projectDir/src/testSharedPptx/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/testSharedPptx/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/testSharedPptx/java")
        getByName("test").kotlin.directories.add("$projectDir/src/testSharedXlsx/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/testSharedXlsx/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/testSharedXlsx/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/testSharedDocx/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/pptxTest/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/pptxTest/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/docxTest/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/xlsxTest/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/xlsxTest/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/testSharedDocx/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/docxTest/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/workflowTest/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/workflowTest/java")
        getByName("phase2Test").manifest.srcFile("src/phase2Test/AndroidManifest.xml")
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        getByName("test").kotlin.directories.add("$projectDir/src/testShared/java")
        getByName("test").kotlin.directories.add("$projectDir/src/testSharedTable/java")
        getByName("androidTest").kotlin.directories.add("$projectDir/src/testSharedTable/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/testSharedTable/java")
        getByName("phase2Test").kotlin.directories.add("$projectDir/src/tableTest/java")
        getByName("m04R8Smoke").kotlin.directories.add("$projectDir/src/tableTest/java")
        getByName("androidTest").kotlin.directories.add("$projectDir/src/testShared/java")
        getByName("androidTest").assets.directories.add("$projectDir/src/test/resources/m11")
        getByName("androidTest").assets.directories.add("$projectDir/src/test/resources")
        // Independent synthetic QR fixtures belong only to the isolated optimized harness.
        getByName("m04R8Smoke").assets.directories.add("$projectDir/src/test/resources/m19")
        getByName("m04R8Smoke").assets.directories.add("$projectDir/src/test/resources/m20")
        getByName("m04R8Smoke").assets.directories.add("$projectDir/src/test/resources/m21")
        getByName("m04R8Smoke").assets.directories.add("$projectDir/src/test/resources/m23")
        getByName("m04R8Smoke").assets.directories.add("$projectDir/src/test/resources/m31")
    }

}

// Built-in Kotlin exposes this project-level extension with the new Android DSL.
kotlin {
    jvmToolchain(17)
}

val verifyPhase2TestIdentity = tasks.register("verifyPhase2TestIdentity") {
    group = "verification"
    description = "Refuses device testing unless the dedicated Phase 2 target ID is isolated."
    doLast {
        val configured = productionApplicationId +
            requireNotNull(android.buildTypes.getByName("phase2Test").applicationIdSuffix)
        check(configured == phase2TestApplicationId)
        check(configured != productionApplicationId)
        println("expected package: $phase2TestApplicationId")
        println("configured target applicationId: $configured")
    }
}

tasks.register("compileDebugAndroidTestKotlin") {
    group = "verification"
    description = "Compatibility gate that compiles only the isolated Phase 2 instrumentation tests."
    dependsOn(verifyPhase2TestIdentity, "compilePhase2TestAndroidTestKotlin")
}

tasks.configureEach {
    val isPhase2TestDeviceTask = name.contains("Phase2Test") &&
        (name.startsWith("connected") || name.startsWith("device") ||
            name.startsWith("install") || name.startsWith("uninstall"))
    val isM04DeviceTask = name.contains("M04R8Smoke") &&
        (name.startsWith("connected") || name.startsWith("device") ||
            name.startsWith("install") || name.startsWith("uninstall"))
    val isOtherDeviceMutationTask = !name.contains("Phase2Test") && !isM04DeviceTask &&
        (name.startsWith("connected") || name.startsWith("device") ||
            name.startsWith("install") || name.startsWith("uninstall"))

    if (isM04DeviceTask) {
        doFirst {
            val smoke = android.buildTypes.getByName("m04R8Smoke")
            check(productionApplicationId + smoke.applicationIdSuffix == "$productionApplicationId.m04r8smoke")
            check(smoke.isMinifyEnabled && smoke.isShrinkResources)
            check(smoke.signingConfig == android.signingConfigs.getByName("debug"))
        }
    }
    if (isPhase2TestDeviceTask) {
        dependsOn(verifyPhase2TestIdentity)
    }
    if (isOtherDeviceMutationTask) {
        doFirst {
            throw GradleException(
                "Phase 2 device tasks must target $phase2TestApplicationId; " +
                    "refusing task $name because it could mutate the production package.",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.register("verifyReleaseSigning") {
    group = "distribution"
    description = "Verifies that production Play upload signing credentials are configured."
    doLast {
        check(productionPlayTaskRequested && productionSigningOptedIn) {
            "Production signing is available only through bundleReleaseForPlay or " +
                "assembleReleaseForPlay with -Ppageharbor.enableProductionSigning=true."
        }
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
    if(modelBundle != null) implementation(files(modelBundle.resolve("libs/ort-core.jar"),modelBundle.resolve("libs/tokenizer-jni.jar")))
    if (translationPlayDelivery) implementation("com.google.android.play:asset-delivery:2.3.0")
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
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
    implementation(libs.play.services.base)
    implementation(libs.play.services.mlkit.text.recognition.chinese)
    implementation(libs.play.services.mlkit.text.recognition.devanagari)
    implementation(libs.play.services.mlkit.text.recognition.japanese)
    implementation(libs.play.services.mlkit.text.recognition.korean)
    // Required for local invisible Unicode text layers; no networking or native binaries.
    implementation(libs.pdfbox.android)
    // Document integrity verifies detached signatures using this provider family.
    implementation(libs.bouncycastle.pkix)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.play.review)
    implementation(libs.androidx.biometric)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("phase2TestImplementation", libs.androidx.compose.ui.tooling)
    add("phase2TestImplementation", libs.androidx.compose.ui.test.manifest)
}

// PDF/FontBox can include field text in parser diagnostics. Silence only those library calls
// in every packaged variant, including ordinary debug; pinned runtime dependencies stay intact.
abstract class QuietPdfBoxFactory : com.android.build.api.instrumentation.AsmClassVisitorFactory<com.android.build.api.instrumentation.InstrumentationParameters.None> {
    override fun isInstrumentable(classData:com.android.build.api.instrumentation.ClassData)=classData.className.startsWith("com.tom_roush.pdfbox.")||classData.className.startsWith("com.tom_roush.fontbox.")
    override fun createClassVisitor(classContext:com.android.build.api.instrumentation.ClassContext,nextClassVisitor:org.objectweb.asm.ClassVisitor):org.objectweb.asm.ClassVisitor =
        object:org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9,nextClassVisitor){
            override fun visitMethod(access:Int,name:String,descriptor:String,signature:String?,exceptions:Array<out String>?):org.objectweb.asm.MethodVisitor {
                return object:org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9,super.visitMethod(access,name,descriptor,signature,exceptions)){
                    override fun visitMethodInsn(opcode:Int,owner:String,name:String,descriptor:String,isInterface:Boolean){
                        val log=owner=="android/util/Log"&&name in setOf("d","e","i","v","w","wtf","println")
                        val trace=name=="printStackTrace"&&opcode!=org.objectweb.asm.Opcodes.INVOKESTATIC&&descriptor in setOf("()V","(Ljava/io/PrintStream;)V","(Ljava/io/PrintWriter;)V")
                        val print=owner=="java/io/PrintStream"&&name in setOf("print","println")
                        if(log||trace||print){
                            org.objectweb.asm.Type.getArgumentTypes(descriptor).reversed().forEach{visitInsn(if(it.size==2)org.objectweb.asm.Opcodes.POP2 else org.objectweb.asm.Opcodes.POP)}
                            if(opcode!=org.objectweb.asm.Opcodes.INVOKESTATIC)visitInsn(org.objectweb.asm.Opcodes.POP)
                            if(org.objectweb.asm.Type.getReturnType(descriptor).sort==org.objectweb.asm.Type.INT)visitInsn(org.objectweb.asm.Opcodes.ICONST_0)
                        }else super.visitMethodInsn(opcode,owner,name,descriptor,isInterface)
                    }
                }
            }
        }
}
androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    variant.instrumentation.transformClassesWith(QuietPdfBoxFactory::class.java,com.android.build.api.instrumentation.InstrumentationScope.ALL){}
}

// Only the isolated optimized harness receives independent text-editing fixtures.
val prepareM24TestAssets = tasks.register<Copy>("prepareM24TestAssets") {
    from("src/test/resources/m24")
    into(layout.buildDirectory.dir("generated/m24TestAssets/m24"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m24TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase = true) || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(prepareM24TestAssets) }

// The explicit visual fallback has independent fixtures only in the isolated optimized package.
val prepareM24VisualTestAssets = tasks.register<Copy>("prepareM24VisualTestAssets") {
    from("src/test/resources/m24visual")
    into(layout.buildDirectory.dir("generated/m24VisualTestAssets/m24visual"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m24VisualTestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase = true) || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(prepareM24VisualTestAssets) }

// M25 synthetic quality input belongs only to the isolated optimized validation variant.
val prepareM25TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m25")
    into(layout.buildDirectory.dir("generated/m25TestAssets/m25"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m25TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase = true) || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(prepareM25TestAssets) }

// M26 fixed quality fixtures belong only to the isolated optimized validation package.
val prepareM26TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m26")
    into(layout.buildDirectory.dir("generated/m26TestAssets/m26"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m26TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase = true) || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(prepareM26TestAssets) }

// M27 fixed quality fixtures belong only to the isolated optimized validation package.
val prepareM27TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m27")
    into(layout.buildDirectory.dir("generated/m27TestAssets/m27"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m27TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase = true) || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(prepareM27TestAssets) }

// M28 fixed quality fixtures belong only to the isolated optimized validation package.
val prepareM28TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m28")
    into(layout.buildDirectory.dir("generated/m28TestAssets/m28"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m28TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase = true) || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(prepareM28TestAssets) }

// M29 independent synthetic fixtures only in the isolated optimized validation package.
val prepareM29TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m29")
    into(layout.buildDirectory.dir("generated/m29TestAssets/m29"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m29TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase = true) || it.name.contains("lint", ignoreCase = true)) }.configureEach { dependsOn(prepareM29TestAssets) }

// Independent M32 corpus stays in isolated validation variants, never production assets.
val prepareM32TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m32")
    into(layout.buildDirectory.dir("generated/m32TestAssets/m32"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m32TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase=true) || it.name.contains("lint", ignoreCase=true)) }.configureEach { dependsOn(prepareM32TestAssets) }

val prepareM32FollowupTestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m32-followup")
    into(layout.buildDirectory.dir("generated/m32TestAssets/m32-followup"))
}
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase=true) || it.name.contains("lint", ignoreCase=true)) }.configureEach { dependsOn(prepareM32FollowupTestAssets) }

// M33 synthetic workbooks/input fixtures are absent from production assets.
val prepareM33TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m33")
    from("src/test/resources/m22/optical-original.png")
    into(layout.buildDirectory.dir("generated/m33TestAssets/m33"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m33TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets", ignoreCase=true) || it.name.contains("lint", ignoreCase=true)) }.configureEach { dependsOn(prepareM33TestAssets) }

val prepareM34TestAssets by tasks.registering(Copy::class) {
    from("src/test/resources/m34")
    into(layout.buildDirectory.dir("generated/m34TestAssets/m34"))
}
android.sourceSets.getByName("m04R8Smoke").assets.directories.add(layout.buildDirectory.dir("generated/m34TestAssets").get().asFile.absolutePath)
tasks.matching { it.name.contains("M04R8Smoke") && (it.name.contains("assets",true)||it.name.contains("lint",true)) }.configureEach { dependsOn(prepareM34TestAssets) }

tasks.named("preBuild").configure { dependsOn(prepareOfficeSources) }
