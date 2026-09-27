package org.synapseworks.pageharbor.ocr.persistence

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.synapseworks.pageharbor.library.LibraryOcrPageOutcomeDraft
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.OcrDurablePageCurrentness
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageAddress
import org.synapseworks.pageharbor.ocr.OcrPageError
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionDescriptor
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionRequest
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrRecognitionCurrentness
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript

data class ScriptedOcrSessionResult(
    val result: OcrResult,
    /** Null means recognition was not safe to persist; existing durable OCR must remain unchanged. */
    val persistenceOutcomes: List<LibraryOcrPageOutcomeDraft>?,
)

data class ScriptedOcrPageResult(
    val outcome: OcrPageRecognitionOutcome,
    val persistence: OcrPagePersistenceMapping?,
)

/** One exact-script path for scanned, imported, reopened, and first-save session pages. */
class ScriptedOcrSessionRunner(private val engine: OcrPageRecognitionEngine) {
    suspend fun recognizePage(
        page: OcrPage,
        script: OcrScript,
        pagePosition: Int,
        sessionDocumentRevision: Long? = null,
        savedSnapshot: LibraryOcrPageSnapshot? = null,
        recognizedAtMillis: Long,
    ): ScriptedOcrPageResult {
        require(sessionDocumentRevision != null || savedSnapshot != null)
        val descriptor = descriptor(
            index = pagePosition,
            script = script,
            sessionRevision = sessionDocumentRevision,
            snapshot = savedSnapshot,
        )
        val outcome = engine.recognize(OcrPageRecognitionRequest(descriptor, page))
        return ScriptedOcrPageResult(
            outcome = outcome,
            persistence = savedSnapshot?.let { expected ->
                OcrPageRecognitionPersistenceMapper.map(
                    result = outcome,
                    expected = expected,
                    recognizedAtMillis = recognizedAtMillis,
                )
            },
        )
    }

    suspend fun recognize(
        pages: List<OcrPage>,
        script: OcrScript,
        sessionDocumentRevision: Long,
        savedSnapshots: List<LibraryOcrPageSnapshot>? = null,
        recognizedAtMillis: Long,
    ): ScriptedOcrSessionResult {
        require(savedSnapshots == null || savedSnapshots.size == pages.size)
        val mapped = ArrayList<LibraryOcrPageOutcomeDraft>(pages.size)
        var mappingsAreComplete = savedSnapshots != null
        val pageResults = pages.mapIndexed { index, page ->
            val snapshot = savedSnapshots?.get(index)
            val pageResult = recognizePage(
                page = page,
                script = script,
                pagePosition = index,
                sessionDocumentRevision = sessionDocumentRevision,
                savedSnapshot = snapshot,
                recognizedAtMillis = recognizedAtMillis,
            )
            if (snapshot != null) {
                when (val persistence = pageResult.persistence) {
                    is OcrPagePersistenceMapping.Ready -> mapped += persistence.outcome
                    OcrPagePersistenceMapping.NoChange,
                    OcrPagePersistenceMapping.Stale,
                    OcrPagePersistenceMapping.InvalidResult,
                    null,
                    -> mappingsAreComplete = false
                }
            }
            pageResult.outcome.toLegacyResult(index)
        }
        return ScriptedOcrSessionResult(
            result = OcrResult(pageResults),
            persistenceOutcomes = mapped.takeIf { mappingsAreComplete && it.size == pages.size },
        )
    }

    private fun descriptor(
        index: Int,
        script: OcrScript,
        sessionRevision: Long?,
        snapshot: LibraryOcrPageSnapshot?,
    ): OcrPageRecognitionDescriptor {
        val address = if (snapshot == null) {
            OcrPageAddress("active-session", "page-$index")
        } else {
            OcrPageAddress(snapshot.documentId, snapshot.pageId)
        }
        return OcrPageRecognitionDescriptor(
            address = address,
            capturedPagePosition = index,
            script = script,
            currentness = OcrRecognitionCurrentness(
                inputFingerprintVersion = 1,
                inputFingerprint = inputFingerprint(snapshot, index, script),
                sessionDocumentRevision = sessionRevision,
                durable = snapshot?.let {
                    OcrDurablePageCurrentness(
                        documentContentRevision = it.documentContentRevision,
                        pageVisualRevision = it.pageVisualRevision,
                        ocrStateRevision = it.ocrStateRevision,
                        activeArtifactRevision = it.activeArtifactRevision,
                    )
                },
            ),
        )
    }

    private fun inputFingerprint(
        snapshot: LibraryOcrPageSnapshot?,
        index: Int,
        script: OcrScript,
    ): String {
        val input = listOf(
            "RME-OCR-SCRIPTED-INPUT-V1",
            snapshot?.contentSha256.orEmpty(),
            snapshot?.pageVisualRevision?.toString().orEmpty(),
            snapshot?.rotationDegrees?.toString().orEmpty(),
            snapshot?.filterName.orEmpty(),
            index.toString(),
            script.stableId,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun OcrPageRecognitionOutcome.toLegacyResult(index: Int): OcrPageResult = when (this) {
        is OcrPageRecognitionOutcome.Success -> OcrPageResult(
            pageIndex = index,
            text = rawText,
            layout = layout,
        )
        is OcrPageRecognitionOutcome.Failure -> OcrPageResult(
            pageIndex = index,
            text = "",
            error = if (reason == OcrFailureReason.IMAGE_UNREADABLE) {
                OcrPageError.IMAGE_UNREADABLE
            } else {
                OcrPageError.RECOGNITION_FAILED
            },
        )
    }
}
