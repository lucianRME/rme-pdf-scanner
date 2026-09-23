package org.synapseworks.pageharbor.portability.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibraryResult

@RunWith(AndroidJUnit4::class)
class RoomPortableExportPdfSourceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val inputRoot = File(context.cacheDir, "portable-export-source-tests")
    private val libraryRoot = File(
        File(context.filesDir, LibraryFileStore.LIBRARY_DIRECTORY),
        "portable-export-tests",
    )
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var source: RoomPortableExportPdfSource

    @Before
    fun setUp() {
        inputRoot.mkdirs()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val fileStore = LibraryFileStore(context, libraryRoot)
        repository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = fileStore,
        )
        source = RoomPortableExportPdfSource(context, database.libraryDao(), fileStore)
    }

    @After
    fun tearDown() {
        database.close()
        inputRoot.deleteRecursively()
        libraryRoot.deleteRecursively()
    }

    @Test
    fun exactOriginalIsCopiedButEditedRevisionIsRecomposedAndTemporaryPdfIsDeleted() = runBlocking {
        val page = pageFixture()
        val directBytes = "%PDF-1.4\nexact retained source\n%%EOF".encodeToByteArray()
        val directFile = File(inputRoot, "original.pdf").apply { writeBytes(directBytes) }
        val saved = repository.saveSession(
            session = DocumentSession(
                pages = listOf(page),
                directPdfSource = DocumentResource(
                    reference = Uri.fromFile(directFile).toString(),
                    ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
                ),
                directPdfPageIds = listOf(page.id),
            ),
            title = "Portable",
        ).successValue()

        val directOutput = ByteArrayOutputStream()
        assertEquals(
            PortableExportPdfWriteResult.WRITTEN,
            source.writePdf(saved.id, directOutput),
        )
        assertTrue(directBytes.contentEquals(directOutput.toByteArray()))

        val opened = repository.openDocument(saved.id).successValue().session
        val edited = requireNotNull(opened.rotateClockwise(opened.pages.single().id))
        repository.saveSession(edited, "Portable").successValue()
        assertFalse(database.libraryDao().sourceAssets(saved.id).single().matchesCurrentRevision)
        val temporaryFilesBefore = normalPdfTemporaryFiles()

        val recomposedOutput = ByteArrayOutputStream()
        assertEquals(
            PortableExportPdfWriteResult.WRITTEN,
            source.writePdf(saved.id, recomposedOutput),
        )

        val recomposedBytes = recomposedOutput.toByteArray()
        assertFalse(directBytes.contentEquals(recomposedBytes))
        PDDocument.load(recomposedBytes).use { pdf -> assertEquals(1, pdf.numberOfPages) }
        assertEquals(temporaryFilesBefore, normalPdfTemporaryFiles())
    }

    private fun pageFixture(): DocumentPage {
        val file = File(inputRoot, "page.jpg")
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xffeeeeee.toInt())
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        return DocumentPage(
            id = DocumentPageId(1),
            source = DocumentResource(
                reference = Uri.fromFile(file).toString(),
                ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
            ),
            sourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
            imageMetadata = DocumentImageMetadata(file.length(), 64, 96),
        )
    }

    private fun normalPdfTemporaryFiles(): Set<String> =
        File(context.cacheDir, "normal-pdfs").listFiles().orEmpty()
            .filter(File::isFile)
            .mapTo(mutableSetOf(), File::getName)

    private fun <T> LibraryResult<T>.successValue(): T {
        assertTrue("Expected success but was $this", this is LibraryResult.Success)
        return (this as LibraryResult.Success).value
    }
}
