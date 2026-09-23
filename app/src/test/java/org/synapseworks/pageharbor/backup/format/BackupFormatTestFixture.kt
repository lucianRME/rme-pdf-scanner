package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class BackupFormatTestFixture(
    pageCount: Int = 2,
    formatVersion: Int = 1,
    minimumReaderVersion: Int = 1,
    requiredFeatures: List<String> = emptyList(),
) {
    val sourceBytes = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n".toByteArray()
    val pageBytesByPath: Map<String, ByteArray>
    val folders = listOf(
        BackupFolderRecord("folder-root", "Archive", null, 1_000L, 2_000L),
        BackupFolderRecord("folder-child", "Receipts", "folder-root", 1_100L, 2_100L),
    )
    val pages: List<BackupPageRecord>
    val sourceAssets: List<BackupSourceAssetRecord>
    val documents: List<BackupDocumentRecord>
    val manifest: BackupManifest
    val records: BackupRecordSource
    val assets: BackupAssetStreamOpener

    init {
        require(pageCount > 0)
        val pageMap = LinkedHashMap<String, ByteArray>()
        pages = (0 until pageCount).map { position ->
            val pageId = "page-${position + 1}"
            val path = "documents/document-1/pages/$position-$pageId.png"
            val bytes = syntheticPng(position)
            pageMap[path] = bytes
            BackupPageRecord(
                pageId = pageId,
                documentId = "document-1",
                position = position,
                relativePath = path,
                mimeType = "image/png",
                sha256 = sha256(bytes),
                byteLength = bytes.size.toLong(),
                width = 1,
                height = 1,
                rotationDegrees = if (position == 1) 90 else 0,
                filterName = if (position == 1) "GRAYSCALE" else "ORIGINAL",
                ocrText = if (position == 0) "Synthetic text" else null,
                ocrError = null,
                sourcePageIndex = position,
            )
        }
        pageBytesByPath = pageMap.toMap()
        val sourcePath = "documents/document-1/sources/source-1.pdf"
        sourceAssets = listOf(
            BackupSourceAssetRecord(
                sourceId = "source-1",
                documentId = "document-1",
                role = "ORIGINAL_DOCUMENT",
                relativePath = sourcePath,
                mimeType = "application/pdf",
                sha256 = sha256(sourceBytes),
                byteLength = sourceBytes.size.toLong(),
                sourceModifiedAtEpochMillis = null,
                matchesCurrentRevision = false,
            ),
        )
        documents = listOf(
            BackupDocumentRecord(
                documentId = "document-1",
                folderId = "folder-child",
                title = "Synthetic backup",
                createdAtEpochMillis = 1_000L,
                modifiedAtEpochMillis = 3_000L,
                contentHashVersion = 1,
                contentSha256 = null,
                pageCount = pageCount,
                sourceAssetCount = 1,
            ),
        )
        val contentBytes = pageMap.values.sumOf { it.size.toLong() } + sourceBytes.size
        manifest = BackupManifest(
            formatVersion = formatVersion,
            minimumReaderVersion = minimumReaderVersion,
            requiredFeatures = requiredFeatures,
            backupId = "00000000-0000-4000-8000-000000000015",
            createdAtEpochMillis = 4_000L,
            producer = BackupProducer("org.synapseworks.pageharbor", "1.5.0", 16),
            summary = BackupSummary(2, 1, pageCount, 1, contentBytes),
            metadata = BackupMetadataPaths(
                RME_BACKUP_FOLDERS_PATH,
                RME_BACKUP_DOCUMENTS_PATH,
                RME_BACKUP_PAGES_PATH,
                RME_BACKUP_SOURCE_ASSETS_PATH,
            ),
            integrity = BackupIntegrity("SHA-256", RME_BACKUP_CHECKSUMS_PATH),
        )
        records = SnapshotBackupRecordSource(folders, documents, pages, sourceAssets)
        val allAssets = pageMap + mapOf(sourcePath to sourceBytes)
        assets = BackupAssetStreamOpener { path ->
            ByteArrayInputStream(requireNotNull(allAssets[path]))
        }
    }

    fun writeArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        BackupArchiveWriter.write(output, manifest, records, assets)
        return output.toByteArray()
    }

    private fun syntheticPng(marker: Int): ByteArray = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
        0x00,
        0x00,
        0x00,
        0x0d,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        marker.toByte(),
    )
}

internal class MemoryStagingSink : BackupStagingSink {
    val assets = LinkedHashMap<String, ByteArrayOutputStream>()
    var verified = false
    var aborted = false

    override fun open(relativePath: String): OutputStream = ByteArrayOutputStream().also {
        assets[relativePath] = it
    }

    override fun verified() {
        verified = true
    }

    override fun abort() {
        aborted = true
        assets.clear()
    }
}

internal fun archiveEntries(archive: ByteArray): LinkedHashMap<String, ByteArray> {
    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries[entry.name] = zip.readBytes()
        }
    }
    return entries
}

internal fun archiveWithFreshLedger(entries: Map<String, ByteArray>): ByteArray {
    val withoutLedger = LinkedHashMap(entries).apply { remove(RME_BACKUP_CHECKSUMS_PATH) }
    val ledger = withoutLedger.entries.sortedBy { it.key }.joinToString(separator = "") { (path, bytes) ->
        "${sha256(bytes)}  ${bytes.size}  $path\n"
    }.toByteArray()
    return rawZip(withoutLedger + mapOf(RME_BACKUP_CHECKSUMS_PATH to ledger))
}

internal fun rawZip(entries: Map<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

internal fun readAndVerify(
    archive: ByteArray,
    staging: MemoryStagingSink = MemoryStagingSink(),
    limits: BackupFormatLimits = BackupFormatLimits(),
): VerifiedBackup = BackupArchiveReader.readAndVerify(
    source = ByteArrayInputStream(archive),
    staging = staging,
    limits = limits,
)

internal fun assertBackupFailure(
    expected: BackupFormatFailure,
    block: () -> Unit,
): BackupFormatException {
    val failure = org.junit.Assert.assertThrows(BackupFormatException::class.java, block)
    org.junit.Assert.assertEquals(expected, failure.failure)
    return failure
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
