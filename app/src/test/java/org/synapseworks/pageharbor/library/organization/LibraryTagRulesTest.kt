package org.synapseworks.pageharbor.library.organization

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LibraryTagRulesTest {
    @Test fun normalizationIsUnicodeBoundedAndIndependentOfDeviceLocale() {
        val previous=Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(LibraryTagName("INVOICE Archive", "invoice archive"),LibraryTagRules.name(" \u00a0ＩＮＶＯＩＣＥ\t  Archive\n"))
            assertEquals("é",LibraryTagRules.name("e\u0301")!!.key)
            assertEquals("invoice archive",LibraryTagRules.search("ＩＮＶＯＩＣＥ\u00a0\tArchive"))
            assertEquals("x",LibraryTagRules.name("x")!!.display)
            assertNotEquals(LibraryTagRules.name("Straße")!!.key,LibraryTagRules.name("STRASSE")!!.key)
        } finally { Locale.setDefault(previous) }
    }
    @Test fun invalidNamesFailWithoutTruncationOrSilentCollision() {
        listOf("", "  ", "a".repeat(41),"a".repeat(161),"safe\u0000name","safe\u200dname","\ud800").forEach { assertNull(LibraryTagRules.name(it)) }
        assertNotNull(LibraryTagRules.name("😀".repeat(40)))
        assertNull(LibraryTagRules.name("😀".repeat(41)))
        assertEquals("%x\\%\\_\\\\y%",LibraryTagRules.like("X%_\\Y"))
    }
    @Test fun filterBoundsAreFiniteAndOrderingUsesBoundArguments() {
        assertThrows(IllegalArgumentException::class.java){LibraryOrganizationFilter((1..17).map{it.toString()}.toSet())}
        val q=OrganizationBrowse.query("folder'", "tag%",org.synapseworks.pageharbor.library.LibrarySortOrder.MODIFIED_DESC,LibraryOrganizationFilter(setOf("b","a"),TagMatchMode.ALL,true),30)
        assertFalse(q.sql.contains("folder'"))
        assertTrue(q.sql.contains("COUNT(*)"));assertTrue(q.sql.contains("d.is_favorite=1"))
        assertTrue(q.sql.contains("LIMIT ? OFFSET ?"));assertTrue(q.sql.contains("document_id"))
        assertThrows(IllegalArgumentException::class.java){OrganizationBrowse.query(null,"",org.synapseworks.pageharbor.library.LibrarySortOrder.TITLE_ASC,LibraryOrganizationFilter(),-1)}
    }
}
