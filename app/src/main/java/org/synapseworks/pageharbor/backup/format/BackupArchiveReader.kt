package org.synapseworks.pageharbor.backup.format

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

object BackupArchiveReader {
    /**
     * Restore reader that writes every decoded record and integrity row into an operation-owned
     * disk index. The only retained state is a manifest, counters, and one bounded JSON record.
     */
    fun readAndVerify(
        source: InputStream,
        staging: BackupIndexedStagingSink,
        supportedRequiredFeatures: Set<String> = emptySet(),
        limits: BackupFormatLimits = BackupFormatLimits(),
        checkCancellation: () -> Unit = {},
        onManifest: (BackupManifest) -> Unit = {},
    ): IndexedVerifiedBackup {
        try {
            val state = IndexedReaderState(
                staging,
                supportedRequiredFeatures,
                limits,
                checkCancellation,
                onManifest,
            )
            ZipInputStream(BufferedInputStream(source)).use { zip -> state.readAll(zip) }
            val verified = state.verify()
            try {
                staging.verified()
            } catch (exception: Exception) {
                throw backupFailure(
                    BackupFormatFailure.STAGING_FAILED,
                    "The verified staging area could not be finalized.",
                    exception,
                )
            }
            return verified
        } catch (throwable: Throwable) {
            try {
                staging.abort()
            } catch (abortFailure: Throwable) {
                throwable.addSuppressed(abortFailure)
            }
            when (throwable) {
                is BackupFormatException -> throw throwable
                is ZipException -> throw backupFailure(
                    BackupFormatFailure.MALFORMED_ZIP,
                    "The backup is not a valid ZIP archive.",
                    throwable,
                )

                is IOException -> throw backupFailure(
                    BackupFormatFailure.IO_ERROR,
                    "The backup ZIP could not be read.",
                    throwable,
                )

                else -> throw throwable
            }
        }
    }

    private class IndexedReaderState(
        private val staging: BackupIndexedStagingSink,
        private val supportedRequiredFeatures: Set<String>,
        private val limits: BackupFormatLimits,
        private val checkCancellation: () -> Unit,
        private val onManifest: (BackupManifest) -> Unit,
    ) {
        private val budget = ArchiveReadBudget(limits)
        private var manifest: BackupManifest? = null
        private var entryCount = 0
        private var folderCount = 0
        private var documentCount = 0
        private var pageCount = 0
        private var sourceAssetCount = 0
        private var ocrDocumentStateCount = 0
        private var ocrPageStateCount = 0
        private var ocrArtifactCount = 0
        private var ocrCorrectionCount = 0
        private var ocrLineCount = 0
        private var ocrLineChunkCount = 0

        fun readAll(zip: ZipInputStream) {
            while (true) {
                checkCancellation()
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > limits.maximumEntryCount) limitExceeded("The backup has too many ZIP entries.")
                val path = entry.name
                if (entryCount == 1 && path != RME_BACKUP_MANIFEST_PATH) {
                    missingEntry("The backup manifest must be the first ZIP entry.")
                }
                if (entry.isDirectory) {
                    throw backupFailure(
                        BackupFormatFailure.INVALID_PATH,
                        "Directory ZIP entries are not part of the backup format.",
                    )
                }
                staging.acceptArchivePath(path)
                when (path) {
                    RME_BACKUP_MANIFEST_PATH -> readManifest(zip, entry, path)
                    RME_BACKUP_FOLDERS_PATH -> readFolders(zip, entry, path)
                    RME_BACKUP_DOCUMENTS_PATH -> readDocuments(zip, entry, path)
                    RME_BACKUP_PAGES_PATH -> readPages(zip, entry, path)
                    RME_BACKUP_SOURCE_ASSETS_PATH -> readSourceAssets(zip, entry, path)
                    RME_BACKUP_OCR_DOCUMENT_STATES_PATH -> readOcrDocumentStates(zip, entry, path)
                    RME_BACKUP_OCR_PAGE_STATES_PATH -> readOcrPageStates(zip, entry, path)
                    RME_BACKUP_OCR_ARTIFACTS_PATH -> readOcrArtifacts(zip, entry, path)
                    RME_BACKUP_OCR_CORRECTIONS_PATH -> readOcrCorrections(zip, entry, path)
                    RME_BACKUP_CHECKSUMS_PATH -> readLedger(zip, entry)
                    else -> if (OCR_LINE_CHUNK_PATH.matches(path)) {
                        readOcrLines(zip, entry, path)
                    } else {
                        readAsset(zip, entry, path)
                    }
                }
            }
        }

