package org.synapseworks.pageharbor.backup.format

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupArchiveWriter {
    fun write(
        destination: OutputStream,
        manifest: BackupManifest,
        records: BackupRecordSource,
        assets: BackupAssetStreamOpener,
        supportedRequiredFeatures: Set<String> = emptySet(),
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): BackupWriteResult {
        val folders = records.folders().toBoundedList(limits.maximumFolderCount)
        val documents = records.documents().toBoundedList(limits.maximumDocumentCount)
        val pages = records.pages().toBoundedList(limits.maximumPageCount)
        val sourceAssets = records.sourceAssets().toBoundedList(limits.maximumSourceAssetCount)
        BackupFormatValidator.validateCompleteBackup(
            manifest = manifest,
            folders = folders,
            documents = documents,
            pages = pages,
            sourceAssets = sourceAssets,
            supportedRequiredFeatures = supportedRequiredFeatures,
            limits = limits,
        )
        val expectedEntryCount = 6L + pages.size.toLong() + sourceAssets.size.toLong()
        if (expectedEntryCount > limits.maximumEntryCount) {
            limitExceeded("The backup has too many ZIP entries.")
        }

        val zip = ZipOutputStream(destination)
        val state = WriterState(zip, limits)
        try {
            state.writeEntry(
                path = RME_BACKUP_MANIFEST_PATH,
                maximumBytes = limits.maximumManifestBytes.toLong(),
            ) { output -> BackupJsonCodec.writeManifest(manifest, output, limits) }
            state.writeEntry(
                path = RME_BACKUP_FOLDERS_PATH,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { output -> BackupJsonCodec.writeFolders(folders.asSequence(), output, limits) }
            state.writeEntry(
                path = RME_BACKUP_DOCUMENTS_PATH,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { output -> BackupJsonCodec.writeDocuments(documents.asSequence(), output, limits) }
            state.writeEntry(
                path = RME_BACKUP_PAGES_PATH,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { output -> BackupJsonCodec.writePages(pages.asSequence(), output, limits) }
            state.writeEntry(
                path = RME_BACKUP_SOURCE_ASSETS_PATH,
                maximumBytes = limits.maximumMetadataEntryBytes.toLong(),
            ) { output -> BackupJsonCodec.writeSourceAssets(sourceAssets.asSequence(), output, limits) }

            val declaredAssets = buildList<DeclaredAsset> {
                pages.forEach { page ->
                    add(
                        DeclaredAsset(
                            path = page.relativePath,
                            mimeType = page.mimeType,
                            sha256 = page.sha256,
                            byteLength = page.byteLength,
                            width = page.width,
                            height = page.height,
                        ),
                    )
                }
                sourceAssets.forEach { source ->
                    add(
                        DeclaredAsset(
                            path = source.relativePath,
                            mimeType = source.mimeType,
                            sha256 = source.sha256,
                            byteLength = source.byteLength,
                            width = null,
                            height = null,
                        ),
                    )
                }
            }.sortedBy(DeclaredAsset::path)
            declaredAssets.forEach { asset -> state.writeAsset(asset, assets) }

            state.writeLedger()
            zip.finish()
            zip.flush()
            return BackupWriteResult(
                entryCount = expectedEntryCount.toInt(),
                contentByteLength = manifest.summary.contentByteLength,
            )
        } catch (exception: BackupFormatException) {
            throw exception
        } catch (exception: IOException) {
            throw backupFailure(
                BackupFormatFailure.IO_ERROR,
                "The backup ZIP could not be written.",
                exception,
            )
        }
    }

    private class WriterState(
        private val zip: ZipOutputStream,
        private val limits: BackupFormatLimits,
    ) {
        private val pathRegistry = BackupPathRegistry()
        private val checksums = ArrayList<BackupChecksum>()
        private var totalUncompressedBytes = 0L

        fun writeEntry(
            path: String,
            maximumBytes: Long,
            includeInLedger: Boolean = true,
            writeContent: (OutputStream) -> Unit,
        ): MeasuredContent {
            pathRegistry.add(path)
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
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
                checksums += BackupChecksum(measured.sha256, measured.byteLength, path)
            }
            return measured
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
            ) { output -> BackupChecksumLedger.write(checksums, output, limits) }
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

private fun <T> Sequence<T>.toBoundedList(maximumSize: Int): List<T> {
    val result = ArrayList<T>(minOf(maximumSize, 1_024))
    forEach { value ->
        if (result.size >= maximumSize) limitExceeded("A metadata record count exceeds its limit.")
        result += value
    }
    return result.toList()
}

internal fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val DEFAULT_COPY_BUFFER_BYTES = 32 * 1024
