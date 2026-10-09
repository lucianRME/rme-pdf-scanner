package org.synapseworks.pageharbor.ui.cardcopy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAcquisition
import org.synapseworks.pageharbor.document.cardcopy.CardCopySide
import org.synapseworks.pageharbor.document.cardcopy.CardCopySource
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

class CardCopyPreviewStateTest {
    @Test
    fun previewResetKeyChangesForReplacementAndRotation() {
        val original = source("front-a")
        val replacement = source("front-b")
        val rotated = original.copy(rotation = DocumentPageRotation.DEGREES_90)

        assertEquals(cardPreviewResetKey(original), cardPreviewResetKey(original.copy()))
        assertNotEquals(cardPreviewResetKey(original), cardPreviewResetKey(replacement))
        assertNotEquals(cardPreviewResetKey(original), cardPreviewResetKey(rotated))
    }

    private fun source(sourceId: String) = CardCopySource(
        side = CardCopySide.FRONT,
        acquisition = CardCopyAcquisition.IMPORT,
        file = File("$sourceId.jpg"),
        ownedDirectory = File("$sourceId-dir"),
        metadata = DocumentImageMetadata(sourceByteCount = 1L, width = 320, height = 200),
        sourceId = sourceId,
        sourceSha256 = "0".repeat(64),
    )
}
