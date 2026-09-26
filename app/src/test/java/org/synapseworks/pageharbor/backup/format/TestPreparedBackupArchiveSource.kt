package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** Test-only adapter for small synthetic record fixtures; production accepts disk-prepared sources only. */
internal class TestPreparedBackupArchiveSource(
    manifest: BackupManifest,
    records: BackupRecordSource,
    assets: BackupAssetStreamOpener,
    supportedRequiredFeatures: Set<String> = emptySet(),
    private val limits: BackupFormatLimits = BackupFormatLimits(),
) : PreparedBackupArchiveSource {
    private val entries = LinkedHashMap<String, ByteArray>()
    private val pageAssets: List<PreparedBackupAsset>
    private val sourceAssets: List<PreparedBackupAsset>

    override val preparedSummary: BackupSummary = manifest.summary
    override val preparedOcrManifest: BackupOcrManifest?

    init {
        val preparedManifest = BackupStreamingValidator.prepareManifestForWrite(
            manifest = manifest,
            records = records,
            supportedRequiredFeatures = supportedRequiredFeatures,
            limits = limits,
        )
        preparedOcrManifest = preparedManifest.ocr
        entries[RME_BACKUP_FOLDERS_PATH] = encode { BackupJsonCodec.writeFolders(records.folders(), it, limits) }
        entries[RME_BACKUP_DOCUMENTS_PATH] = encode {
            BackupJsonCodec.writeDocuments(records.documents(), it, limits)
        }
        entries[RME_BACKUP_PAGES_PATH] = encode { BackupJsonCodec.writePages(records.pages(), it, limits) }
        entries[RME_BACKUP_SOURCE_ASSETS_PATH] = encode {
            BackupJsonCodec.writeSourceAssets(records.sourceAssets(), it, limits)
        }
        preparedManifest.ocr?.let { ocr ->
            entries[ocr.documentStatesPath] = encode {
                BackupOcrJsonCodec.writeDocumentStates(records.ocrDocumentStates(), it, limits)
            }
            entries[ocr.pageStatesPath] = encode {
                BackupOcrJsonCodec.writePageStates(records.ocrPageStates(), it, limits)
            }
            entries[ocr.artifactsPath] = encode {
                BackupOcrJsonCodec.writeArtifacts(records.ocrArtifacts(), it, limits)
            }
            entries[ocr.correctionsPath] = encode {
                BackupOcrJsonCodec.writeCorrections(records.ocrCorrections(), it, limits)
            }
            val lines = records.ocrLines().iterator()
            ocr.lineChunks.forEach { descriptor ->
                val output = ByteArrayOutputStream()
                repeat(descriptor.recordCount) {
                    check(lines.hasNext())
                    output.write(BackupOcrJsonCodec.encodeLineBytes(lines.next()))
                }
                check(output.size().toLong() == descriptor.byteLength)
                entries[descriptor.path] = output.toByteArray()
            }
            check(!lines.hasNext())
        }
        pageAssets = records.pages().map { page ->
            PreparedBackupAsset(
                path = page.relativePath,
                mimeType = page.mimeType,
                sha256 = page.sha256,
                byteLength = page.byteLength,
                width = page.width,
                height = page.height,
                openStream = { assets.open(page.relativePath) },
            )
        }.toList()
        sourceAssets = records.sourceAssets().map { source ->
            PreparedBackupAsset(
                path = source.relativePath,
                mimeType = source.mimeType,
                sha256 = source.sha256,
                byteLength = source.byteLength,
                width = null,
                height = null,
                openStream = { assets.open(source.relativePath) },
            )
        }.toList()
    }

    override fun openPreparedEntry(path: String): InputStream =
        ByteArrayInputStream(requireNotNull(entries[path]))

    override fun preparedPageAssets(): PreparedBackupAssetSource = TestPreparedAssetSource(pageAssets)

    override fun preparedSourceAssets(): PreparedBackupAssetSource = TestPreparedAssetSource(sourceAssets)

    override fun checkCancellation() = Unit

    private fun encode(write: (OutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(write).toByteArray()
}

internal fun writeBackupArchiveForTest(
    destination: OutputStream,
    manifest: BackupManifest,
    records: BackupRecordSource,
    assets: BackupAssetStreamOpener,
    supportedRequiredFeatures: Set<String> = emptySet(),
    limits: BackupFormatLimits = BackupFormatLimits(),
): BackupWriteResult {
    val scratch = Files.createTempDirectory("rme-test-backup-writer-").toFile()
    return try {
        BackupArchiveWriter.write(
            destination = destination,
            manifest = manifest,
            source = TestPreparedBackupArchiveSource(
                manifest = manifest,
                records = records,
                assets = assets,
                supportedRequiredFeatures = supportedRequiredFeatures,
                limits = limits,
            ),
            scratchDirectory = scratch,
            supportedRequiredFeatures = supportedRequiredFeatures,
            limits = limits,
        )
    } finally {
        if (scratch.exists() && !scratch.deleteRecursively()) {
            throw IOException("The test backup scratch directory could not be removed.")
        }
    }
}

private class TestPreparedAssetSource(
    private val assets: List<PreparedBackupAsset>,
) : PreparedBackupAssetSource {
    override fun iterator(): Iterator<PreparedBackupAsset> = assets.iterator()

    override fun close() = Unit
}
