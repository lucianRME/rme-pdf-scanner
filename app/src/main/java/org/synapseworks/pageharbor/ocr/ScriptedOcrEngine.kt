package org.synapseworks.pageharbor.ocr

import kotlinx.coroutines.runBlocking

/** Bridges the Phase 3A page router to existing ordered in-memory OCR consumers. */
class ScriptedOcrEngine(
    private val engine: OcrPageRecognitionEngine,
    private val script: OcrScript,
) : OcrEngine {
    override fun recognize(pages: List<OcrPage>): OcrResult = runBlocking {
        OcrResult(
            pages.mapIndexed { index, page ->
                val descriptor = OcrPageRecognitionDescriptor(
                    address = OcrPageAddress("transient", "page-$index"),
                    capturedPagePosition = index,
                    script = script,
                    currentness = OcrRecognitionCurrentness(
                        inputFingerprintVersion = 1,
                        inputFingerprint = "transient:$index:${script.stableId}",
                        sessionDocumentRevision = 0L,
                    ),
                )
                when (val outcome = engine.recognize(OcrPageRecognitionRequest(descriptor, page))) {
                    is OcrPageRecognitionOutcome.Success -> OcrPageResult(
                        pageIndex = index,
                        text = outcome.rawText,
                        layout = outcome.layout,
                    )
                    is OcrPageRecognitionOutcome.Failure -> OcrPageResult(
                        pageIndex = index,
                        text = "",
                        error = if (outcome.reason == OcrFailureReason.IMAGE_UNREADABLE) {
                            OcrPageError.IMAGE_UNREADABLE
                        } else {
                            OcrPageError.RECOGNITION_FAILED
                        },
                    )
                }
            },
        )
    }
}
