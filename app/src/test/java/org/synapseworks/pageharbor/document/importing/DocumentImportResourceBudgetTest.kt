package org.synapseworks.pageharbor.document.importing

import org.junit.Assert.*
import org.junit.Test

class DocumentImportResourceBudgetTest {
    @Test fun storageReserveHandlesExactBoundaryAndOverflowWithoutAllocations() {
        assertTrue(hasImportStorage(IMPORT_STORAGE_RESERVE_BYTES + 1024L, 1024L))
        assertFalse(hasImportStorage(IMPORT_STORAGE_RESERVE_BYTES + 1023L, 1024L))
        assertFalse(hasImportStorage(-1, 0))
        assertFalse(hasImportStorage(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(hasImportStorage(Long.MAX_VALUE, -1))
    }
    @Test fun metadataAdmissionScalesWithResourcesRatherThanAFixedPageCount() {
        assertTrue(hasImportMetadataMemory(64L * 1024 * 1024, 1000))
        assertFalse(hasImportMetadataMemory(16L * 1024 * 1024, 1000))
        assertTrue(hasImportMetadataMemory(1024L * 1024 * 1024, 20_000))
        assertFalse(hasImportMetadataMemory(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(hasImportMetadataMemory(-1, 1))
        assertFalse(hasImportMetadataMemory(1024, -1))
    }
    @Test fun cameraImageValidationFitsSmallHeapsAndPreservesReserve() {
        val mib = 1024L * 1024
        assertEquals(8, importValidationSampleSize(8000, 6000, 48 * mib))
        assertEquals(4, importValidationSampleSize(4096, 3072, 22 * mib))
        assertEquals(1, importValidationSampleSize(800, 600, 16 * mib))
        assertEquals(2, importValidationSampleSize(1001, 1001, 8 * mib + 4_000_000))
        assertNull(importValidationSampleSize(8000, 6000, 8 * mib))
        assertNull(importValidationSampleSize(0, 10, 48 * mib))
    }
}
