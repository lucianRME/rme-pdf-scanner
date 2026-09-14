package org.synapseworks.pageharbor.document.importing

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImportModelsTest {
    @Test
    fun renderSizeUsesPreferredScaleForOrdinaryPdfPages() {
        assertEquals(PdfRenderSize(1_224, 1_584), calculatePdfRenderSize(612, 792))
    }

    @Test
    fun renderSizeBoundsLargePagesByDimensionAndPixelCount() {
        val result = requireNotNull(calculatePdfRenderSize(20_000, 10_000))

        assertTrue(result.width <= MAX_PDF_RENDER_DIMENSION)
        assertTrue(result.height <= MAX_PDF_RENDER_DIMENSION)
        assertTrue(result.width.toLong() * result.height <= MAX_PDF_RENDER_PIXELS)
        assertEquals(2, result.width / result.height)
    }

    @Test
    fun renderSizeRejectsInvalidInputs() {
        assertNull(calculatePdfRenderSize(0, 100))
        assertNull(calculatePdfRenderSize(100, -1))
        assertNull(calculatePdfRenderSize(100, 100, preferredScale = 0f))
    }

    @Test
    fun supportedSignaturesAreDetectedWithoutTrustingFileNames() {
        assertEquals("image/jpeg", sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)))
        assertEquals(
            "image/png",
            sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals(
            "image/webp",
            sniff("RIFF0000WEBP".encodeToByteArray()),
        )
        assertEquals("application/pdf", sniff("%PDF-1.7".encodeToByteArray()))
        assertNull(sniff("not a supported document".encodeToByteArray()))
    }

    @Test
    fun shareTypesAreNarrowAndCaseInsensitive() {
        assertTrue(isSupportedDeclaredShareType("image/*"))
        assertTrue(isSupportedDeclaredShareType("IMAGE/JPEG; charset=binary"))
        assertTrue(isSupportedDeclaredShareType("application/pdf"))
        assertFalse(isSupportedDeclaredShareType("image/heic"))
        assertFalse(isSupportedDeclaredShareType("*/*"))
        assertFalse(isSupportedDeclaredShareType(null))
    }

    private fun sniff(bytes: ByteArray): String? =
        ByteArrayInputStream(bytes).use(::sniffSupportedContentType)
}
