package org.synapseworks.pageharbor.ocr

/** Stable document/page identity supplied by the owning session or persistence adapter. */
data class OcrPageAddress(
    val documentId: String,
    val pageId: String,
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(pageId.isNotBlank()) { "pageId must not be blank" }
    }
}

/** Durable revision values captured from persistence without depending on Room-facing models. */
data class OcrDurablePageCurrentness(
    val documentContentRevision: Long,
    val pageVisualRevision: Long,
    val ocrStateRevision: Long,
    val activeArtifactRevision: Long?,
) {
    init {
        require(documentContentRevision >= 0L)
        require(pageVisualRevision >= 0L)
        require(ocrStateRevision >= 0L)
        require(activeArtifactRevision == null || activeArtifactRevision > 0L)
    }
}

/**
 * Immutable input identity captured before recognition starts.
 *
 * A live workspace supplies [sessionDocumentRevision]. Reconstructed persisted work supplies
 * [durable]. Saved-document work can supply both. At least one revision boundary is required.
 */
data class OcrRecognitionCurrentness(
    val inputFingerprintVersion: Int,
    val inputFingerprint: String,
    val sessionDocumentRevision: Long? = null,
    val documentPageOrderFingerprint: String? = null,
    val durable: OcrDurablePageCurrentness? = null,
) {
    init {
        require(inputFingerprintVersion > 0) { "inputFingerprintVersion must be positive" }
        require(inputFingerprint.isNotBlank()) { "inputFingerprint must not be blank" }
        require(sessionDocumentRevision == null || sessionDocumentRevision >= 0L)
        require(documentPageOrderFingerprint == null || documentPageOrderFingerprint.isNotBlank())
        require(sessionDocumentRevision != null || durable != null) {
            "At least one currentness boundary is required"
        }
    }
}

/** Persistable/reconstructable metadata for exactly one page-recognition attempt. */
data class OcrPageRecognitionDescriptor(
    val address: OcrPageAddress,
    val capturedPagePosition: Int,
    val script: OcrScript,
    val currentness: OcrRecognitionCurrentness,
) {
    init {
        require(capturedPagePosition >= 0) { "capturedPagePosition must not be negative" }
    }
}

/** One descriptor plus an operation-scoped local source; the source itself is never persisted. */
data class OcrPageRecognitionRequest(
    val descriptor: OcrPageRecognitionDescriptor,
    val source: OcrPage,
)

enum class OcrModelDelivery {
    BUNDLED,
    PLAY_SERVICES,
}

/** Provenance exposed by an engine without inventing an opaque vendor model revision. */
data class OcrRecognizerProvenance(
    val actualScript: OcrScript,
    val recognizerId: String,
    val pipelineVersion: String,
    val clientVersion: String? = null,
    val delivery: OcrModelDelivery,
) {
    init {
        require(recognizerId.isNotBlank()) { "recognizerId must not be blank" }
        require(pipelineVersion.isNotBlank()) { "pipelineVersion must not be blank" }
        require(clientVersion == null || clientVersion.isNotBlank()) {
            "clientVersion must be null or non-blank"
        }
    }
}

/** Safe typed failures; exception messages, source details, and recognized content are excluded. */
enum class OcrFailureReason {
    IMAGE_UNREADABLE,
    MODEL_UNAVAILABLE,
    GOOGLE_PLAY_SERVICES_UNAVAILABLE,
    SCRIPT_UNSUPPORTED,
    RECOGNITION_FAILED,
    STALE_INPUT,
    CANCELLED,
    PERSISTENCE_FAILED,
}

sealed interface OcrPageRecognitionOutcome {
    val descriptor: OcrPageRecognitionDescriptor

    data class Success(
        override val descriptor: OcrPageRecognitionDescriptor,
        val rawText: String,
        val layout: OcrPageLayout?,
        val provenance: OcrRecognizerProvenance,
    ) : OcrPageRecognitionOutcome

    data class Failure(
        override val descriptor: OcrPageRecognitionDescriptor,
        val reason: OcrFailureReason,
    ) : OcrPageRecognitionOutcome
}

/** Page-oriented engine boundary shared by manual, reconstructed, and durable batch work. */
fun interface OcrPageRecognitionEngine {
    suspend fun recognize(request: OcrPageRecognitionRequest): OcrPageRecognitionOutcome
}

/** Exact comparison used before publishing or attempting a persistence compare-and-set. */
fun OcrPageRecognitionOutcome.isCurrentFor(
    address: OcrPageAddress,
    currentness: OcrRecognitionCurrentness,
): Boolean = descriptor.address == address && descriptor.currentness == currentness