        fun verify(): IndexedVerifiedBackup {
            if (manifest == null) missingEntry("The backup manifest is missing.")
            return staging.verifyIndexed(supportedRequiredFeatures, limits, checkCancellation)
        }

        private fun readManifest(zip: ZipInputStream, entry: ZipEntry, path: String) {
            val result = readMeasuredEntry(
                zip,
                entry,
                path,
                limits.maximumManifestBytes.toLong(),
            ) { BackupJsonCodec.readManifest(it, limits) }
            manifest = result.value
            BackupFormatValidator.validateCompatibility(result.value, supportedRequiredFeatures, limits)
            checkCancellation()
            onManifest(result.value)
            checkCancellation()
            staging.acceptManifest(result.value)
        }

        private fun readFolders(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumMetadataEntryBytes.toLong()) { input ->
                BackupJsonCodec.readFolders(input, limits) {
                    checkCancellation()
                    staging.acceptFolder(folderCount++, it)
                }
            }
        }

        private fun readDocuments(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumMetadataEntryBytes.toLong()) { input ->
                BackupJsonCodec.readDocuments(input, limits) {
                    checkCancellation()
                    staging.acceptDocument(documentCount++, it)
                }
            }
        }

        private fun readPages(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumMetadataEntryBytes.toLong()) { input ->
                BackupJsonCodec.readPages(input, limits) {
                    checkCancellation()
                    staging.acceptPage(pageCount++, it)
                }
            }
        }

        private fun readSourceAssets(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumMetadataEntryBytes.toLong()) { input ->
                BackupJsonCodec.readSourceAssets(input, limits) {
                    checkCancellation()
                    staging.acceptSourceAsset(sourceAssetCount++, it)
                }
            }
        }

        private fun readOcrDocumentStates(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumOcrMetadataEntryBytes.toLong()) { input ->
                BackupOcrJsonCodec.readDocumentStates(input, limits) {
                    checkCancellation()
                    staging.acceptOcrDocumentState(ocrDocumentStateCount++, it)
                }
            }
        }

        private fun readOcrPageStates(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumOcrMetadataEntryBytes.toLong()) { input ->
                BackupOcrJsonCodec.readPageStates(input, limits) {
                    checkCancellation()
                    staging.acceptOcrPageState(ocrPageStateCount++, it)
                }
            }
        }

        private fun readOcrArtifacts(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumOcrMetadataEntryBytes.toLong()) { input ->
                BackupOcrJsonCodec.readArtifacts(input, limits) {
                    checkCancellation()
                    staging.acceptOcrArtifact(ocrArtifactCount++, it)
                }
            }
        }

        private fun readOcrCorrections(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(zip, entry, path, limits.maximumOcrMetadataEntryBytes.toLong()) { input ->
                BackupOcrJsonCodec.readCorrections(input, limits) {
                    checkCancellation()
                    staging.acceptOcrCorrection(ocrCorrectionCount++, it)
                }
            }
        }

        private fun readOcrLines(zip: ZipInputStream, entry: ZipEntry, path: String) {
            val expectedPath = "$RME_BACKUP_OCR_LINES_DIRECTORY/${ocrLineChunkCount.toString().padStart(6, '0')}.jsonl"
            if (path != expectedPath) {
                throw backupFailure(
                    BackupFormatFailure.INVALID_PATH,
                    "OCR line chunks are not in canonical order.",
                )
            }
            val stagedOutput = try {
                staging.openMetadata(path)
            } catch (exception: Exception) {
                throw backupFailure(
                    BackupFormatFailure.STAGING_FAILED,
                    "An OCR metadata staging output could not be opened.",
                    exception,
                )
            }
            var lineCount = 0
            val result = stagedOutput.use { output ->
                readMeasuredEntry(
                    zip,
                    entry,
                    path,
                    limits.maximumOcrLineChunkBytes.toLong(),
                ) { input ->
                    BackupOcrJsonCodec.readLines(TeeInputStream(input, output), limits) { line ->
                        checkCancellation()
                        staging.acceptOcrLine(ocrLineCount++, line)
                        lineCount += 1
                    }
                }
            }
            checkCancellation()
            staging.acceptOcrLineChunk(
                ocrLineChunkCount++,
                BackupOcrLineChunkDescriptor(path, lineCount, result.observed.byteLength),
            )
        }

        private fun readLedger(zip: ZipInputStream, entry: ZipEntry) {
            readMeasuredEntry(
                zip,
                entry,
                RME_BACKUP_CHECKSUMS_PATH,
                limits.maximumLedgerBytes.toLong(),
                includeAsObserved = false,
            ) { input ->
                BackupChecksumLedger.read(input, limits) { checksum ->
                    checkCancellation()
                    staging.acceptChecksum(checksum)
                }
            }
        }

        private fun readAsset(zip: ZipInputStream, entry: ZipEntry, path: String) {
            if (!BackupPathValidator.isAssetPath(path)) {
                throw backupFailure(
                    BackupFormatFailure.UNDECLARED_ENTRY,
                    "The backup contains an undeclared entry.",
                )
            }
            val inspection = BackupAssetInspection.Collector()
            readMeasuredEntry(
                zip,
                entry,
                path,
                limits.maximumSingleAssetBytes,
                inspection = inspection,
            ) { input -> stageAsset(path, input) }
        }

        private fun stageAsset(path: String, input: InputStream) {
            val output = try {
                staging.open(path)
            } catch (exception: Exception) {
                throw backupFailure(
                    BackupFormatFailure.STAGING_FAILED,
                    "A staging output could not be opened.",
                    exception,
                )
            }
            output.use { destination ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) destination.write(buffer, 0, count)
                }
            }
        }

        private fun <T> readMeasuredEntry(
            zip: ZipInputStream,
            entry: ZipEntry,
            path: String,
            maximumBytes: Long,
            includeAsObserved: Boolean = true,
            inspection: BackupAssetInspection.Collector? = null,
            read: (InputStream) -> T,
        ): MeasuredValue<T> {
            val measuredInput = MeasuringEntryInputStream(
                zip,
                maximumBytes,
                budget,
                inspection,
                checkCancellation,
            )
            val value = read(measuredInput)
            val measured = measuredInput.finish()
            zip.closeEntry()
            requireCompressionRatio(entry, measured.byteLength)
            val observed = ObservedEntry(
                path = path,
                sha256 = measured.sha256,
                byteLength = measured.byteLength,
                inspection = inspection?.finish(),
            )
            if (includeAsObserved) {
                checkCancellation()
                staging.acceptObservedEntry(
                    BackupObservedEntryRecord(
                        path = observed.path,
                        sha256 = observed.sha256,
                        byteLength = observed.byteLength,
                        inspection = observed.inspection?.snapshot(),
                    ),
                )
            }
            return MeasuredValue(value, observed)
        }

        private fun requireCompressionRatio(entry: ZipEntry, uncompressedBytes: Long) {
            val compressedBytes = entry.compressedSize
            if (uncompressedBytes == 0L) return
            if (compressedBytes == 0L ||
                (compressedBytes > 0L && uncompressedBytes / compressedBytes > limits.maximumCompressionRatio)
            ) {
                limitExceeded("A ZIP entry exceeds the configured decompression ratio.")
            }
        }
    }

}

