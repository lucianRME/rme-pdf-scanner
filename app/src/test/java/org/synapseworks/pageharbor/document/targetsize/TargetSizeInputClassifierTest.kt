package org.synapseworks.pageharbor.document.targetsize

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetSizeInputClassifierTest {
    @Test
    fun classifiesOrdinaryImageDocument() {
        assertEquals(
            TargetSizeInputClass.IMAGE_DOCUMENT_SUPPORTED,
            TargetSizeInputClassifier.classify(TargetSizeInputFacts(pageCount = 2)).inputClass,
        )
    }

    @Test
    fun classifiesImageOnlyAndMixedReconstructablePdfsSeparately() {
        val imagePdf = TargetSizePdfSourceFacts(
            pageCount = 2,
            allPagesContainRasterImage = true,
            hasTextOrVectorContent = false,
        )
        val mixedPdf = imagePdf.copy(hasTextOrVectorContent = true)

        assertEquals(
            TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED,
            TargetSizeInputClassifier.classify(
                TargetSizeInputFacts(
                    pageCount = 2,
                    originalPdfSources = listOf(imagePdf),
                    containsNonPdfPages = false,
                ),
            ).inputClass,
        )
        assertEquals(
            TargetSizeInputClass.MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION,
            TargetSizeInputClassifier.classify(
                TargetSizeInputFacts(
                    pageCount = 2,
                    originalPdfSources = listOf(mixedPdf),
                    containsNonPdfPages = false,
                ),
            ).inputClass,
        )
    }

    @Test
    fun rejectsComplexEncryptedMalformedAndIncompleteInputsWithSafeReasons() {
        val imagePdf = TargetSizePdfSourceFacts(1, true, false)
        assertEquals(
            TargetSizeInputClass.ENCRYPTED_PDF,
            classifyPdf(imagePdf.copy(encrypted = true)).inputClass,
        )
        assertEquals(
            TargetSizeInputClass.INVALID_SOURCE,
            classifyPdf(imagePdf.copy(malformed = true)).inputClass,
        )
        assertEquals(
            TargetSizeUnsupportedReason.INTERACTIVE_FORM,
            classifyPdf(imagePdf.copy(hasInteractiveForm = true)).unsupportedReason,
        )
        assertEquals(
            TargetSizeUnsupportedReason.SIGNATURE,
            classifyPdf(imagePdf.copy(hasSignature = true)).unsupportedReason,
        )
        assertEquals(
            TargetSizeUnsupportedReason.ANNOTATION,
            classifyPdf(imagePdf.copy(hasAnnotation = true)).unsupportedReason,
        )
        assertEquals(
            TargetSizeUnsupportedReason.EMBEDDED_FILE,
            classifyPdf(imagePdf.copy(hasEmbeddedFile = true)).unsupportedReason,
        )
        assertEquals(
            TargetSizeUnsupportedReason.VECTOR_OR_NON_IMAGE_PAGE,
            classifyPdf(imagePdf.copy(allPagesContainRasterImage = false)).unsupportedReason,
        )
        assertEquals(
            TargetSizeUnsupportedReason.INCOMPLETE_RECONSTRUCTION,
            TargetSizeInputClassifier.classify(
                TargetSizeInputFacts(
                    pageCount = 1,
                    originalPdfSources = listOf(imagePdf),
                    containsNonPdfPages = false,
                    reconstructionComplete = false,
                ),
            ).unsupportedReason,
        )
    }

    private fun classifyPdf(source: TargetSizePdfSourceFacts): TargetSizeClassification =
        TargetSizeInputClassifier.classify(
            TargetSizeInputFacts(
                pageCount = 1,
                originalPdfSources = listOf(source),
                containsNonPdfPages = false,
            ),
        )
}
