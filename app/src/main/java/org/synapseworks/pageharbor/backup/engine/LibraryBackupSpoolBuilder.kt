package org.synapseworks.pageharbor.backup.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupJsonCodec
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrJsonCodec
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrManifest
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupSummary
import org.synapseworks.pageharbor.backup.format.PreparedBackupAsset
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_DOCUMENTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_FOLDERS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_ARTIFACTS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_CORRECTIONS_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_DOCUMENT_STATES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_LINES_DIRECTORY
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_OCR_PAGE_STATES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_PAGES_PATH
import org.synapseworks.pageharbor.backup.format.RME_BACKUP_SOURCE_ASSETS_PATH
import org.synapseworks.pageharbor.backup.format.jsonString

internal class LibraryBackupSpoolBuilder(
    private val spoolRoot: File,
    private val limits: BackupFormatLimits,
) : AutoCloseable {
    private val entriesRoot = File(spoolRoot, FilePreparedBackupArchiveSource.ENTRIES_DIRECTORY)
    private val outputs = LinkedHashMap<String, CountingOutputStream>()
    private val pageIndexFile = File(spoolRoot, "page-assets.bin")
    private val sourceIndexFile = File(spoolRoot, "source-assets.bin")
    private val lineChunkIndexFile = File(spoolRoot, "ocr-line-chunks.bin")
    private val pageAssets = BackupAssetIndexWriter(pageIndexFile)
    private val sourceAssets = BackupAssetIndexWriter(sourceIndexFile)
    private val lineChunks = BackupLineChunkIndex(lineChunkIndexFile)
    private var lineOutput: CountingOutputStream? = null
    private var lineChunkRecords = 0
    private var folderCount = 0
    private var documentCount = 0
    private var pageCount = 0
    private var sourceAssetCount = 0
    private var documentStateCount = 0
    private var pageStateCount = 0
    private var artifactCount = 0
    private var correctionCount = 0
    private var lineCount = 0
    private var lineBytes = 0L
    private var contentBytes = 0L
    private var correctionOpen = false
    private var closed = false
    private var finished = false

    init {
        if (!entriesRoot.mkdirs() || !entriesRoot.isDirectory) throw IOException("Backup spool entries are unavailable.")
        listOf(
            RME_BACKUP_FOLDERS_PATH,
            RME_BACKUP_DOCUMENTS_PATH,
            RME_BACKUP_PAGES_PATH,
            RME_BACKUP_SOURCE_ASSETS_PATH,
            RME_BACKUP_OCR_DOCUMENT_STATES_PATH,
            RME_BACKUP_OCR_PAGE_STATES_PATH,
            RME_BACKUP_OCR_ARTIFACTS_PATH,
            RME_BACKUP_OCR_CORRECTIONS_PATH,
        ).forEach { path -> outputs[path] = openEntry(path) }
    }

    fun appendFolder(record: BackupFolderRecord) {
        requireCount(++folderCount, limits.maximumFolderCount)
        BackupJsonCodec.writeFolders(sequenceOf(record), output(RME_BACKUP_FOLDERS_PATH), limits)
    }

    fun appendDocument(record: BackupDocumentRecord) {
        requireCount(++documentCount, limits.maximumDocumentCount)
        BackupJsonCodec.writeDocuments(sequenceOf(record), output(RME_BACKUP_DOCUMENTS_PATH), limits)
    }

    fun appendPage(record: BackupPageRecord, source: File) {
        requireCount(++pageCount, limits.maximumPageCount)
        contentBytes = addExact(contentBytes, record.byteLength)
        BackupJsonCodec.writePages(sequenceOf(record), output(RME_BACKUP_PAGES_PATH), limits)
        pageAssets.append(
            PreparedBackupAsset(
                path = record.relativePath,
                mimeType = record.mimeType,
                sha256 = record.sha256,
                byteLength = record.byteLength,
                width = record.width,
                height = record.height,
                openStream = { throw UnsupportedOperationException() },
            ),
            source,
        )
    }

    fun appendSourceAsset(record: BackupSourceAssetRecord, source: File) {
        requireCount(++sourceAssetCount, limits.maximumSourceAssetCount)
        contentBytes = addExact(contentBytes, record.byteLength)
        BackupJsonCodec.writeSourceAssets(sequenceOf(record), output(RME_BACKUP_SOURCE_ASSETS_PATH), limits)
        sourceAssets.append(
            PreparedBackupAsset(
                path = record.relativePath,
                mimeType = record.mimeType,
                sha256 = record.sha256,
                byteLength = record.byteLength,
                width = null,
                height = null,
                openStream = { throw UnsupportedOperationException() },
            ),
            source,
        )
    }

    fun appendOcrDocumentState(record: BackupOcrDocumentStateRecord) {
        requireCount(++documentStateCount, limits.maximumDocumentCount)
        BackupOcrJsonCodec.writeDocumentStates(
            sequenceOf(record),
            output(RME_BACKUP_OCR_DOCUMENT_STATES_PATH),
            limits,
        )
    }

    fun appendOcrPageState(record: BackupOcrPageStateRecord) {
        requireCount(++pageStateCount, limits.maximumPageCount)
        BackupOcrJsonCodec.writePageStates(sequenceOf(record), output(RME_BACKUP_OCR_PAGE_STATES_PATH), limits)
    }

    fun appendOcrArtifact(record: BackupOcrArtifactRecord) {
        requireCount(++artifactCount, limits.maximumOcrArtifactCount)
        BackupOcrJsonCodec.writeArtifacts(sequenceOf(record), output(RME_BACKUP_OCR_ARTIFACTS_PATH), limits)
    }

    fun appendOcrCorrection(record: BackupOcrCorrectionRecord) {
        val appender = beginOcrCorrection(record.copy(lineCorrections = emptyList()))
        record.lineCorrections.forEach(appender::append)
        appender.finish()
    }

    fun beginOcrCorrection(header: BackupOcrCorrectionRecord): OcrCorrectionAppender {
        check(!correctionOpen)
        require(header.lineCorrections.isEmpty())
        requireCount(++correctionCount, limits.maximumOcrCorrectionCount)
        correctionOpen = true
        val destination = output(RME_BACKUP_OCR_CORRECTIONS_PATH) as CountingOutputStream
        val start = destination.byteCount
        val prefix = buildString {
            append('{')
            append("\"pageId\":").append(jsonString(header.pageId))
            append(",\"baseArtifactRevision\":").append(header.baseArtifactRevision)
            append(",\"correctedText\":").append(jsonString(header.correctedText))
            append(",\"correctedAtEpochMillis\":").append(header.correctedAtEpochMillis)
            append(",\"alignment\":").append(jsonString(header.alignment.name))
            append(",\"lineCorrections\":[")
        }.toByteArray(StandardCharsets.UTF_8)
        destination.write(prefix)
        requireCorrectionBounds(destination, start)
        return OcrCorrectionAppender(destination, start)
    }

    fun appendOcrLine(record: BackupOcrLineRecord) {
        requireCount(++lineCount, limits.maximumOcrLineCount)
        val bytes = BackupOcrJsonCodec.encodeLineBytes(record)
        if (bytes.size > limits.maximumJsonLineBytes || bytes.size > limits.maximumOcrLineChunkBytes) {
            throw IOException("An OCR line exceeds the backup spool limit.")
        }
        val output = lineOutput
        if (output != null && lineChunkRecords > 0 &&
            (lineChunkRecords >= limits.maximumOcrLineChunkRecords ||
                output.byteCount + bytes.size > limits.maximumOcrLineChunkBytes)
        ) {
            finishLineChunk()
        }
        val destination = lineOutput ?: openLineChunk().also { lineOutput = it }
        destination.write(bytes)
        lineChunkRecords += 1
        lineBytes = addExact(lineBytes, bytes.size.toLong())
    }

    fun finish(): SpoolLibraryBackupSnapshot {
        check(!finished && !closed)
        check(!correctionOpen)
        finished = true
        finishLineChunk()
        closeOutputs()
        if (documentStateCount != documentCount || pageStateCount != pageCount) {
            throw IOException("Backup OCR state counts do not match the base metadata.")
        }
        requireEntryLimit(RME_BACKUP_FOLDERS_PATH, limits.maximumMetadataEntryBytes)
        requireEntryLimit(RME_BACKUP_DOCUMENTS_PATH, limits.maximumMetadataEntryBytes)
        requireEntryLimit(RME_BACKUP_PAGES_PATH, limits.maximumMetadataEntryBytes)
        requireEntryLimit(RME_BACKUP_SOURCE_ASSETS_PATH, limits.maximumMetadataEntryBytes)
        requireEntryLimit(RME_BACKUP_OCR_DOCUMENT_STATES_PATH, limits.maximumOcrMetadataEntryBytes)
        requireEntryLimit(RME_BACKUP_OCR_PAGE_STATES_PATH, limits.maximumOcrMetadataEntryBytes)
        requireEntryLimit(RME_BACKUP_OCR_ARTIFACTS_PATH, limits.maximumOcrMetadataEntryBytes)
        requireEntryLimit(RME_BACKUP_OCR_CORRECTIONS_PATH, limits.maximumOcrMetadataEntryBytes)
        val ocr = BackupOcrManifest(
            documentStatesPath = RME_BACKUP_OCR_DOCUMENT_STATES_PATH,
            pageStatesPath = RME_BACKUP_OCR_PAGE_STATES_PATH,
            artifactsPath = RME_BACKUP_OCR_ARTIFACTS_PATH,
            correctionsPath = RME_BACKUP_OCR_CORRECTIONS_PATH,
            documentStateCount = documentStateCount,
            pageStateCount = pageStateCount,
            artifactCount = artifactCount,
            correctionCount = correctionCount,
            lineCount = lineCount,
            lineByteLength = lineBytes,
            lineChunks = lineChunks.asList(),
        )
        val summary = BackupSummary(
            folderCount = folderCount,
            documentCount = documentCount,
            pageCount = pageCount,
            sourceAssetCount = sourceAssetCount,
            contentByteLength = contentBytes,
        )
        val metadataBytes = addExact(
            outputs.values.fold(0L) { total, value -> addExact(total, value.byteCount) },
            lineBytes,
        )
        val spoolBytes = addExact(
            metadataBytes,
            addExact(pageIndexFile.length(), addExact(sourceIndexFile.length(), lineChunkIndexFile.length())),
        )
        val prepared = FilePreparedBackupArchiveSource(spoolRoot, summary, ocr, pageIndexFile, sourceIndexFile)
        val stats = LibraryBackupSnapshotStats(
            summary = summary,
            ocrManifest = ocr,
            serializedMetadataBytes = metadataBytes,
            spoolBytes = spoolBytes,
            zipEntryCount = addExact(
                10L,
                addExact(pageCount.toLong(), addExact(sourceAssetCount.toLong(), lineChunks.count.toLong())),
            ),
        )
        val parent = requireNotNull(spoolRoot.parentFile)
        return SpoolLibraryBackupSnapshot(prepared, stats) { deleteSpoolDirectory(parent, spoolRoot) }
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching(::finishLineChunk)
            closeOutputs()
        }
    }

    inner class OcrCorrectionAppender internal constructor(
        private val destination: CountingOutputStream,
        private val startByteCount: Long,
    ) {
        private var lineCount = 0
        private var finished = false

        fun append(record: BackupOcrLineCorrectionRecord) {
            check(!finished && correctionOpen)
            if (lineCount >= limits.maximumOcrLinesPerArtifact || record.lineOrdinal != lineCount) {
                throw IOException("An OCR correction line sequence is invalid.")
            }
            val encoded = buildString {
                if (lineCount > 0) append(',')
                append('{')
                append("\"lineOrdinal\":").append(record.lineOrdinal)
                append(",\"correctedText\":").append(jsonString(record.correctedText))
                append('}')
            }.toByteArray(StandardCharsets.UTF_8)
            destination.write(encoded)
            lineCount += 1
            requireCorrectionBounds(destination, startByteCount)
        }

        fun finish() {
            check(!finished && correctionOpen)
            finished = true
            destination.write("]}\n".toByteArray(StandardCharsets.UTF_8))
            requireCorrectionBounds(destination, startByteCount)
            correctionOpen = false
        }
    }

    private fun output(path: String): OutputStream = requireNotNull(outputs[path])

    private fun openEntry(path: String): CountingOutputStream {
        val file = File(entriesRoot, path)
        val parent = requireNotNull(file.parentFile)
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) throw IOException("Backup spool path failed.")
        return CountingOutputStream(BufferedOutputStream(FileOutputStream(file, false)))
    }

    private fun openLineChunk(): CountingOutputStream {
        val maximumChunkCount = limits.maximumManifestBytes / MINIMUM_LINE_CHUNK_DESCRIPTOR_BYTES
        if (lineChunks.count >= maximumChunkCount) {
            throw IOException("The OCR line chunk manifest exceeds its bounded spool limit.")
        }
        val path = "$RME_BACKUP_OCR_LINES_DIRECTORY/${lineChunks.count.toString().padStart(6, '0')}.jsonl"
        return openEntry(path)
    }

    private fun finishLineChunk() {
        val output = lineOutput ?: return
        output.close()
        lineChunks.append(lineChunkRecords, output.byteCount)
        lineOutput = null
        lineChunkRecords = 0
    }

    private fun closeOutputs() {
        var failure: Throwable? = null
        fun close(resource: AutoCloseable) {
            try {
                resource.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure?.addSuppressed(closeFailure)
            }
        }
        close(pageAssets)
        close(sourceAssets)
        close(lineChunks)
        outputs.values.forEach(::close)
        failure?.let { throw it }
    }

    private fun requireEntryLimit(path: String, maximum: Int) {
        if (requireNotNull(outputs[path]).byteCount > maximum) throw IOException("A backup metadata entry is too large.")
    }

    private fun requireCorrectionBounds(destination: CountingOutputStream, startByteCount: Long) {
        if (destination.byteCount - startByteCount > limits.maximumJsonLineBytes ||
            destination.byteCount > limits.maximumOcrMetadataEntryBytes
        ) {
            throw IOException("An OCR correction exceeds the backup spool limit.")
        }
    }

    private fun requireCount(actual: Int, maximum: Int) {
        if (actual > maximum) throw IOException("A backup record count exceeds its limit.")
    }
}

internal class CountingOutputStream(destination: OutputStream) : FilterOutputStream(destination) {
    var byteCount: Long = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        byteCount = addExact(byteCount, 1L)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        byteCount = addExact(byteCount, length.toLong())
    }
}

private fun addExact(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (failure: ArithmeticException) {
    throw IOException("A backup byte count overflowed.", failure)
}

private const val MINIMUM_LINE_CHUNK_DESCRIPTOR_BYTES = 48
