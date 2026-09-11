package org.synapseworks.pageharbor.document.session

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDocumentResourceTest {
    @Test
    fun openableColumnSizeTakesPriorityOverDescriptorLength() {
        assertEquals(42L, firstKnownSourceByteCount(42L, 84L))
    }

    @Test
    fun descriptorLengthIsUsedWhenOpenableSizeIsUnknown() {
        assertEquals(84L, firstKnownSourceByteCount(null, 84L))
        assertEquals(84L, firstKnownSourceByteCount(-1L, 84L))
    }

    @Test
    fun unavailableProviderSizesRemainUnknown() {
        assertEquals(null, firstKnownSourceByteCount(null, -1L))
    }

    @Test
    fun declaredZeroLengthIsPreservedForTypedMetadataValidation() {
        assertEquals(0L, firstKnownSourceByteCount(0L, 84L))
    }
}
