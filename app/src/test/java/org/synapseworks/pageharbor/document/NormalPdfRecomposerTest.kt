package org.synapseworks.pageharbor.document

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NormalPdfRecomposerTest {
    private lateinit var cacheDirectory: File
    private lateinit var normalPdfDirectory: File
    private val nowMillis = 2 * 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        cacheDirectory = Files.createTempDirectory("pageharbor-cache-").toFile()
        normalPdfDirectory = File(cacheDirectory, "normal-pdfs").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun removesOnlyStaleOwnedRecompositionPdfAndVisualFiles() {
        val stalePdf = file("normal-stale.pdf", 24L * 60L * 60L * 1000L)
        val staleVisual = file("normal-visual-stale.jpg", 24L * 60L * 60L * 1000L)
        val freshPdf = file("normal-fresh.pdf", 1L)
        val unrelated = file("other.pdf", 24L * 60L * 60L * 1000L)

        deleteStaleNormalPdfs(cacheDirectory, nowMillis)

        assertFalse(stalePdf.exists())
        assertFalse(staleVisual.exists())
        assertTrue(freshPdf.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun oversizedPageGenerationFailureRemainsTypedDuringNormalPdfRecomposition() {
        assertEquals(
            NormalPdfRecompositionResult.SourceTooLarge,
            normalPdfResultForGenerationFailure(
                org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationError
                    .PAGE_IMAGE_TOO_LARGE,
            ),
        )
    }

    private fun file(name: String, ageMillis: Long): File = File(normalPdfDirectory, name).apply {
        writeText("test")
        setLastModified(nowMillis - ageMillis)
    }
}
