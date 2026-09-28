package org.synapseworks.pageharbor.ui.portability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScannerMigrationSourceTest {
    @Test
    fun guidedSourcesRetainExistingOrderAndAddMicrosoftLens() {
        assertEquals(
            listOf(
                "CamScanner",
                "Adobe Scan / Acrobat",
                "Genius Scan",
                "Microsoft Lens",
                "Other scanner",
            ),
            ScannerMigrationSource.entries.map { it.displayName },
        )
    }

    @Test
    fun microsoftLensGuidanceDescribesOnlyGenericShareAndFilePickerRoutes() {
        val guidance = ScannerMigrationSource.MICROSOFT_LENS.guidance

        assertEquals(
            "Export or share your existing scans from Microsoft Lens as PDF, then choose RME. " +
                "Choose exported PDFs using Android's file picker.",
            guidance,
        )
        assertFalse(guidance.contains("account", ignoreCase = true))
        assertFalse(guidance.contains("OneDrive API", ignoreCase = true))
    }
}
