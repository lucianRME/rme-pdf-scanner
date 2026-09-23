package org.synapseworks.pageharbor.portability.export

import org.junit.Assert.assertEquals
import org.junit.Test

class PortableExportNamingTest {
    @Test
    fun `unsafe and trailing characters become a portable name`() {
        assertEquals(
            "Invoice_ 2026",
            PortableExportNaming.safeBaseName("  Invoice:  2026...  "),
        )
    }

    @Test
    fun `blank and reserved names remain usable`() {
        assertEquals("Document", PortableExportNaming.safeBaseName("  ... "))
        assertEquals("_CON", PortableExportNaming.safeBaseName("CON"))
        assertEquals("_lpt1", PortableExportNaming.safeBaseName("lpt1"))
    }

    @Test
    fun `file collisions are deterministic and case insensitive`() {
        val used = mutableSetOf<String>()

        assertEquals("Receipt.pdf", PortableExportNaming.allocateFileName("Receipt", "PDF", used))
        assertEquals("receipt (2).pdf", PortableExportNaming.allocateFileName("receipt", ".pdf", used))
        assertEquals("Receipt (3).pdf", PortableExportNaming.allocateFileName("Receipt", "pdf", used))
    }

    @Test
    fun `folder collisions are deterministic and case insensitive`() {
        val used = mutableSetOf<String>()

        assertEquals("Work", PortableExportNaming.allocateFolderName("Work", used))
        assertEquals("work (2)", PortableExportNaming.allocateFolderName("work", used))
    }
}
