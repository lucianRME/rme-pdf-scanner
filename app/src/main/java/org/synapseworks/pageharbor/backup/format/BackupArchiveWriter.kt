package org.synapseworks.pageharbor.backup.format

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.security.MessageDigest

internal object BackupArchiveWriter {
    /** Writes only a prevalidated, disk-prepared source; production never re-materializes records here. */
    fun write(
        destination: OutputStream,
        manifest: BackupManifest,
        source: PreparedBackupArchiveSource,
        scratchDirectory: File,
        supportedRequiredFeatures: Set<String> = emptySet(),
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): BackupWriteResult {
        source.checkCancellation()
        check(manifest.summary == source.preparedSummary)
        val archiveManifest = manifest.copy(ocr = source.preparedOcrManifest).also { value ->
            BackupFormatValidator.validateCompatibility(value, supportedRequiredFeatures, limits)
        }
        val ocrEntryCount = archiveManifest.ocr?.let { 4L + it.lineChunks.size } ?: 0L
        val expectedEntryCount = 6L + archiveManifest.summary.pageCount.toLong() +
            archiveManifest.summary.sourceAssetCount.toLong() + ocrEntryCount
        if (expectedEntryCount > limits.maximumEntryCount) {
            limitExceeded("The backup has too many ZIP entries.")
        }

        val zip = SpoolingZipWriter(
            destination = destination,
            cancellationCheck = source::checkCancellation,
            centralDirectoryFile = createPrivateCentralDirectoryFile(scratchDirectory),
        )
        val state = try {
            WriterState(zip, limits, scratchDirectory, source::checkCancellation)
        } catch (failure: Throwable) {
            try {
                zip.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        try {
            state.writeEntry(
                path = RME_BACKUP_MANIFEST_PATH,
                maximumBytes = limits.maximumManifestBytes.toLong(),
            ) { output -> BackupJsonCodec.writeManifest(archiveManifest, output, limits) }
            state.writePreparedMetadata(source, RME_BACKUP_FOLDERS_PATH, limits.maximumMetadataEntryBytes)
            state.writePreparedMetadata(source, RME_BACKUP_DOCUMENTS_PATH, limits.maximumMetadataEntryBytes)
            state.writePreparedMetadata(source, RME_BACKUP_PAGES_PATH, limits.maximumMetadataEntryBytes)
            state.writePreparedMetadata(source, RME_BACKUP_SOURCE_ASSETS_PATH, limits.maximumMetadataEntryBytes)

            archiveManifest.ocr?.let { ocr ->
                state.writePreparedMetadata(source, ocr.documentStatesPath, limits.maximumOcrMetadataEntryBytes)
                state.writePreparedMetadata(source, ocr.pageStatesPath, limits.maximumOcrMetadataEntryBytes)
                state.writePreparedMetadata(source, ocr.artifactsPath, limits.maximumOcrMetadataEntryBytes)
                state.writePreparedMetadata(source, ocr.correctionsPath, limits.maximumOcrMetadataEntryBytes)
                ocr.lineChunks.forEach { chunk ->
                    state.writePreparedMetadata(source, chunk.path, limits.maximumOcrLineChunkBytes)
                }
            }

            var writtenPageAssets = 0
            val pageAssets = source.preparedPageAssets()
            try {
                pageAssets.forEach { asset ->
                    source.checkCancellation()
                    state.writePreparedAsset(asset)
                    writtenPageAssets += 1
                }
            } finally {
                pageAssets.close()
            }
            requireRecordCount(writtenPageAssets, archiveManifest.summary.pageCount)
            var writtenSourceAssets = 0
            val sourceAssets = source.preparedSourceAssets()
            try {
                sourceAssets.forEach { asset ->
                    source.checkCancellation()
                    state.writePreparedAsset(asset)
                    writtenSourceAssets += 1
                }
            } finally {
                sourceAssets.close()
            }
            requireRecordCount(writtenSourceAssets, archiveManifest.summary.sourceAssetCount)

            state.writeLedger()
            zip.finish()
            zip.flush()
            return BackupWriteResult(
                entryCount = expectedEntryCount.toInt(),
                contentByteLength = archiveManifest.summary.contentByteLength,
                manifest = archiveManifest,
            )
        } catch (exception: BackupFormatException) {
            throw exception
        } catch (exception: IOException) {
            throw backupFailure(
                BackupFormatFailure.IO_ERROR,
                "The backup ZIP could not be written.",
                exception,
            )
        } finally {
            state.close()
        }
    }

    private fun requireRecordCount(actual: Int, expected: Int) {
        if (actual != expected) {
            throw backupFailure(
                BackupFormatFailure.RELATIONSHIP_INVALID,
                "A repeatable backup record source changed while the archive was written.",
            )
        }
    }

    private class WriterState(
        private val zip: SpoolingZipWriter,
        private val limits: BackupFormatLimits,
        scratchDirectory: File,
        private val cancellationCheck: () -> Unit,
    ) : AutoCloseable {
        private val checksums = BackupChecksumSpool(
            limits = limits,
            cancellationCheck = cancellationCheck,
            root = createPrivateChecksumDirectory(scratchDirectory),
        )
        private var totalUncompressedBytes = 0L

        fun writeEntry(
            path: String,
            maximumBytes: Long,
            includeInLedger: Boolean = true,
            writeContent: (OutputStream) -> Unit,
        ): MeasuredContent {
            cancellationCheck()
            BackupPathValidator.requireSupportedEntryPath(path)
            zip.putNextEntry(path, maximumBytes)
            val output = MeasuringOutputStream(
                destination = zip,
                maximumEntryBytes = maximumBytes,
                consumeTotal = ::consumeTotal,
            )
            var failure: Throwable? = null
            try {
                writeContent(output)
            } catch (throwable: Throwable) {
                failure = throwable
            }
            try {
                zip.closeEntry()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
            failure?.let { throw it }
            val measured = output.finish()
            if (includeInLedger) {
                checksums.append(BackupChecksum(measured.sha256, measured.byteLength, path))
            }
            return measured
        }

        fun writePreparedMetadata(
            source: PreparedBackupArchiveSource,
            path: String,
            maximumBytes: Int,
        ) {
            writeEntry(path, maximumBytes.toLong()) { output ->
                source.openPreparedEntry(path).use { input ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                    while (true) {
                        source.checkCancellation()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }
            }
        }

        fun writePreparedAsset(asset: PreparedBackupAsset) {
            writeAsset(
                DeclaredAsset(
                    path = asset.path,
                    mimeType = asset.mimeType,
                    sha256 = asset.sha256,
                    byteLength = asset.byteLength,
                    width = asset.width,
                    height = asset.height,
                ),
                BackupAssetStreamOpener { requestedPath ->
                    check(requestedPath == asset.path)
                    asset.openStream()
                },
            )
        }

        fun writeAsset(asset: DeclaredAsset, opener: BackupAssetStreamOpener) {
            var measuredInspection: BackupAssetInspection? = null
            val measured = writeEntry(
                path = asset.path,
                maximumBytes = limits.maximumSingleAssetBytes,
            ) { output ->
                val inspection = BackupAssetInspection.Collector()
                val input = try {
                    opener.open(asset.path)
                } catch (exception: Exception) {
                    throw backupFailure(
                        BackupFormatFailure.ASSET_UNAVAILABLE,
                        "A declared backup asset could not be opened.",
                        exception,
                    )
                }
                try {
                    input.use { source -> copyAsset(source, output, inspection, asset.byteLength) }
                } catch (exception: BackupFormatException) {
                    throw exception
                } catch (exception: IOException) {
                    throw backupFailure(
                        BackupFormatFailure.ASSET_UNAVAILABLE,
                        "A declared backup asset could not be read.",
                        exception,
                    )
                }
                measuredInspection = inspection.finish()
            }
            if (measured.byteLength != asset.byteLength) {
                throw backupFailure(
                    BackupFormatFailure.SIZE_MISMATCH,
                    "A streamed asset does not match its declared byte length.",
                )
            }
            if (!MessageDigest.isEqual(measured.sha256.toByteArray(), asset.sha256.toByteArray())) {
                throw backupFailure(
                    BackupFormatFailure.HASH_MISMATCH,
                    "A streamed asset does not match its declared SHA-256.",
                )
            }
            requireNotNull(measuredInspection).requireMatches(asset.mimeType, asset.width, asset.height)
        }

        fun writeLedger() {
            writeEntry(
                path = RME_BACKUP_CHECKSUMS_PATH,
                maximumBytes = limits.maximumLedgerBytes.toLong(),
                includeInLedger = false,
            ) { output -> checksums.writeLedger(output) }
        }

        override fun close() {
            var failure: Throwable? = null
            try {
                checksums.close()
            } catch (closeFailure: Throwable) {
                failure = closeFailure
            }
            try {
                zip.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
            failure?.let { throw it }
        }

        private fun copyAsset(
            source: InputStream,
            destination: OutputStream,
            inspection: BackupAssetInspection.Collector,
            declaredLength: Long,
        ) {
            val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
            var copied = 0L
            while (true) {
                cancellationCheck()
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                copied = checkedAdd(copied, count.toLong())
                if (copied > declaredLength) {
                    throw backupFailure(
                        BackupFormatFailure.SIZE_MISMATCH,
                        "A streamed asset exceeds its declared byte length.",
                    )
                }
                inspection.update(buffer, 0, count)
                destination.write(buffer, 0, count)
            }
        }

        private fun consumeTotal(count: Int) {
            totalUncompressedBytes = checkedAdd(totalUncompressedBytes, count.toLong())
            if (totalUncompressedBytes > limits.maximumTotalUncompressedBytes) {
                limitExceeded("The total uncompressed backup size exceeds the configured limit.")
            }
        }
    }
}

/** A repeatable, operation-owned source whose metadata has already been serialized in bounded batches. */
internal interface PreparedBackupArchiveSource {
    val preparedSummary: BackupSummary
    val preparedOcrManifest: BackupOcrManifest?

    fun openPreparedEntry(path: String): InputStream

    fun preparedPageAssets(): PreparedBackupAssetSource

    fun preparedSourceAssets(): PreparedBackupAssetSource

    fun checkCancellation()
}

internal interface PreparedBackupAssetSource : Sequence<PreparedBackupAsset>, AutoCloseable

internal data class PreparedBackupAsset(
    val path: String,
    val mimeType: String,
    val sha256: String,
    val byteLength: Long,
    val width: Int?,
    val height: Int?,
    val openStream: () -> InputStream,
)

private data class DeclaredAsset(
    val path: String,
    val mimeType: String,
    val sha256: String,
    val byteLength: Long,
    val width: Int?,
    val height: Int?,
)

private data class MeasuredContent(
    val byteLength: Long,
    val sha256: String,
)

private class MeasuringOutputStream(
    private val destination: OutputStream,
    private val maximumEntryBytes: Long,
    private val consumeTotal: (Int) -> Unit,
) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var byteLength = 0L

    override fun write(value: Int) {
        val byte = byteArrayOf(value.toByte())
        write(byte, 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val newLength = checkedAdd(byteLength, length.toLong())
        if (newLength > maximumEntryBytes) limitExceeded("A ZIP entry exceeds its configured limit.")
        consumeTotal(length)
        destination.write(buffer, offset, length)
        digest.update(buffer, offset, length)
        byteLength = newLength
    }

    fun finish(): MeasuredContent = MeasuredContent(
        byteLength = byteLength,
        sha256 = digest.digest().toLowerHex(),
    )
}

internal fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val DEFAULT_COPY_BUFFER_BYTES = 32 * 1024
