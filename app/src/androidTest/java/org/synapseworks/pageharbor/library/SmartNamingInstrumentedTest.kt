package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartNamingInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "smart-naming-test")
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        root.deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.libraryDao()
        repository = LibraryRepository(context, dao, LibraryFileStore(context, root))
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun correctedOcrSupersedesRawAndRevertRestoresLatestRawSuggestion() = runBlocking {
        storeDocument("corrected", "Document 1")
        commitRaw("corrected", "V0daf0ne\nInvoice\nSeptember 2026")
        assertEquals(
            "V0daf0ne Invoice — Sep 2026",
            repository.suggestDocumentName("corrected").successValue()?.name,
        )

        val correctedSnapshot = requireNotNull(dao.ocrPageSnapshot("corrected", "corrected-page"))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.saveOcrCorrection(
                correctedSnapshot,
                LibraryOcrCorrectionDraft(
                    correctedText = "Vodafone\nInvoice\nSeptember 2026",
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
                2,
            ),
        )
        assertEquals(
            "Vodafone Invoice — Sep 2026",
            repository.suggestDocumentName("corrected").successValue()?.name,
        )

        val revertSnapshot = requireNotNull(dao.ocrPageSnapshot("corrected", "corrected-page"))
        assertEquals(LibraryOcrCommitResult.APPLIED, dao.revertOcrCorrection(revertSnapshot, 3))
        assertEquals(
            "V0daf0ne Invoice — Sep 2026",
            repository.suggestDocumentName("corrected").successValue()?.name,
        )
    }

    @Test
    fun reOcrNeverChangesConfirmedTitleOrKeepsNagging() = runBlocking {
        storeDocument("rerun", "Scan 2026-09-27")
        commitRaw("rerun", "Tesco\nReceipt\n25 September 2026")
        val suggested = requireNotNull(repository.suggestDocumentName("rerun").successValue())
        assertEquals("Tesco Receipt — 25 Sep 2026", suggested.name)
        assertEquals(LibraryResult.Success(Unit), repository.renameDocument("rerun", suggested.name))

        commitRaw("rerun", "Vodafone\nInvoice\nOctober 2026", recognizedAt = 4)

        assertEquals(suggested.name, requireNotNull(dao.document("rerun")).title)
        assertNull(repository.suggestDocumentName("rerun").successValue())
    }

    @Test
    fun noOcrKeepsNormalManualRenameAvailable() = runBlocking {
        storeDocument("no-ocr", "Document 2")

        assertNull(repository.suggestDocumentName("no-ocr").successValue())
        assertEquals(LibraryResult.Success(Unit), repository.renameDocument("no-ocr", "Manual title"))
        assertEquals("Manual title", requireNotNull(dao.document("no-ocr")).title)
    }

    @Test
    fun multilingualEffectiveOcrProducesLocalUnicodeSafeSuggestions() = runBlocking {
        val cases = mapOf(
            "english" to ("Acme\nInvoice\n25 September 2026" to "Acme Invoice — 25 Sep 2026"),
            "romanian" to ("Electrica\nFactură\n25 septembrie 2026" to "Electrica Factură — 25 Sep 2026"),
            "german" to ("Allianz\nVersicherung\n25 September 2026" to "Allianz Versicherung — 25 Sep 2026"),
            "french" to ("Orange\nFacture\n25 septembre 2026" to "Orange Facture — 25 Sep 2026"),
            "italian" to ("Enel\nFattura\n25 settembre 2026" to "Enel Fattura — 25 Sep 2026"),
            "spanish" to ("Iberdrola\nFactura\n25 septiembre 2026" to "Iberdrola Factura — 25 Sep 2026"),
            "chinese" to ("樱花株式会社\n2026-09-25" to "樱花株式会社 — 25 Sep 2026"),
            "japanese" to ("株式会社サクラ\n2026-09-25" to "株式会社サクラ — 25 Sep 2026"),
            "korean" to ("한빛회사\n2026-09-25" to "한빛회사 — 25 Sep 2026"),
            "devanagari" to ("भारत संस्था\n2026-09-25" to "भारत संस्था — 25 Sep 2026"),
        )

        cases.forEach { (id, values) ->
            storeDocument(id, "Document 3")
            commitRaw(id, values.first)
            assertEquals(id, values.second, repository.suggestDocumentName(id).successValue()?.name)
        }
    }

    @Test
    fun batchRecognizedOcrMakesSuggestionAvailableWithoutAutomaticRename() = runBlocking {
        storeDocument("batch", "Document 4")

        commitRaw("batch", "Tesco\nReceipt\n25 September 2026")

        assertEquals("Document 4", requireNotNull(dao.document("batch")).title)
        assertEquals(
            "Tesco Receipt — 25 Sep 2026",
            repository.suggestDocumentName("batch").successValue()?.name,
        )
    }

    private suspend fun storeDocument(documentId: String, title: String) {
        dao.replaceDocument(
            LibraryDocumentEntity(
                documentId = documentId,
                title = title,
                createdAtMillis = 1,
                modifiedAtMillis = 1,
                pageCount = 1,
                folderId = null,
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
            ),
            listOf(
                LibraryPageEntity(
                    pageId = "$documentId-page",
                    documentId = documentId,
                    position = 0,
                    relativePath = "$documentId.jpg",
                    contentType = "image/jpeg",
                    sourceCategory = "TEST",
                    width = 20,
                    height = 20,
                    sourceByteCount = 1,
                    rotationDegrees = 0,
                    filterName = "ORIGINAL",
                    ocrText = null,
                    ocrError = null,
                    contentSha256 = documentId.padEnd(64, '0').take(64),
                ),
            ),
            "",
        )
    }

    private suspend fun commitRaw(documentId: String, text: String, recognizedAt: Long = 1) {
        val snapshot = requireNotNull(dao.ocrPageSnapshot(documentId, "$documentId-page"))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            dao.commitOcrArtifact(
                snapshot,
                LibraryOcrArtifactDraft(
                    inputFingerprintVersion = 1,
                    inputFingerprint = "fingerprint-$recognizedAt-$documentId",
                    contentSha256 = requireNotNull(snapshot.contentSha256),
                    rotationDegrees = snapshot.rotationDegrees,
                    filterName = snapshot.filterName,
                    uprightWidth = 20,
                    uprightHeight = 20,
                    coordinateSystemVersion = 1,
                    transformVersion = 1,
                    actualScript = "LATIN",
                    recognizerId = "smart-naming-test",
                    pipelineVersion = "test-v1",
                    clientVersion = null,
                    delivery = "BUNDLED",
                    recognizedAtMillis = recognizedAt,
                    rawText = text,
                ),
                recognizedAt,
            ),
        )
    }

    private fun <T> LibraryResult<T>.successValue(): T = (this as LibraryResult.Success<T>).value
}
