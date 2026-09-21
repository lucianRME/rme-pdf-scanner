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
    fun readAndVerify(
        source: InputStream,
        staging: BackupStagingSink,
        supportedRequiredFeatures: Set<String> = emptySet(),
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): VerifiedBackup {
        try {
            val state = ReaderState(staging, supportedRequiredFeatures, limits)
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

    private class ReaderState(
        private val staging: BackupStagingSink,
        private val supportedRequiredFeatures: Set<String>,
        private val limits: BackupFormatLimits,
    ) {
        private val pathRegistry = BackupPathRegistry()
        private val observedEntries = LinkedHashMap<String, ObservedEntry>()
        private val folders = ArrayList<BackupFolderRecord>()
        private val documents = ArrayList<BackupDocumentRecord>()
        private val pages = ArrayList<BackupPageRecord>()
        private val sourceAssets = ArrayList<BackupSourceAssetRecord>()
        private val budget = ArchiveReadBudget(limits)
        private var manifest: BackupManifest? = null
        private var ledger: List<BackupChecksum>? = null
        private var entryCount = 0

        fun readAll(zip: ZipInputStream) {
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > limits.maximumEntryCount) {
                    limitExceeded("The backup has too many ZIP entries.")
                }
                val path = entry.name
                if (entry.isDirectory) {
                    throw backupFailure(
                        BackupFormatFailure.INVALID_PATH,
                        "Directory ZIP entries are not part of the backup format.",
                    )
                }
                pathRegistry.add(path)
                when (path) {
                    RME_BACKUP_MANIFEST_PATH -> readManifest(zip, entry, path)
                    RME_BACKUP_FOLDERS_PATH -> readFolders(zip, entry, path)
                    RME_BACKUP_DOCUMENTS_PATH -> readDocuments(zip, entry, path)
                    RME_BACKUP_PAGES_PATH -> readPages(zip, entry, path)
                    RME_BACKUP_SOURCE_ASSETS_PATH -> readSourceAssets(zip, entry, path)
                    RME_BACKUP_CHECKSUMS_PATH -> readLedger(zip, entry)
                    else -> readAsset(zip, entry, path)
                }
            }
        }

        fun verify(): VerifiedBackup {
            val actualManifest = manifest ?: missingEntry("The backup manifest is missing.")
            val actualLedger = ledger ?: missingEntry("The checksum ledger is missing.")
            REQUIRED_HASHED_ENTRIES.forEach { path ->
                if (path !in observedEntries) missingEntry("A required metadata entry is missing.")
            }

            BackupFormatValidator.validateCompleteBackup(
                manifest = actualManifest,
                folders = folders,
                documents = documents,
                pages = pages,
                sourceAssets = sourceAssets,
                supportedRequiredFeatures = supportedRequiredFeatures,
                limits = limits,
            )
            verifyLedger(actualLedger)
            verifyDeclaredAssets()
            return VerifiedBackup(
                manifest = actualManifest,
                folders = folders.toList(),
                documents = documents.toList(),
                pages = pages.toList(),
                sourceAssets = sourceAssets.toList(),
            )
        }

        private fun readManifest(zip: ZipInputStream, entry: ZipEntry, path: String) {
            val result = readMeasuredEntry(
                zip = zip,
                entry = entry,
                path = path,
                maximumBytes = limits.maximumManifestBytes.toLong(),
            ) { input -> BackupJsonCodec.readManifest(input, limits) }
            manifest = result.value
            BackupFormatValidator.validateCompatibility(result.value, supportedRequiredFeatures, limits)
        }

        private fun readFolders(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(
                zip = zip,
                entry = entry,
                path = path,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { input -> BackupJsonCodec.readFolders(input, limits, folders::add) }
        }

        private fun readDocuments(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(
                zip = zip,
                entry = entry,
                path = path,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { input -> BackupJsonCodec.readDocuments(input, limits, documents::add) }
        }

        private fun readPages(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(
                zip = zip,
                entry = entry,
                path = path,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { input -> BackupJsonCodec.readPages(input, limits, pages::add) }
        }

        private fun readSourceAssets(zip: ZipInputStream, entry: ZipEntry, path: String) {
            readMeasuredEntry(
                zip = zip,
                entry = entry,
                path = path,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { input -> BackupJsonCodec.readSourceAssets(input, limits, sourceAssets::add) }
        }

        private fun readLedger(zip: ZipInputStream, entry: ZipEntry) {
            val result = readMeasuredEntry(
                zip = zip,
                entry = entry,
                path = RME_BACKUP_CHECKSUMS_PATH,
                maximumBytes = limits.maximumLedgerBytes.toLong(),
                includeAsObserved = false,
            ) { input -> BackupChecksumLedger.read(input, limits) }
            ledger = result.value
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
                zip = zip,
                entry = entry,
                path = path,
                maximumBytes = limits.maximumSingleAssetBytes,
                inspection = inspection,
            ) { input -> stageAsset(path, input) }
        }

        private fun stageAsset(path: String, input: InputStream) {
            val destination = try {
                staging.open(path)
            } catch (exception: Exception) {
                throw backupFailure(
                    BackupFormatFailure.STAGING_FAILED,
                    "A staging output could not be opened.",
                    exception,
                )
            }
            var failure: Throwable? = null
            try {
                copyToStaging(input, destination)
            } catch (throwable: Throwable) {
                failure = throwable
            }
            try {
                destination.close()
            } catch (closeFailure: Throwable) {
                val wrapped = backupFailure(
                    BackupFormatFailure.STAGING_FAILED,
                    "A staging output could not be closed.",
                    closeFailure,
                )
                if (failure == null) failure = wrapped else requireNotNull(failure).addSuppressed(wrapped)
            }
            failure?.let { throw it }
        }

        private fun copyToStaging(source: InputStream, destination: OutputStream) {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                try {
                    destination.write(buffer, 0, count)
                } catch (exception: Exception) {
                    throw backupFailure(
                        BackupFormatFailure.STAGING_FAILED,
                        "An asset could not be written to staging.",
                        exception,
                    )
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
                source = zip,
                maximumEntryBytes = maximumBytes,
                budget = budget,
                inspection = inspection,
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
            if (includeAsObserved) observedEntries[path] = observed
            return MeasuredValue(value, observed)
        }

        private fun requireCompressionRatio(entry: ZipEntry, uncompressedBytes: Long) {
            val compressedBytes = entry.compressedSize
            if (uncompressedBytes == 0L) return
            if (compressedBytes == 0L || (
                    compressedBytes > 0L &&
                        uncompressedBytes / compressedBytes > limits.maximumCompressionRatio
                    )
            ) {
                limitExceeded("A ZIP entry exceeds the configured decompression ratio.")
            }
        }

        private fun verifyLedger(checksums: List<BackupChecksum>) {
            val byPath = checksums.associateBy(BackupChecksum::path)
            if (byPath.size != checksums.size || byPath.keys != observedEntries.keys) {
                throw backupFailure(
                    BackupFormatFailure.INVALID_LEDGER,
                    "The checksum ledger does not declare every archive entry exactly once.",
                )
            }
            observedEntries.forEach { (path, observed) ->
                val declared = byPath.getValue(path)
                if (declared.byteLength != observed.byteLength) {
                    throw backupFailure(
                        BackupFormatFailure.SIZE_MISMATCH,
                        "A ZIP entry does not match its ledger byte length.",
                    )
                }
                if (!constantTimeEquals(declared.sha256, observed.sha256)) {
                    throw backupFailure(
                        BackupFormatFailure.HASH_MISMATCH,
                        "A ZIP entry does not match its ledger SHA-256.",
                    )
                }
            }
        }

        private fun verifyDeclaredAssets() {
            val declared = LinkedHashMap<String, DeclaredAssetMetadata>()
            pages.forEach { page ->
                declared[page.relativePath] = DeclaredAssetMetadata(
                    page.mimeType,
                    page.sha256,
                    page.byteLength,
                    page.width,
                    page.height,
                )
            }
            sourceAssets.forEach { source ->
                declared[source.relativePath] = DeclaredAssetMetadata(
                    source.mimeType,
                    source.sha256,
                    source.byteLength,
                    null,
                    null,
                )
            }
            val observedAssets = observedEntries.filterKeys(BackupPathValidator::isAssetPath)
            val undeclared = observedAssets.keys - declared.keys
            if (undeclared.isNotEmpty()) {
                throw backupFailure(
                    BackupFormatFailure.UNDECLARED_ENTRY,
                    "The backup contains an asset not declared by metadata.",
                )
            }
            val missing = declared.keys - observedAssets.keys
            if (missing.isNotEmpty()) missingEntry("A declared asset is missing from the backup.")

            declared.forEach { (path, metadata) ->
                val observed = observedAssets.getValue(path)
                if (metadata.byteLength != observed.byteLength) {
                    throw backupFailure(
                        BackupFormatFailure.SIZE_MISMATCH,
                        "An asset does not match its metadata byte length.",
                    )
                }
                if (!constantTimeEquals(metadata.sha256, observed.sha256)) {
                    throw backupFailure(
                        BackupFormatFailure.HASH_MISMATCH,
                        "An asset does not match its metadata SHA-256.",
                    )
                }
                requireNotNull(observed.inspection).requireMatches(
                    metadata.mimeType,
                    metadata.width,
                    metadata.height,
                )
            }
        }
    }
}

private data class DeclaredAssetMetadata(
    val mimeType: String,
    val sha256: String,
    val byteLength: Long,
    val width: Int?,
    val height: Int?,
)

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
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var byteLength = 0L
    private var exhausted = false

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
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

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII),
    right.toByteArray(Charsets.US_ASCII),
)

private fun missingEntry(message: String): Nothing = throw backupFailure(
    BackupFormatFailure.MISSING_ENTRY,
    message,
)

private val REQUIRED_HASHED_ENTRIES = setOf(
    RME_BACKUP_MANIFEST_PATH,
    RME_BACKUP_FOLDERS_PATH,
    RME_BACKUP_DOCUMENTS_PATH,
    RME_BACKUP_PAGES_PATH,
    RME_BACKUP_SOURCE_ASSETS_PATH,
)

private const val COPY_BUFFER_BYTES = 32 * 1024