private data class ObservedEntry(
    val path: String,
    val sha256: String,
    val byteLength: Long,
    val inspection: BackupAssetInspection?,
)

private data class MeasuredValue<T>(
    val value: T,
    val observed: ObservedEntry,
)

private data class MeasuredEntry(
    val sha256: String,
    val byteLength: Long,
)

private class MeasuringEntryInputStream(
    private val source: InputStream,
    private val maximumEntryBytes: Long,
    private val budget: ArchiveReadBudget,
    private val inspection: BackupAssetInspection.Collector?,
    private val checkCancellation: () -> Unit,
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var byteLength = 0L
    private var exhausted = false

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancellation()
        val count = source.read(buffer, offset, length)
        if (count < 0) {
            exhausted = true
            return -1
        }
        if (count == 0) return 0
        val newLength = checkedAdd(byteLength, count.toLong())
        if (newLength > maximumEntryBytes) limitExceeded("A ZIP entry exceeds its configured limit.")
        budget.consume(count)
        digest.update(buffer, offset, count)
        inspection?.update(buffer, offset, count)
        byteLength = newLength
        return count
    }

    fun finish(): MeasuredEntry {
        if (!exhausted) {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (read(buffer) >= 0) {
                // Drain so the digest and all limits cover the complete entry.
            }
        }
        return MeasuredEntry(digest.digest().toLowerHex(), byteLength)
    }
}

