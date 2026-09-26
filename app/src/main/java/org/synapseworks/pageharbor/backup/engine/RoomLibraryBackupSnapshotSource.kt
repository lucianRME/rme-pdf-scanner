package org.synapseworks.pageharbor.backup.engine

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionAlignment
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrInputFingerprint
import org.synapseworks.pageharbor.backup.format.BackupOcrLineCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPoint
import org.synapseworks.pageharbor.backup.format.BackupOcrVerificationState
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryBackupOcrCorrectionLineRow
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity

/** Maps the active Room v3 library to the portable format while its operation gate is held. */
internal class RoomLibraryBackupSnapshotSource(
    private val dao: LibraryDao,
    private val fileStore: LibraryFileStore,
    private val limits: BackupFormatLimits = BackupFormatLimits(),
    private val spoolParent: File = File(
        requireNotNull(fileStore.root.parentFile),
        PRIVATE_BACKUP_SPOOL_DIRECTORY,
    ),
) : LibraryBackupSnapshotSource {
    init {
        recoverStaleBackupSpools(spoolParent)
    }

    override suspend fun capture(): LibraryBackupSnapshot = try {
        captureChecked()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LibraryBackupSnapshotException) {
        throw failure
    } catch (exception: Exception) {
        throw LibraryBackupSnapshotException(
            LibraryBackupSnapshotFailure.DATABASE_UNAVAILABLE,
            "The local library snapshot could not be read.",
            exception,
        )
    }

    private suspend fun captureChecked(): LibraryBackupSnapshot {
        val startingRevision = dao.metadata()?.libraryRevision ?: 0L
        val spoolRoot = createPrivateSpoolDirectory(spoolParent)
        val builder = try {
            LibraryBackupSpoolBuilder(spoolRoot, limits)
        } catch (failure: Throwable) {
            throwAfterBackupCaptureCleanup(failure) {
                deleteSpoolDirectory(spoolParent, spoolRoot)
            }
        }
        try {
            spoolFolders(builder)
            spoolDocuments(builder)
            spoolPages(builder)
            spoolOcrArtifacts(builder)
            spoolOcrLines(builder)
            spoolOcrCorrections(builder)
            spoolSourceAssets(builder)

            val endingRevision = dao.metadata()?.libraryRevision ?: 0L
            if (startingRevision != endingRevision) {
                snapshotFailure(
                    LibraryBackupSnapshotFailure.INCONSISTENT_REVISION,
                    "The local library changed while its backup snapshot was captured.",
                )
            }
            return builder.finish()
        } catch (failure: Throwable) {
            var cleanupFailure: Throwable? = null
            try {
                builder.close()
            } catch (closeFailure: Throwable) {
                cleanupFailure = closeFailure
            }
            throwAfterBackupCaptureCleanup(failure, cleanupFailure) {
                deleteSpoolDirectory(spoolParent, spoolRoot)
            }
        }
    }

    private suspend fun spoolFolders(builder: LibraryBackupSpoolBuilder) {
        var afterFolderId = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.backupFoldersPage(afterFolderId, DATABASE_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { folder ->
                currentCoroutineContext().ensureActive()
                builder.appendFolder(
                    BackupFolderRecord(
                        folderId = folder.folderId,
                        name = folder.name,
                        parentFolderId = folder.parentFolderId,
                        createdAtEpochMillis = folder.createdAtMillis,
                        modifiedAtEpochMillis = folder.modifiedAtMillis,
                    ),
                )
            }
            afterFolderId = page.last().folderId
            if (page.size < DATABASE_PAGE_SIZE) break
        }
    }

    private suspend fun spoolDocuments(builder: LibraryBackupSpoolBuilder) {
        var afterRowId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.activeBackupDocumentsPage(afterRowId, DATABASE_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { row ->
                currentCoroutineContext().ensureActive()
                if (row.document.pageCount != row.actualPageCount) {
                    snapshotFailure(
                        LibraryBackupSnapshotFailure.INVALID_RECORD,
                        "A document page count is inconsistent.",
                    )
                }
                builder.appendDocument(
                    row.document.toBackupRecord(row.actualPageCount, row.actualSourceAssetCount),
                )
                builder.appendOcrDocumentState(
                    BackupOcrDocumentStateRecord(
                        documentId = row.document.documentId,
                        contentRevision = row.document.contentRevision,
                        scriptPreference = row.document.ocrScriptPreference,
                    ),
                )
            }
            afterRowId = page.last().document.rowId
            if (page.size < DATABASE_PAGE_SIZE) break
        }
    }

    private suspend fun spoolPages(builder: LibraryBackupSpoolBuilder) {
        var afterDocumentRowId = -1L
        var afterPagePosition = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = dao.activeBackupPagesPage(
                afterDocumentRowId = afterDocumentRowId,
                afterPagePosition = afterPagePosition,
                limit = OCR_DATABASE_PAGE_SIZE,
            )
            if (rows.isEmpty()) break
            rows.forEach { row ->
                currentCoroutineContext().ensureActive()
                val (record, file) = row.page.toBackupRecord()
                builder.appendPage(record, file)
                builder.appendOcrPageState(
                    BackupOcrPageStateRecord(
                        pageId = row.page.pageId,
                        visualRevision = row.page.visualRevision,
                        ocrStateRevision = row.page.ocrStateRevision,
                        activeArtifactRevision = row.page.activeOcrArtifactRevision,
                    ),
                )
            }
            afterDocumentRowId = rows.last().documentRowId
            afterPagePosition = rows.last().page.position
            if (rows.size < OCR_DATABASE_PAGE_SIZE) break
        }
    }

    private suspend fun spoolOcrArtifacts(builder: LibraryBackupSpoolBuilder) {
        var afterDocumentRowId = -1L
        var afterPagePosition = -1
        var afterArtifactRevision = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.activeBackupOcrArtifactsPage(
                afterDocumentRowId,
                afterPagePosition,
                afterArtifactRevision,
                OCR_ROW_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { row ->
                currentCoroutineContext().ensureActive()
                builder.appendOcrArtifact(row.artifact.toBackupRecord(row.lineCount))
            }
            val last = page.last()
            afterDocumentRowId = last.documentRowId
            afterPagePosition = last.pagePosition
            afterArtifactRevision = last.artifact.artifactRevision
            if (page.size < OCR_ROW_PAGE_SIZE) break
        }
    }

    private suspend fun spoolOcrLines(builder: LibraryBackupSpoolBuilder) {
        var afterDocumentRowId = -1L
        var afterPagePosition = -1
        var afterArtifactRevision = -1L
        var afterLineOrdinal = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.activeBackupOcrLinesPage(
                afterDocumentRowId,
                afterPagePosition,
                afterArtifactRevision,
                afterLineOrdinal,
                OCR_ROW_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { row ->
                currentCoroutineContext().ensureActive()
                builder.appendOcrLine(row.line.toBackupRecord())
            }
            val last = page.last()
            afterDocumentRowId = last.documentRowId
            afterPagePosition = last.pagePosition
            afterArtifactRevision = last.line.artifactRevision
            afterLineOrdinal = last.line.lineOrdinal
            if (page.size < OCR_ROW_PAGE_SIZE) break
        }
    }

    private suspend fun spoolOcrCorrections(builder: LibraryBackupSpoolBuilder) {
        val lines = CorrectionLinePager(dao)
        var afterDocumentRowId = -1L
        var afterPagePosition = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.activeBackupOcrCorrectionsPage(
                afterDocumentRowId,
                afterPagePosition,
                OCR_ROW_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { row ->
                currentCoroutineContext().ensureActive()
                val correction = row.correction
                val correctionKey = OcrCorrectionKey(
                    row.documentRowId,
                    row.pagePosition,
                    correction.baseArtifactRevision,
                )
                val appender = builder.beginOcrCorrection(
                    BackupOcrCorrectionRecord(
                        pageId = correction.pageId,
                        baseArtifactRevision = correction.baseArtifactRevision,
                        correctedText = correction.correctedText,
                        correctedAtEpochMillis = correction.correctedAtMillis,
                        alignment = enumValueOrSnapshotFailure(
                            correction.alignmentState,
                            "OCR correction alignment",
                        ),
                        lineCorrections = emptyList(),
                    ),
                )
                while (true) {
                    val lineRow = lines.peek() ?: break
                    val lineKey = lineRow.correctionKey()
                    if (lineKey < correctionKey) {
                        snapshotFailure(
                            LibraryBackupSnapshotFailure.INVALID_RECORD,
                            "An OCR correction line is orphaned.",
                        )
                    }
                    if (lineKey > correctionKey) break
                    val line = lines.remove().line
                    appender.append(BackupOcrLineCorrectionRecord(line.lineOrdinal, line.correctedText))
                }
                appender.finish()
            }
            val last = page.last()
            afterDocumentRowId = last.documentRowId
            afterPagePosition = last.pagePosition
            if (page.size < OCR_ROW_PAGE_SIZE) break
        }
        if (lines.peek() != null) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "An OCR correction line is orphaned.",
            )
        }
    }

    private suspend fun spoolSourceAssets(builder: LibraryBackupSpoolBuilder) {
        var afterAssetId = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.activeBackupSourceAssetsPage(afterAssetId, DATABASE_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { source ->
                currentCoroutineContext().ensureActive()
                val (record, file) = source.toBackupRecord()
                builder.appendSourceAsset(record, file)
            }
            afterAssetId = page.last().assetId
            if (page.size < DATABASE_PAGE_SIZE) break
        }
    }

    private suspend fun LibraryPageEntity.toBackupRecord(): Pair<BackupPageRecord, File> {
        val file = resolveAsset(relativePath)
        val actualByteLength = file.length()
        if (actualByteLength <= 0L || (sourceByteCount != null && sourceByteCount != actualByteLength)) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A page byte count is inconsistent.",
            )
        }
        val dimensions = when {
            width != null && width > 0 && height != null && height > 0 -> width to height
            else -> measureImage(file)
        }
        val hash = when {
            contentSha256 == null -> hashFile(file)
            SHA_256.matches(contentSha256) -> contentSha256
            else -> snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A page content hash is invalid.",
            )
        }
        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> snapshotFailure(
                LibraryBackupSnapshotFailure.UNSUPPORTED_ASSET,
                "A page content type is unsupported by the portable backup format.",
            )
        }
        val archivePath = "documents/$documentId/pages/${position.toString().padStart(6, '0')}-$pageId.$extension"
        return BackupPageRecord(
            pageId = pageId,
            documentId = documentId,
            position = position,
            relativePath = archivePath,
            mimeType = contentType,
            sha256 = hash,
            byteLength = actualByteLength,
            width = dimensions.first,
            height = dimensions.second,
            rotationDegrees = rotationDegrees,
            filterName = filterName,
            ocrText = ocrText,
            ocrError = ocrError,
            sourcePageIndex = null,
        ) to file
    }

    private fun LibrarySourceAssetEntity.toBackupRecord(): Pair<BackupSourceAssetRecord, File> {
        if (contentType != "application/pdf") {
            snapshotFailure(
                LibraryBackupSnapshotFailure.UNSUPPORTED_ASSET,
                "A source asset is unsupported by the portable backup format.",
            )
        }
        val file = resolveAsset(relativePath)
        if (byteCount <= 0L || file.length() != byteCount || !SHA_256.matches(sha256)) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A source asset has inconsistent integrity metadata.",
            )
        }
        val archivePath = "documents/$documentId/sources/$assetId.pdf"
        return BackupSourceAssetRecord(
            sourceId = assetId,
            documentId = documentId,
            role = role,
            relativePath = archivePath,
            mimeType = contentType,
            sha256 = sha256,
            byteLength = byteCount,
            sourceModifiedAtEpochMillis = sourceModifiedAtMillis,
            matchesCurrentRevision = matchesCurrentRevision,
        ) to file
    }

    private fun LibraryDocumentEntity.toBackupRecord(
        actualPageCount: Int,
        actualSourceAssetCount: Int,
    ): BackupDocumentRecord = BackupDocumentRecord(
        documentId = documentId,
        folderId = folderId,
        title = title,
        createdAtEpochMillis = createdAtMillis,
        modifiedAtEpochMillis = modifiedAtMillis,
        contentHashVersion = contentHashVersion ?: DEFAULT_CONTENT_HASH_VERSION,
        contentSha256 = contentSha256,
        pageCount = actualPageCount,
        sourceAssetCount = actualSourceAssetCount,
    )

    private fun org.synapseworks.pageharbor.library.LibraryPageOcrArtifactEntity.toBackupRecord(
        lineCount: Int,
    ): BackupOcrArtifactRecord {
        val fingerprintValues = listOf(
            capturedPageVisualRevision,
            inputFingerprintVersion,
            inputFingerprint,
            contentSha256,
            rotationDegrees,
            filterName,
            uprightWidth,
            uprightHeight,
            coordinateSystemVersion,
            transformVersion,
        )
        val fingerprint = when {
            fingerprintValues.all { it == null } -> null
            fingerprintValues.any { it == null } -> snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "An OCR artifact has incomplete fingerprint provenance.",
            )
            else -> BackupOcrInputFingerprint(
                version = requireNotNull(inputFingerprintVersion),
                value = requireNotNull(inputFingerprint),
                contentSha256 = requireNotNull(contentSha256),
                visualRevision = requireNotNull(capturedPageVisualRevision),
                rotationDegrees = requireNotNull(rotationDegrees),
                filterName = requireNotNull(filterName),
                uprightWidth = requireNotNull(uprightWidth),
                uprightHeight = requireNotNull(uprightHeight),
                coordinateSystemVersion = requireNotNull(coordinateSystemVersion),
                transformVersion = requireNotNull(transformVersion),
            )
        }
        return BackupOcrArtifactRecord(
            pageId = pageId,
            artifactRevision = artifactRevision,
            capturedPageVisualRevision = capturedPageVisualRevision,
            capturedDocumentContentRevision = capturedDocumentContentRevision,
            verificationState = enumValueOrSnapshotFailure(verificationState, "OCR verification state"),
            inputFingerprint = fingerprint,
            actualScript = actualScript,
            recognizerId = recognizerId,
            pipelineVersion = pipelineVersion,
            clientVersion = clientVersion,
            delivery = delivery,
            recognizedAtEpochMillis = recognizedAtMillis,
            rawText = rawText,
            lineCount = lineCount,
        )
    }

    private fun org.synapseworks.pageharbor.library.LibraryPageOcrLineEntity.toBackupRecord():
        BackupOcrLineRecord = BackupOcrLineRecord(
        pageId = pageId,
        artifactRevision = artifactRevision,
        lineOrdinal = lineOrdinal,
        rawText = rawText,
        cornerPoints = listOf(
            BackupOcrPoint(topLeftX, topLeftY),
            BackupOcrPoint(topRightX, topRightY),
            BackupOcrPoint(bottomRightX, bottomRightY),
            BackupOcrPoint(bottomLeftX, bottomLeftY),
        ),
        baselineStart = BackupOcrPoint(baselineStartX, baselineStartY),
        baselineEnd = BackupOcrPoint(baselineEndX, baselineEndY),
        baselineAngleDegrees = baselineAngleDegrees,
        writingOrientation = writingOrientation,
    )

    private inline fun <reified T : Enum<T>> enumValueOrSnapshotFailure(value: String, field: String): T =
        enumValues<T>().singleOrNull { it.name == value }
            ?: snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A stored $field value is unsupported.",
            )

    private fun resolveAsset(relativePath: String): File =
        fileStore.resolve(relativePath)?.takeIf(File::isFile)
            ?: snapshotFailure(
                LibraryBackupSnapshotFailure.MISSING_ASSET,
                "A library asset required for backup is missing.",
            )

    private fun measureImage(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            snapshotFailure(
                LibraryBackupSnapshotFailure.INVALID_RECORD,
                "A page image has invalid dimensions.",
            )
        }
        return options.outWidth to options.outHeight
    }

    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { source ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun snapshotFailure(
        failure: LibraryBackupSnapshotFailure,
        message: String,
    ): Nothing = throw LibraryBackupSnapshotException(failure, message)

    private data class OcrCorrectionKey(
        val documentRowId: Long,
        val pagePosition: Int,
        val artifactRevision: Long,
    ) : Comparable<OcrCorrectionKey> {
        override fun compareTo(other: OcrCorrectionKey): Int {
            val document = documentRowId.compareTo(other.documentRowId)
            if (document != 0) return document
            val page = pagePosition.compareTo(other.pagePosition)
            return if (page != 0) page else artifactRevision.compareTo(other.artifactRevision)
        }
    }

    private fun LibraryBackupOcrCorrectionLineRow.correctionKey(): OcrCorrectionKey =
        OcrCorrectionKey(documentRowId, pagePosition, line.baseArtifactRevision)

    private class CorrectionLinePager(
        private val dao: LibraryDao,
    ) {
        private var page: List<LibraryBackupOcrCorrectionLineRow> = emptyList()
        private var index = 0
        private var exhausted = false
        private var afterDocumentRowId = -1L
        private var afterPagePosition = -1
        private var afterArtifactRevision = -1L
        private var afterLineOrdinal = -1

        suspend fun peek(): LibraryBackupOcrCorrectionLineRow? {
            if (index >= page.size && !exhausted) loadPage()
            return page.getOrNull(index)
        }

        fun remove(): LibraryBackupOcrCorrectionLineRow =
            page.getOrNull(index++) ?: throw NoSuchElementException()

        private suspend fun loadPage() {
            currentCoroutineContext().ensureActive()
            page = dao.activeBackupOcrCorrectionLinesPage(
                afterDocumentRowId,
                afterPagePosition,
                afterArtifactRevision,
                afterLineOrdinal,
                OCR_ROW_PAGE_SIZE,
            )
            index = 0
            if (page.isEmpty()) {
                exhausted = true
                return
            }
            val last = page.last()
            afterDocumentRowId = last.documentRowId
            afterPagePosition = last.pagePosition
            afterArtifactRevision = last.line.baseArtifactRevision
            afterLineOrdinal = last.line.lineOrdinal
        }
    }

    private companion object {
        const val DATABASE_PAGE_SIZE = 256
        const val OCR_DATABASE_PAGE_SIZE = 32
        const val OCR_ROW_PAGE_SIZE = 32
        const val HASH_BUFFER_SIZE = 32 * 1024
        const val DEFAULT_CONTENT_HASH_VERSION = 1
        const val PRIVATE_BACKUP_SPOOL_DIRECTORY = ".portable-backup-spool"
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

internal fun throwAfterBackupCaptureCleanup(
    failure: Throwable,
    initialCleanupFailure: Throwable? = null,
    cleanup: () -> Boolean,
): Nothing {
    var cleanupFailure = initialCleanupFailure
    var cleaned = false
    repeat(CAPTURE_CLEANUP_ATTEMPTS) {
        if (!cleaned) cleaned = cleanup()
    }
    if (!cleaned) {
        val deletionFailure = IOException("The failed backup snapshot spool could not be removed.")
        if (cleanupFailure == null) cleanupFailure = deletionFailure
        else cleanupFailure.addSuppressed(deletionFailure)
    }
    if (cleanupFailure == null) throw failure
    failure.addSuppressed(cleanupFailure)
    if (failure is CancellationException) throw failure
    val snapshotFailure = failure as? LibraryBackupSnapshotException
    throw LibraryBackupSnapshotException(
        failure = snapshotFailure?.failure ?: LibraryBackupSnapshotFailure.DATABASE_UNAVAILABLE,
        message = snapshotFailure?.message ?: "The local library snapshot could not be read.",
        cause = failure,
        cleanupSucceeded = false,
    )
}

private const val CAPTURE_CLEANUP_ATTEMPTS = 3
