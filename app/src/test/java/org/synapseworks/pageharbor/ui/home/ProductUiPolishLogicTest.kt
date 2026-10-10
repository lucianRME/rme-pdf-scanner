package org.synapseworks.pageharbor.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductUiPolishLogicTest {
    @Test
    fun oneSelectedDocumentPrioritizesExtractText() {
        assertEquals(SelectionPrimaryAction.EXTRACT_TEXT, selectionPrimaryAction(1))
    }

    @Test
    fun multipleSelectedDocumentsPrioritizeMerge() {
        assertEquals(SelectionPrimaryAction.MERGE, selectionPrimaryAction(2))
        assertEquals(SelectionPrimaryAction.MERGE, selectionPrimaryAction(12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectionActionRejectsEmptySelection() {
        selectionPrimaryAction(0)
    }

    @Test
    fun previewZoomClampsScaleAndPanToViewport() {
        val transformed = updateDocumentPreviewTransform(
            current = DocumentPreviewTransform(),
            zoomChange = 10f,
            panX = 10_000f,
            panY = -10_000f,
            viewportWidth = 400,
            viewportHeight = 800,
        )

        assertEquals(DOCUMENT_PREVIEW_MAX_SCALE, transformed.scale)
        assertEquals(600f, transformed.offsetX)
        assertEquals(-1_200f, transformed.offsetY)
    }

    @Test
    fun previewReturnsToFitAndClearsPanAtMinimumScale() {
        val transformed = updateDocumentPreviewTransform(
            current = DocumentPreviewTransform(scale = 2f, offsetX = 80f, offsetY = 120f),
            zoomChange = 0.1f,
            panX = 50f,
            panY = 50f,
            viewportWidth = 400,
            viewportHeight = 800,
        )

        assertEquals(DocumentPreviewTransform(), transformed)
    }

    @Test
    fun verySmallFittedAxisDoesNotPanOffViewportWhileZoomed() {
        val content = fittedDocumentPreviewContentSize(
            viewportWidth = 400,
            viewportHeight = 800,
            imageWidth = 1_000,
            imageHeight = 100,
        )

        val transformed = updateDocumentPreviewTransform(
            current = DocumentPreviewTransform(),
            zoomChange = 4f,
            panX = 10_000f,
            panY = 10_000f,
            viewportWidth = 400,
            viewportHeight = 800,
            contentWidth = content.width,
            contentHeight = content.height,
        )

        assertEquals(4f, transformed.scale)
        assertEquals(600f, transformed.offsetX)
        assertEquals(0f, transformed.offsetY)
    }

    @Test
    fun fittedPreviewScaleIsIndependentFromBitmapPixelDimensions() {
        assertEquals(
            DocumentPreviewContentSize(width = 400f, height = 40f),
            fittedDocumentPreviewContentSize(400, 800, 1_000, 100),
        )
        assertEquals(
            DocumentPreviewContentSize(width = 400f, height = 40f),
            fittedDocumentPreviewContentSize(400, 800, 100, 10),
        )
    }

    @Test
    fun doubleTapTogglesBetweenFitAndInspectionZoom() {
        val zoomed = toggleDocumentPreviewZoom(DocumentPreviewTransform())
        assertEquals(2f, zoomed.scale)
        assertEquals(DocumentPreviewTransform(), toggleDocumentPreviewZoom(zoomed))
    }
}
