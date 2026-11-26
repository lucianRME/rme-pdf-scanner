import java.security.MessageDigest
import java.nio.file.Files

// This script is applied only by the three explicitly included on-demand modules.
val packs = mapOf(
    "translation_ro" to Triple("rmeRomanianPackArchive", "opus-en-ro-v1.zip", "9afe2f31534370774c0ee487ba617fe81de7a81d8599a93cb9d8ab36477c865f"),
    "translation_multi" to Triple("rmeMultilingualPackArchive", "rme-multilingual-v1.zip", "d6768346c72f7d2ad9402257b6379fe0639f0a4e9d5f60ce79c6ff3cb4fe1b6f"),
    "translation_ga" to Triple("rmeIrishPackArchive", "rme-irish-v1.zip", "f8de4aaf0170fab68e22264a0cb968e36e833047f22c7176fa9dbe83634ee4a3"),
)
val (property, archiveName, expectedSha) = packs.getValue(project.name)

// Only an explicitly supplied, pinned local archive is eligible. No download.
val archive = providers.gradleProperty(property).map { rootProject.file(it) }
val delivered = layout.projectDirectory.file("src/main/assets/$archiveName")
val prepareTranslationPack = tasks.register("prepareTranslationPack") {
    inputs.file(archive); outputs.file(delivered)
    doLast {
        val source = archive.get()
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Pinned translation archive required: $property" }
        val hash = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        require(hash.digest().joinToString("") { "%02x".format(it) } == expectedSha) { "Translation archive pin mismatch" }
        val target = delivered.asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}
// The asset-pack plugin uses its fixed src/main/assets directory.
tasks.configureEach {
    if (name.startsWith("generate") || name.startsWith("merge") || name.startsWith("package")) dependsOn(prepareTranslationPack)
}
