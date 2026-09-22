package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.backup.engine.FileLibraryBackupWorkspace
import org.synapseworks.pageharbor.backup.engine.LibraryBackupClock
import org.synapseworks.pageharbor.backup.engine.LibraryBackupCreationResult
import org.synapseworks.pageharbor.backup.engine.LibraryBackupEngine
import org.synapseworks.pageharbor.backup.engine.LibraryBackupIdSource
import org.synapseworks.pageharbor.backup.engine.LibraryBackupStoragePreflight
import org.synapseworks.pageharbor.backup.engine.RoomLibraryBackupSnapshotSource
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.publish.BackupPublicationDestination
import org.synapseworks.pageharbor.backup.publish.BackupPublicationEngine
import org.synapseworks.pageharbor.backup.publish.BackupPublicationResult
import org.synapseworks.pageharbor.backup.publish.PublishedBackupKind
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryFolderEntity
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryPageSource
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetSource
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage
import org.synapseworks.pageharbor.portability.export.PortableExportPdfWriteResult
import org.synapseworks.pageharbor.portability.export.RoomPortableExportPdfSource

@RunWith(AndroidJUnit4::class)
class PortableBackupRestoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun plainAndEncryptedBackupsRestoreEquivalentRealRoomLibraries() = runBlocking {
        val runId = UUID.randomUUID().toString()
        val testRoot = File(context.filesDir, "document-library/portable-round-trip-$runId")
        val workRoot = File(context.cacheDir, "portable-round-trip-$runId")
        val source = LibraryRig.create(context, File(testRoot, "source"), "source-$runId.db")
        val plain = LibraryRig.create(context, File(testRoot, "plain"), "plain-$runId.db")
        val encrypted = LibraryRig.create(context, File(testRoot, "encrypted"), "encrypted-$runId.db")
        val password = "portable-test-password-ș".toCharArray()

