package org.synapseworks.pageharbor.ocr.review

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.persistence.OcrPagePersistenceMapping
import org.synapseworks.pageharbor.ocr.persistence.ScriptedOcrSessionRunner

data class DocumentOcrRerunTarget(
    val snapshot: LibraryOcrPageSnapshot,
    val source: OcrPage,
    val hadCorrection: Boolean,
)

enum class DocumentOcrRerunFailure {
    MODEL_UNAVAILABLE,
    RECOGNITION_FAILED,
    STALE,
    DELETED,
    PERSISTENCE_FAILED,
}

data class DocumentOcrRerunPageResult(
    val pageId: String,
    val succeeded: Boolean,
    val correctionPreserved: Boolean = false,
    val failure: DocumentOcrRerunFailure? = null,
)

data class DocumentOcrRerunProgress(
    val completedPages: Int,
    val totalPages: Int,
    val succeededPages: Int,
    val failedPages: Int,
)

data class DocumentOcrRerunResult(val pages: List<DocumentOcrRerunPageResult>) {
    val succeededPages: Int get() = pages.count(DocumentOcrRerunPageResult::succeeded)
    val failedPages: Int get() = pages.size - succeededPages
    val preservedCorrections: Int get() = pages.count(DocumentOcrRerunPageResult::correctionPreserved)
}

fun interface OcrArtifactCommitter {
    suspend fun commit(
        expected: LibraryOcrPageSnapshot,
        artifact: LibraryOcrArtifactDraft,
    ): LibraryOcrCommitResult
}

/**
 * Incremental, page-bounded OCR refresh for one document. Each successful page is committed with
 * the Phase 2 compare-and-set contract; a failed, cancelled, stale, or deleted page never replaces
 * its last valid artifact.
 */
class DocumentOcrRerunCoordinator(
    engine: OcrPageRecognitionEngine,
    private val committer: OcrArtifactCommitter,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val runner = ScriptedOcrSessionRunner(engine)

    suspend fun run(
        targets: List<DocumentOcrRerunTarget>,
        script: OcrScript,
        onProgress: (DocumentOcrRerunProgress) -> Unit = {},
    ): DocumentOcrRerunResult {
        require(targets.isNotEmpty())
        require(targets.map { it.snapshot.documentId }.distinct().size == 1)
        require(targets.map { it.snapshot.pageId }.distinct().size == targets.size)

        val results = ArrayList<DocumentOcrRerunPageResult>(targets.size)
        targets.forEach { target ->
            currentCoroutineContext().ensureActive()
            val recognition = runner.recognizePage(
                page = target.source,
                script = script,
                pagePosition = target.snapshot.pagePosition,
                savedSnapshot = target.snapshot,
                recognizedAtMillis = nowMillis(),
            )
            currentCoroutineContext().ensureActive()
            val pageResult = when (val persistence = recognition.persistence) {
                is OcrPagePersistenceMapping.Ready -> {
                    val artifact = persistence.outcome.artifact
                    if (artifact == null) {
                        recognitionFailure(target, recognition.outcome)
                    } else {
                        when (committer.commit(target.snapshot, artifact)) {
                            LibraryOcrCommitResult.APPLIED -> DocumentOcrRerunPageResult(
                                pageId = target.snapshot.pageId,
                                succeeded = true,
                                correctionPreserved = target.hadCorrection,
                            )
                            LibraryOcrCommitResult.STALE -> failed(
                                target,
                                DocumentOcrRerunFailure.STALE,
                            )
                            LibraryOcrCommitResult.NOT_FOUND -> failed(
                                target,
                                DocumentOcrRerunFailure.DELETED,
                            )
                        }
                    }
                }
                OcrPagePersistenceMapping.Stale -> failed(target, DocumentOcrRerunFailure.STALE)
                OcrPagePersistenceMapping.NoChange,
                OcrPagePersistenceMapping.InvalidResult,
                null,
                -> recognitionFailure(target, recognition.outcome)
            }
            results += pageResult
            onProgress(
                DocumentOcrRerunProgress(
                    completedPages = results.size,
                    totalPages = targets.size,
                    succeededPages = results.count(DocumentOcrRerunPageResult::succeeded),
                    failedPages = results.count { !it.succeeded },
                ),
            )
        }
        return DocumentOcrRerunResult(results)
    }

    private fun recognitionFailure(
        target: DocumentOcrRerunTarget,
        outcome: OcrPageRecognitionOutcome,
    ): DocumentOcrRerunPageResult = failed(
        target,
        when ((outcome as? OcrPageRecognitionOutcome.Failure)?.reason) {
            OcrFailureReason.MODEL_UNAVAILABLE,
            OcrFailureReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
            OcrFailureReason.SCRIPT_UNSUPPORTED,
            -> DocumentOcrRerunFailure.MODEL_UNAVAILABLE
            OcrFailureReason.STALE_INPUT -> DocumentOcrRerunFailure.STALE
            OcrFailureReason.PERSISTENCE_FAILED -> DocumentOcrRerunFailure.PERSISTENCE_FAILED
            OcrFailureReason.IMAGE_UNREADABLE,
            OcrFailureReason.RECOGNITION_FAILED,
            OcrFailureReason.CANCELLED,
            null,
            -> DocumentOcrRerunFailure.RECOGNITION_FAILED
        },
    )

    private fun failed(
        target: DocumentOcrRerunTarget,
        reason: DocumentOcrRerunFailure,
    ) = DocumentOcrRerunPageResult(
        pageId = target.snapshot.pageId,
        succeeded = false,
        failure = reason,
    )
}