private class TeeInputStream(
    private val source: InputStream,
    private val copy: OutputStream,
) : InputStream() {
    override fun read(): Int {
        val value = source.read()
        if (value >= 0) copy.write(value)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = source.read(buffer, offset, length)
        if (count > 0) copy.write(buffer, offset, count)
        return count
    }
}

private class ArchiveReadBudget(
    private val limits: BackupFormatLimits,
) {
    private var totalUncompressedBytes = 0L

    fun consume(count: Int) {
        totalUncompressedBytes = checkedAdd(totalUncompressedBytes, count.toLong())
        if (totalUncompressedBytes > limits.maximumTotalUncompressedBytes) {
            limitExceeded("The total uncompressed backup size exceeds the configured limit.")
        }
    }
}

private fun missingEntry(message: String): Nothing = throw backupFailure(
    BackupFormatFailure.MISSING_ENTRY,
    message,
)

private val V1_REQUIRED_HASHED_ENTRIES = setOf(
    RME_BACKUP_MANIFEST_PATH,
    RME_BACKUP_FOLDERS_PATH,
    RME_BACKUP_DOCUMENTS_PATH,
    RME_BACKUP_PAGES_PATH,
    RME_BACKUP_SOURCE_ASSETS_PATH,
)

private fun requiredHashedEntries(manifest: BackupManifest): Set<String> = when (manifest.formatVersion) {
    RME_BACKUP_FORMAT_VERSION_V1 -> V1_REQUIRED_HASHED_ENTRIES
    RME_BACKUP_FORMAT_VERSION_V2 -> V1_REQUIRED_HASHED_ENTRIES + setOf(
        RME_BACKUP_OCR_DOCUMENT_STATES_PATH,
        RME_BACKUP_OCR_PAGE_STATES_PATH,
        RME_BACKUP_OCR_ARTIFACTS_PATH,
        RME_BACKUP_OCR_CORRECTIONS_PATH,
    ) + requireNotNull(manifest.ocr).lineChunks.map(BackupOcrLineChunkDescriptor::path)
    else -> emptySet()
}

private val OCR_LINE_CHUNK_PATH = Regex("ocr-lines/[0-9]{6,}\\.jsonl")

private const val COPY_BUFFER_BYTES = 32 * 1024
