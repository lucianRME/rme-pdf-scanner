package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMultilingualSearchInstrumentedTest {
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun unicode61WithAuxiliaryCjkTermsFindsEverySupportedLanguage() = runBlocking {
        val cases = listOf(
            SearchCase("english", "Insurance certificate invoice date", "insurance"),
            SearchCase("romanian", "Factură cu diacritice și valoare", "factură"),
            SearchCase("german", "Versicherungsbescheinigung für März", "versicherung"),
            SearchCase("french", "Certificat d’assurance et facture", "assurance"),
            SearchCase("italian", "Certificato assicurativo e fattura", "assicurativo"),
            SearchCase("spanish", "Certificado de seguro y factura", "seguro"),
            SearchCase("chinese", "中文测试发票金额", "发票"),
            SearchCase("japanese", "日本語テスト請求書", "請求書"),
            SearchCase("korean", "한국어 테스트 청구서", "청구서"),
            SearchCase("devanagari", "हिन्दी परीक्षण चालान", "चालान"),
        )

        cases.forEachIndexed { index, case ->
            val documentId = "document-${case.id}"
            val pageId = "page-${case.id}"
            dao.replaceDocument(
                LibraryDocumentEntity(
                    documentId = documentId,
                    title = "Language ${case.id}",
                    createdAtMillis = index.toLong(),
                    modifiedAtMillis = index.toLong(),
                    pageCount = 1,
                    folderId = null,
                    thumbnailRelativePath = null,
                    ocrStatus = LibraryOcrStatus.INDEXED.name,
                    libraryState = LibraryDocumentState.ACTIVE.name,
                ),
                listOf(
                    LibraryPageEntity(
                        pageId = pageId,
                        documentId = documentId,
                        position = 0,
                        relativePath = "${case.id}.jpg",
                        contentType = "image/jpeg",
                        sourceCategory = "TEST",
                        width = 100,
                        height = 100,
                        sourceByteCount = 1,
                        rotationDegrees = 0,
                        filterName = "ORIGINAL",
                        ocrText = case.text,
                        ocrError = null,
                        contentSha256 = (index + 1).toString().repeat(64).take(64),
                    ),
                ),
                "",
            )

            val hits = dao.pageSearchPage(
                ftsQuery = requireNotNull(case.query.toFtsPrefixQuery()),
                beforeModifiedAt = Long.MAX_VALUE,
                afterPageId = "",
                limit = 20,
            )

            assertEquals("${case.id}: ${case.query}", pageId, hits.single().pageId)
            assertEquals("${case.id}: current position", 0, hits.single().pagePosition)
            assertEquals("${case.id}: document identity", documentId, hits.single().documentId)
            assertTrue(
                "${case.id}: snippet did not retain useful OCR context: ${hits.single().matchSnippet}",
                hits.single().matchSnippet?.contains(case.query, ignoreCase = true) == true,
            )
        }
    }

    private data class SearchCase(
        val id: String,
        val text: String,
        val query: String,
    )
}
