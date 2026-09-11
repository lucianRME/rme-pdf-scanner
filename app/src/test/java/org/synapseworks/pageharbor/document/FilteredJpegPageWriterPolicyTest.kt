package org.synapseworks.pageharbor.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentInputLimits

class FilteredJpegPageWriterPolicyTest {
    private val limits = DocumentInputLimits(
        maxSourceBytes = 100,
        maxDecodedPixelCount = 2_000,
        maxImageDimension = 100,
    )

    @Test
    fun exactByteDimensionAndPixelLimitsAreAccepted() {
        assertNull(
            transformedImagePreflight(
                metadata = DocumentImageMetadata(sourceByteCount = 100),
                decodedWidth = 40,
                decodedHeight = 50,
                limits = limits,
            ),
        )
    }

    @Test
    fun oversizedKnownSourceFailsBeforeBoundsDecode() {
        assertEquals(
            PageExportResult.SourceTooLarge,
            transformedImagePreflight(
                metadata = DocumentImageMetadata(sourceByteCount = 101),
                limits = limits,
            ),
        )
    }

    @Test
    fun oversizedDecodedBoundsFailBeforeBitmapAllocation() {
        assertEquals(
            PageExportResult.SourceTooLarge,
            transformedImagePreflight(
                metadata = DocumentImageMetadata(),
                decodedWidth = 50,
                decodedHeight = 41,
                limits = limits,
            ),
        )
        assertEquals(
            PageExportResult.SourceTooLarge,
            transformedImagePreflight(
                metadata = DocumentImageMetadata(),
                decodedWidth = 101,
                decodedHeight = 1,
                limits = limits,
            ),
        )
    }

    @Test
    fun invalidMetadataReturnsTypedSourceFailure() {
        assertEquals(
            PageExportResult.SourceMissing,
            transformedImagePreflight(
                metadata = DocumentImageMetadata(sourceByteCount = 0),
                limits = limits,
            ),
        )
        assertEquals(
            PageExportResult.SourceMissing,
            transformedImagePreflight(
                metadata = DocumentImageMetadata(),
                decodedWidth = 0,
                decodedHeight = 50,
                limits = limits,
            ),
        )
    }

    @Test
    fun commonCameraPageUsesTwoBitmapsAndOnlyOneScanlineBuffer() {
        val workingSet = transformedImageWorkingSet(width = 4_032, height = 3_024)

        assertEquals(2, workingSet.maxConcurrentFullResolutionBitmaps)
        assertEquals(0, workingSet.fullPageArgbArrays)
        assertEquals(4_032, workingSet.scanlineBufferPixels)
        assertEquals(97_559_296L, workingSet.estimatedPeakArgbBytes)
    }
}