        try {
            seedSourceLibrary(source)
            val expected = logicalSnapshot(source)

            val backupResult = LibraryBackupEngine(
                snapshotSource = RoomLibraryBackupSnapshotSource(source.dao, source.fileStore),
                workspace = FileLibraryBackupWorkspace(File(workRoot, "backup-workspace")),
                operationGate = LibraryOperationGate(),
                storagePreflight = LibraryBackupStoragePreflight { true },
                clock = LibraryBackupClock { BACKUP_CREATED_AT },
                idSource = LibraryBackupIdSource { BACKUP_ID },
            ).create(
                BackupProducer(
                    applicationId = context.packageName,
                    versionName = "1.5.0",
                    versionCode = 16,
                ),
            )
            val artifact = (backupResult as LibraryBackupCreationResult.Verified).artifact
            try {
                assertEquals(2, artifact.manifest.summary.documentCount)
                assertEquals(28, artifact.manifest.summary.pageCount)
                assertEquals(2, artifact.manifest.summary.folderCount)
                assertEquals(1, artifact.manifest.summary.sourceAssetCount)

                val plainFile = File(workRoot, "published/rme-backup.zip")
                val encryptedFile = File(workRoot, "published/rme-backup.rmebackup")
                val publication = BackupPublicationEngine()
                val plainPublished = publication.publishUnencrypted(
                    artifact,
                    FileBackupDestination(plainFile),
                ) as BackupPublicationResult.Verified
                val encryptedPublished = publication.publishEncrypted(
                    artifact,
                    FileBackupDestination(encryptedFile),
                    password,
                ) as BackupPublicationResult.Verified

                assertEquals(PublishedBackupKind.UNENCRYPTED_ZIP, plainPublished.backup.kind)
                assertEquals(PublishedBackupKind.ENCRYPTED_ENVELOPE_V1, encryptedPublished.backup.kind)
                assertEquals(artifact.manifest, plainPublished.backup.manifest)
                assertEquals(artifact.manifest, encryptedPublished.backup.manifest)
                assertTrue(plainFile.isFile && plainFile.length() > 0L)
                assertTrue(encryptedFile.isFile && encryptedFile.length() > 0L)
                assertFalse(encryptedFile.readBytes().containsSubsequence(OCR_SENTINEL.toByteArray()))

                restoreAndAssert(
                    rig = plain,
                    backup = plainFile,
                    password = null,
                    expectedKind = RestoreInputKind.ZIP,
                    expected = expected,
                    workRoot = File(workRoot, "plain-restore"),
                    idSeed = 100,
                )
                restoreAndAssert(
                    rig = encrypted,
                    backup = encryptedFile,
                    password = password,
                    expectedKind = RestoreInputKind.ENCRYPTED,
                    expected = expected,
                    workRoot = File(workRoot, "encrypted-restore"),
                    idSeed = 500,
                )
            } finally {
                artifact.close()
            }
        } finally {
            password.fill('\u0000')
            source.close()
            plain.close()
            encrypted.close()
            testRoot.deleteRecursively()
            workRoot.deleteRecursively()
        }
    }

    private suspend fun restoreAndAssert(
        rig: LibraryRig,
        backup: File,
        password: CharArray?,
        expectedKind: RestoreInputKind,
        expected: LogicalLibrary,
        workRoot: File,
        idSeed: Int,
    ) {
        assertTrue(rig.dao.activeDocumentsPage(-1L, 10).isEmpty())
        assertTrue(rig.dao.foldersPage(0, 10).isEmpty())
        val reference = RestoreSafReference("test-backup")
        val engine = LibraryRestoreEngine(
            store = RoomRestoreLibraryStore(
                context = context,
                dao = rig.dao,
                fileStore = rig.fileStore,
                clock = RestoreClock { RESTORED_AT },
            ),
            stagingWorkspace = FileRestoreStagingWorkspace(File(workRoot, "verified-staging")),
            operationGate = LibraryOperationGate(),
            storagePreflight = RestoreStoragePreflight { true },
            idSource = SequentialRestoreIdSource(idSeed),
            clock = RestoreClock { RESTORED_AT },
        )
        val coordinator = AndroidRestoreCoordinator(
            engine = engine,
            safAccess = FileRestoreSafAccess(reference, backup),
            archiveWorkspace = FileRestoreArchiveWorkspace(File(workRoot, "archive-staging")),
            testing = Unit,
        )

        assertEquals(
            RestoreSourceInspection.Supported(expectedKind, password != null),
            coordinator.inspect(reference),
        )
        val preparation = coordinator.prepare(reference, password)
        val prepared = (preparation as RestoreCoordinatorPrepareResult.Ready).prepared
        assertEquals(2, prepared.preview.documentCount)
        assertEquals(28, prepared.preview.pageCount)
        assertEquals(2, prepared.preview.folderCount)
        assertEquals(1, prepared.preview.sourceAssetCount)

        val result = coordinator.restore(prepared, RestoreMergePolicy.MERGE_IMPORT_ANYWAY)

        result as RestoreResult.Completed
        assertEquals(2, result.importedDocumentCount)
        assertEquals(0, result.skippedExactDocumentCount)
        assertTrue(result.stagingCleanupSucceeded)
        assertEquals(expected, logicalSnapshot(rig))
        assertDerivedStateAndExport(rig, workRoot)
        assertTrue(File(workRoot, "verified-staging").listFiles().isNullOrEmpty())
        assertTrue(File(workRoot, "archive-staging").listFiles().isNullOrEmpty())
    }

    private suspend fun assertDerivedStateAndExport(rig: LibraryRig, workRoot: File) {
        val repository = LibraryRepository(context, rig.dao, rig.fileStore)
        val search = requireNotNull(repository.observeSearch("quartz")).first()
        assertEquals(listOf(LONG_DOCUMENT_TITLE), search.map { it.title })

        val documents = rig.dao.activeDocumentsPage(-1L, 10)
        assertEquals(2, documents.size)
        documents.forEach { document ->
            val thumbnail = requireNotNull(
                rig.fileStore.resolve(requireNotNull(document.thumbnailRelativePath)),
            )
            assertTrue(thumbnail.isFile && thumbnail.length() > 0L)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(thumbnail.path, bounds)
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
        }

        val longDocument = documents.single { it.title == LONG_DOCUMENT_TITLE }
        val source = rig.dao.sourceAssets(longDocument.documentId).single()
        val sourceFile = requireNotNull(rig.fileStore.resolve(source.relativePath))
        assertTrue(sourceFile.isFile && sourceFile.length() == source.byteCount)
        assertTrue(sourceFile.inputStream().use { input ->
            val signature = ByteArray(5)
            input.read(signature) == signature.size && signature.contentEquals("%PDF-".toByteArray())
        })
        assertEquals(LONG_PAGE_COUNT, pdfPageCount(sourceFile))

        val export = ByteArrayOutputStream()
        assertEquals(
            PortableExportPdfWriteResult.WRITTEN,
            RoomPortableExportPdfSource(context, rig.dao, rig.fileStore)
                .writePdf(longDocument.documentId, export),
        )
        val exportedBytes = export.toByteArray()
        assertTrue(exportedBytes.size > 5)
        assertArrayEquals("%PDF-".toByteArray(), exportedBytes.copyOfRange(0, 5))
        val exportedFile = File(workRoot, "ordinary-export.pdf").apply { writeBytes(exportedBytes) }
        assertEquals(LONG_PAGE_COUNT, pdfPageCount(exportedFile))
    }

    private suspend fun seedSourceLibrary(rig: LibraryRig) {
        rig.dao.insertFolder(
            LibraryFolderEntity(
                folderId = ROOT_FOLDER_ID,
                name = "Archive",
                normalizedName = "archive",
                createdAtMillis = ROOT_CREATED_AT,
                modifiedAtMillis = ROOT_MODIFIED_AT,
            ),
        )
        rig.dao.insertFolder(
            LibraryFolderEntity(
                folderId = CHILD_FOLDER_ID,
                name = "2026",
                normalizedName = "2026",
                createdAtMillis = CHILD_CREATED_AT,
                modifiedAtMillis = CHILD_MODIFIED_AT,
                parentFolderId = ROOT_FOLDER_ID,
            ),
        )

        val originalPdf = File(rig.fileStore.root.parentFile, "synthetic-original.pdf")
        writePdf(originalPdf, LONG_PAGE_COUNT)
        seedDocument(
            rig = rig,
            documentId = LONG_DOCUMENT_ID,
            title = LONG_DOCUMENT_TITLE,
            folderId = CHILD_FOLDER_ID,
            createdAt = LONG_CREATED_AT,
            modifiedAt = LONG_MODIFIED_AT,
            pageCount = LONG_PAGE_COUNT,
            sourcePdf = originalPdf,
        )
        seedDocument(
            rig = rig,
            documentId = SHORT_DOCUMENT_ID,
            title = SHORT_DOCUMENT_TITLE,
            folderId = ROOT_FOLDER_ID,
            createdAt = SHORT_CREATED_AT,
            modifiedAt = SHORT_MODIFIED_AT,
            pageCount = SHORT_PAGE_COUNT,
            sourcePdf = null,
        )
        originalPdf.delete()
    }

    private suspend fun seedDocument(
        rig: LibraryRig,
        documentId: String,
        title: String,
        folderId: String,
        createdAt: Long,
        modifiedAt: Long,
        pageCount: Int,
        sourcePdf: File?,
    ) {
        val pageBytes = List(pageCount) { index -> pngPage(index, documentId == LONG_DOCUMENT_ID) }
        val pageSources = pageBytes.mapIndexed { index, bytes ->
            val rotation = when (index % 4) {
                1 -> DocumentPageRotation.DEGREES_90
                2 -> DocumentPageRotation.DEGREES_180
                3 -> DocumentPageRotation.DEGREES_270
                else -> DocumentPageRotation.DEGREES_0
            }
            val filter = when (index % 4) {
                1 -> DocumentFilter.GRAYSCALE
                2 -> DocumentFilter.BLACK_AND_WHITE
                3 -> DocumentFilter.AUTO_ENHANCE
                else -> DocumentFilter.ORIGINAL
            }
            LibraryPageSource(
                persistentId = "$documentId-page-${index.toString().padStart(2, '0')}",
                contentType = "image/png",
                sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE.name,
                imageMetadata = DocumentImageMetadata(
                    sourceByteCount = bytes.size.toLong(),
                    width = PAGE_WIDTH,
                    height = PAGE_HEIGHT,
                ),
                rotation = rotation,
                filter = filter,
                ocrText = when {
                    documentId == LONG_DOCUMENT_ID && index == 0 -> OCR_SENTINEL
                    documentId == LONG_DOCUMENT_ID && index == pageCount - 1 -> "Final archived page"
                    documentId == SHORT_DOCUMENT_ID && index == 1 -> "Receipt total 42.00"
                    else -> null
                },
                ocrError = null,
                openStream = { ByteArrayInputStream(bytes) },
            )
        }
        val sourceAssets = sourcePdf?.let { pdf ->
            listOf(
                LibrarySourceAssetSource(
                    role = "ORIGINAL_DOCUMENT",
                    contentType = "application/pdf",
                    sourceModifiedAtMillis = ORIGINAL_SOURCE_MODIFIED_AT,
                    matchesCurrentRevision = false,
                    openStream = { FileInputStream(pdf) },
                ),
            )
        }.orEmpty()
        val prepared = when (
            val result = rig.fileStore.prepareRevision(documentId, pageSources, sourceAssets)
        ) {
            is LibraryResult.Success -> result.value
            is LibraryResult.Failure -> error("Unable to seed $title: ${result.reason}")
        }
        val pages = prepared.pages.map { page ->
            LibraryPageEntity(
                pageId = page.pageId,
                documentId = documentId,
                position = page.position,
                relativePath = page.relativePath,
                contentType = page.contentType,
                sourceCategory = page.sourceCategory,
                width = page.imageMetadata.width,
                height = page.imageMetadata.height,
                sourceByteCount = page.imageMetadata.sourceByteCount,
                rotationDegrees = page.rotation.degrees,
                filterName = page.filter.name,
                ocrText = page.ocrText,
                ocrError = page.ocrError,
                contentSha256 = page.contentSha256,
            )
        }
        val fingerprint = DocumentFingerprintV1.calculate(
            pages.map { page ->
                FingerprintPage(
                    assetSha256 = requireNotNull(page.contentSha256),
                    mimeType = page.contentType,
                    byteLength = requireNotNull(page.sourceByteCount),
                    rotationDegrees = page.rotationDegrees,
                    filterName = page.filterName,
                )
            },
        )
        val storedSources = prepared.sourceAssets.map { source ->
            LibrarySourceAssetEntity(
                assetId = source.assetId,
                documentId = documentId,
                role = source.role,
                relativePath = source.relativePath,
                contentType = source.contentType,
                byteCount = source.byteCount,
                sha256 = source.sha256,
                sourceModifiedAtMillis = source.sourceModifiedAtMillis,
                createdAtMillis = createdAt,
                matchesCurrentRevision = source.matchesCurrentRevision,
            )
        }
        rig.dao.replaceDocument(
            document = LibraryDocumentEntity(
                documentId = documentId,
                title = title,
                createdAtMillis = createdAt,
                modifiedAtMillis = modifiedAt,
                pageCount = pages.size,
                folderId = folderId,
                thumbnailRelativePath = prepared.thumbnailRelativePath,
                ocrStatus = LibraryOcrStatus.INDEXED.name,
                contentHashVersion = fingerprint.version,
                contentSha256 = fingerprint.sha256,
                contentByteCount = pages.sumOf { requireNotNull(it.sourceByteCount) },
                sourceModifiedAtMillis = storedSources.firstOrNull()?.sourceModifiedAtMillis,
                importedAtMillis = createdAt + 1,
            ),
            pages = pages,
            ocrText = pages.joinToString("\n\n") { it.ocrText.orEmpty() },
            sourceAssets = storedSources,
        )
    }

    private suspend fun logicalSnapshot(rig: LibraryRig): LogicalLibrary {
        val folders = rig.dao.foldersPage(0, 100)
        val foldersById = folders.associateBy { it.folderId }
        fun folderPath(folderId: String?): String? {
            if (folderId == null) return null
            val segments = ArrayDeque<String>()
            var current = foldersById[folderId]
            while (current != null) {
                segments.addFirst(current.name)
                current = current.parentFolderId?.let(foldersById::get)
            }
            return segments.joinToString("/")
        }
        val logicalFolders = folders.map { folder ->
            LogicalFolder(
                path = requireNotNull(folderPath(folder.folderId)),
                createdAt = folder.createdAtMillis,
                modifiedAt = folder.modifiedAtMillis,
            )
        }.sortedBy(LogicalFolder::path)
        val documents = rig.dao.activeDocumentsPage(-1L, 100).map { document ->
            val pages = rig.dao.pages(document.documentId).map { page ->
                LogicalPage(
                    position = page.position,
                    mimeType = page.contentType,
                    sha256 = requireNotNull(page.contentSha256),
                    byteLength = requireNotNull(page.sourceByteCount),
                    width = requireNotNull(page.width),
                    height = requireNotNull(page.height),
                    rotation = page.rotationDegrees,
                    filter = page.filterName,
                    ocrText = page.ocrText,
                    ocrError = page.ocrError,
                )
            }
            assertEquals(pages.indices.toList(), pages.map(LogicalPage::position))
            val fingerprint = DocumentFingerprintV1.calculate(
                pages.map { page ->
                    FingerprintPage(
                        assetSha256 = page.sha256,
                        mimeType = page.mimeType,
                        byteLength = page.byteLength,
                        rotationDegrees = page.rotation,
                        filterName = page.filter,
                    )
                },
            )
            assertEquals(document.contentHashVersion, fingerprint.version)
            assertEquals(document.contentSha256, fingerprint.sha256)
            val sources = rig.dao.sourceAssets(document.documentId).map { source ->
                val file = rig.fileStore.resolve(source.relativePath)
                assertTrue(file?.isFile == true && file.length() == source.byteCount)
                LogicalSource(
                    role = source.role,
                    mimeType = source.contentType,
                    sha256 = source.sha256,
                    byteLength = source.byteCount,
                    modifiedAt = source.sourceModifiedAtMillis,
                    matchesCurrentRevision = source.matchesCurrentRevision,
                )
            }.sortedBy(LogicalSource::sha256)
            LogicalDocument(
                title = document.title,
                folderPath = folderPath(document.folderId),
                createdAt = document.createdAtMillis,
                modifiedAt = document.modifiedAtMillis,
                contentHashVersion = requireNotNull(document.contentHashVersion),
                contentSha256 = requireNotNull(document.contentSha256),
                pages = pages,
                sources = sources,
            )
        }.sortedBy(LogicalDocument::title)
        return LogicalLibrary(logicalFolders, documents)
    }

    private fun pngPage(index: Int, longDocument: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(
                Color.rgb(
                    (31 + index * 17) % 255,
                    (83 + index * 29) % 255,
                    (149 + index * 37) % 255,
                ),
            )
            canvas.drawText(
                "${if (longDocument) "Archive" else "Receipt"} ${index + 1}",
                6f,
                28f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 13f
                },
            )
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writePdf(destination: File, pageCount: Int) {
        destination.parentFile?.mkdirs()
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(144, 192, index + 1).create(),
                )
                page.canvas.drawText(
                    "Original source page ${index + 1}",
                    12f,
                    28f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 12f
                    },
                )
                document.finishPage(page)
            }
            FileOutputStream(destination).use(document::writeTo)
        } finally {
            document.close()
        }
        assertTrue(destination.isFile && destination.length() > 0L)
    }

    private fun pdfPageCount(file: File): Int {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
    }

    private data class LibraryRig(
        val database: LibraryDatabase,
        val dao: LibraryDao,
        val fileStore: LibraryFileStore,
        val databaseName: String,
        val context: Context,
    ) {
        fun close() {
            database.close()
            context.deleteDatabase(databaseName)
            fileStore.root.deleteRecursively()
        }

        companion object {
            fun create(context: Context, root: File, databaseName: String): LibraryRig {
                root.deleteRecursively()
                val database = Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName)
                    .build()
                return LibraryRig(
                    database = database,
                    dao = database.libraryDao(),
                    fileStore = LibraryFileStore(context, root),
                    databaseName = databaseName,
                    context = context,
                )
            }
        }
    }

    private class FileBackupDestination(private val file: File) : BackupPublicationDestination {
        override fun openOutput(): OutputStream? {
            file.parentFile?.mkdirs()
            return FileOutputStream(file, false)
        }

        override fun openInput(): InputStream? = file.takeIf(File::isFile)?.let { FileInputStream(it) }

        override fun delete(): Boolean = !file.exists() || file.delete()
    }

    private class FileRestoreSafAccess(
        private val expected: RestoreSafReference,
        private val file: File,
    ) : RestoreSafAccess {
        override fun open(reference: RestoreSafReference): InputStream {
            require(reference == expected)
            return FileInputStream(file)
        }
    }

    private class SequentialRestoreIdSource(start: Int) : RestoreIdSource {
        private var next = start

        override fun newId(): String = "00000000-0000-4000-8000-${(next++).toString().padStart(12, '0')}"
    }

    private data class LogicalLibrary(
        val folders: List<LogicalFolder>,
        val documents: List<LogicalDocument>,
    )

    private data class LogicalFolder(val path: String, val createdAt: Long, val modifiedAt: Long)

    private data class LogicalDocument(
        val title: String,
        val folderPath: String?,
        val createdAt: Long,
        val modifiedAt: Long,
        val contentHashVersion: Int,
        val contentSha256: String,
        val pages: List<LogicalPage>,
        val sources: List<LogicalSource>,
    )

    private data class LogicalPage(
        val position: Int,
        val mimeType: String,
        val sha256: String,
        val byteLength: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val filter: String,
        val ocrText: String?,
        val ocrError: String?,
    )

    private data class LogicalSource(
        val role: String,
        val mimeType: String,
        val sha256: String,
        val byteLength: Long,
        val modifiedAt: Long?,
        val matchesCurrentRevision: Boolean,
    )

    private companion object {
        const val BACKUP_ID = "00000000-0000-4000-8000-000000000901"
        const val BACKUP_CREATED_AT = 1_790_000_000_000L
        const val RESTORED_AT = 1_790_000_100_000L
        const val ROOT_FOLDER_ID = "root-folder"
        const val CHILD_FOLDER_ID = "child-folder"
        const val ROOT_CREATED_AT = 1_700_000_000_000L
        const val ROOT_MODIFIED_AT = 1_700_000_100_000L
        const val CHILD_CREATED_AT = 1_710_000_000_000L
        const val CHILD_MODIFIED_AT = 1_710_000_100_000L
        const val LONG_DOCUMENT_ID = "long-document"
        const val LONG_DOCUMENT_TITLE = "Long archive"
        const val LONG_CREATED_AT = 1_720_000_000_000L
        const val LONG_MODIFIED_AT = 1_720_000_100_000L
        const val LONG_PAGE_COUNT = 25
        const val SHORT_DOCUMENT_ID = "short-document"
        const val SHORT_DOCUMENT_TITLE = "Receipt bundle"
        const val SHORT_CREATED_AT = 1_730_000_000_000L
        const val SHORT_MODIFIED_AT = 1_730_000_100_000L
        const val SHORT_PAGE_COUNT = 3
        const val ORIGINAL_SOURCE_MODIFIED_AT = 1_719_999_900_000L
        const val OCR_SENTINEL = "private quartz archive sentinel"
        const val PAGE_WIDTH = 96
        const val PAGE_HEIGHT = 128
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    return indices.any { start ->
        start + candidate.size <= size && candidate.indices.all { offset ->
            this[start + offset] == candidate[offset]
        }
    }
}
