package org.synapseworks.pageharbor.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryModelsTest {
    @Test
    fun searchQueryUsesBoundedQuotedUnicodePrefixes() {
        assertEquals("factura* AND 2026*", " Factura, 2026! ".toFtsPrefixQuery())
        assertEquals("café*", "café".toFtsPrefixQuery())
        assertNull("---".toFtsPrefixQuery())
    }

    @Test
    fun namesCollapseWhitespaceAndRespectStorageBounds() {
        assertEquals("Quarterly report", normalizeLibraryTitle("  Quarterly   report  "))
        assertEquals("Client files", normalizeFolderName(" Client\nfiles "))
        assertEquals(MAX_LIBRARY_TITLE_LENGTH, normalizeLibraryTitle("x".repeat(200)).length)
        assertEquals(MAX_FOLDER_NAME_LENGTH, normalizeFolderName("y".repeat(200)).length)
    }
}
