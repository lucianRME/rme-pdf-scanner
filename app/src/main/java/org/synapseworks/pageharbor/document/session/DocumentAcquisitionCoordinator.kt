package org.synapseworks.pageharbor.document.session

const val DEFAULT_MAX_DOCUMENT_PAGES = 20
const val DEFAULT_MAX_IMAGE_SOURCE_BYTES = 64L * 1024L * 1024L
const val DEFAULT_MAX_IMAGE_PIXEL_COUNT = 12_500_000L
const val DEFAULT_MAX_IMAGE_DIMENSION = 6_000

data class DocumentInputLimits(
    val maxPages: Int = DEFAULT_MAX_DOCUMENT_PAGES,
    // 64 MiB rejects unusually large imports before decode when a provider exposes its size.
    val maxSourceBytes: Long = DEFAULT_MAX_IMAGE_SOURCE_BYTES,
    // 12.5 MP admits a 4032x3024 camera page while bounding ARGB/filter working memory.
    val maxDecodedPixelCount: Long = DEFAULT_MAX_IMAGE_PIXEL_COUNT,
    // A 6000 px edge covers common 300 dpi document scans without permitting extreme bitmaps.
    val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION,
    val supportedPageContentTypes: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
    ),
) {
    init {
        require(maxPages > 0) { "maxPages must be positive." }
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxDecodedPixelCount > 0) { "maxDecodedPixelCount must be positive." }
        require(maxImageDimension > 0) { "maxImageDimension must be positive." }
        require(supportedPageContentTypes.isNotEmpty()) {
            "At least one page content type must be supported."
        }
    }
}

val DEFAULT_DOCUMENT_INPUT_LIMITS = DocumentInputLimits()

enum class DocumentAcquisitionMode {
    REPLACE,
    APPEND,
}

@JvmInline
value class DocumentAcquisitionToken internal constructor(val value: Long)

/** Raw resource description supplied at the acquisition boundary before validation. */
data class AcquiredResource(
    val reference: String?,
    val ownership: DocumentResourceOwnership = DocumentResourceOwnership.USER_OR_EXTERNAL,
    val ownedTemporaryFile: OwnedTemporaryFile? = null,
)

data class AcquiredDocumentPage(
    val resource: AcquiredResource,
    val sourceCategory: DocumentSourceCategory,
    val contentType: String,
    val imageMetadata: DocumentImageMetadata = DocumentImageMetadata(),
)

/**
 * A normalized acquisition result. [transientResources] are staging artifacts that are never
 * transferred into the active session and are deleted on every terminal path, including success.
 */
data class DocumentAcquisitionInput(
    val pages: List<AcquiredDocumentPage>,
    val directPdfSource: AcquiredResource? = null,
    val transientResources: List<AcquiredResource> = emptyList(),
)

enum class DocumentAcquisitionError {
    EMPTY_INPUT,
    INVALID_REFERENCE,
    INVALID_OWNERSHIP,
    UNSUPPORTED_CONTENT_TYPE,
    PAGE_LIMIT_EXCEEDED,
    SOURCE_UNAVAILABLE,
    INVALID_IMAGE_METADATA,
    SOURCE_TOO_LARGE,
    IMAGE_DIMENSIONS_EXCEEDED,
    INTERRUPTED,
}

enum class DocumentImageConstraintViolation {
    INVALID_METADATA,
    SOURCE_BYTES_EXCEEDED,
    DIMENSIONS_EXCEEDED,
}

/** Shared pre-allocation policy for acquisition, preview, OCR and transformed export paths. */
fun DocumentInputLimits.imageConstraintViolation(
    metadata: DocumentImageMetadata,
): DocumentImageConstraintViolation? {
    metadata.sourceByteCount?.let { byteCount ->
        if (byteCount <= 0L) return DocumentImageConstraintViolation.INVALID_METADATA
        if (byteCount > maxSourceBytes) {
            return DocumentImageConstraintViolation.SOURCE_BYTES_EXCEEDED
        }
    }

    val width = metadata.width
    val height = metadata.height
    if (width == null && height == null) return null
    if (width == null || height == null || width <= 0 || height <= 0) {
        return DocumentImageConstraintViolation.INVALID_METADATA
    }
    if (
        width > maxImageDimension ||
        height > maxImageDimension ||
        width.toLong() * height.toLong() > maxDecodedPixelCount
    ) {
        return DocumentImageConstraintViolation.DIMENSIONS_EXCEEDED
    }
    return null
}

sealed interface DocumentAcquisitionResult {
    data class Success(val session: DocumentSession) : DocumentAcquisitionResult
    data object Cancelled : DocumentAcquisitionResult
    data class Failure(val reason: DocumentAcquisitionError) : DocumentAcquisitionResult
}

sealed interface PendingResourceRegistrationResult {
    data object Registered : PendingResourceRegistrationResult
    data object StaleToken : PendingResourceRegistrationResult
    data class InvalidResource(val reason: DocumentAcquisitionError) :
        PendingResourceRegistrationResult
}

fun interface DocumentResourceCleaner {
    fun delete(resource: DocumentResource)
}

/**
 * Deletes only one regular file whose canonical path is strictly below its declared private root.
 * External/user resources never reach this cleaner.
 */
class LocalDocumentResourceCleaner : DocumentResourceCleaner {
    override fun delete(resource: DocumentResource) {
        if (resource.ownership != DocumentResourceOwnership.RME_OWNED_TEMPORARY) return
        val identity = resource.ownedTemporaryFile?.canonicalIdentityOrNull() ?: return
        try {
            if (identity.file.isFile) identity.file.delete()
        } catch (_: SecurityException) {
            // Private temporary cleanup is best-effort and never logs document details.
        }
    }
}

/**
 * Single coordination boundary for scan output and future picker/share/PDF-rendered page inputs.
 * It owns validation, stable identities, interruption, and temporary-resource transfer/cleanup.
 */
class DocumentAcquisitionCoordinator(
    val limits: DocumentInputLimits = DEFAULT_DOCUMENT_INPUT_LIMITS,
    private val resourceCleaner: DocumentResourceCleaner = LocalDocumentResourceCleaner(),
) {
    private var nextOperationId = 0L
    private var nextPageId = 0L
    private data class ActiveOperation(
        val token: DocumentAcquisitionToken,
        val mode: DocumentAcquisitionMode,
        val pendingResources: MutableList<AcquiredResource> = mutableListOf(),
    )

    private var activeOperation: ActiveOperation? = null

    fun begin(mode: DocumentAcquisitionMode): DocumentAcquisitionToken? {
        if (activeOperation != null) return null
        return DocumentAcquisitionToken(nextOperationId++).also { token ->
            activeOperation = ActiveOperation(token = token, mode = mode)
        }
    }

    /** Registers adapter-created staging resources as soon as they exist. */
    fun registerPendingResources(
        token: DocumentAcquisitionToken,
        currentSession: DocumentSession,
        resources: List<AcquiredResource>,
    ): PendingResourceRegistrationResult {
        val operation = activeOperation
        if (operation?.token != token) {
            cleanupAcquiredResources(
                resources = resources,
                preserving = operation?.pendingResources.orEmpty(),
                preservingSession = currentSession,
            )
            return PendingResourceRegistrationResult.StaleToken
        }
        resources.firstNotNullOfOrNull(::validatePendingResource)?.let { error ->
            return PendingResourceRegistrationResult.InvalidResource(error)
        }
        operation.pendingResources += resources
        return PendingResourceRegistrationResult.Registered
    }

    fun complete(
        token: DocumentAcquisitionToken,
        currentSession: DocumentSession,
        input: DocumentAcquisitionInput,
        deferReplacedSessionCleanup: Boolean = false,
    ): DocumentAcquisitionResult {
        val operation = activeOperation
        if (operation?.token != token) {
            cleanupInput(
                input,
                preserving = currentSession.ownedResources(),
                preservingAcquired = operation?.pendingResources.orEmpty(),
            )
            return DocumentAcquisitionResult.Failure(DocumentAcquisitionError.INTERRUPTED)
        }
        activeOperation = null

        val error = validate(operation.mode, currentSession, input)
        if (error != null) {
            cleanupInput(
                input,
                preserving = currentSession.ownedResources(),
                additionalResources = operation.pendingResources,
            )
            return DocumentAcquisitionResult.Failure(error)
        }

        val acquiredPages = input.pages.map { acquired ->
            DocumentPage(
                id = DocumentPageId(nextPageId++),
                source = acquired.resource.toDocumentResource(),
                sourceCategory = acquired.sourceCategory,
                contentType = acquired.contentType.lowercase(),
                imageMetadata = acquired.imageMetadata,
            )
        }
        val incomingPdf = input.directPdfSource?.toDocumentResource()
        val canAdoptIncomingPdf = operation.mode == DocumentAcquisitionMode.REPLACE ||
            (currentSession.pages.isEmpty() && currentSession.directPdfSource == null)
        val session = when (operation.mode) {
            DocumentAcquisitionMode.REPLACE -> DocumentSession(
                pages = acquiredPages,
                directPdfSource = incomingPdf,
                directPdfPageIds = if (incomingPdf == null) {
                    emptyList()
                } else {
                    acquiredPages.map(DocumentPage::id)
                },
            )

            DocumentAcquisitionMode.APPEND -> {
                currentSession.copy(
                    pages = currentSession.pages + acquiredPages,
                    directPdfSource = if (canAdoptIncomingPdf) incomingPdf else currentSession.directPdfSource,
                    directPdfPageIds = if (canAdoptIncomingPdf) {
                        acquiredPages.map(DocumentPage::id)
                    } else {
                        currentSession.directPdfPageIds
                    },
                )
            }
        }

        cleanupResources(
            resources = buildList {
                if (operation.mode == DocumentAcquisitionMode.REPLACE && !deferReplacedSessionCleanup) {
                    addAll(currentSession.ownedResources())
                }
                addAll(cleanupCandidates(operation.pendingResources + input.transientResources))
                if (!canAdoptIncomingPdf && incomingPdf != null) add(incomingPdf)
            },
            preserving = session.ownedResources(),
        )
        return DocumentAcquisitionResult.Success(session)
    }

    fun cancel(
        token: DocumentAcquisitionToken,
        currentSession: DocumentSession,
        input: DocumentAcquisitionInput? = null,
    ): DocumentAcquisitionResult {
        val operation = activeOperation
        if (operation?.token != token) {
            input?.let {
                cleanupInput(
                    it,
                    preserving = currentSession.ownedResources(),
                    preservingAcquired = operation?.pendingResources.orEmpty(),
                )
            }
            return DocumentAcquisitionResult.Failure(DocumentAcquisitionError.INTERRUPTED)
        }
        activeOperation = null
        cleanupInput(
            input = input ?: DocumentAcquisitionInput(emptyList()),
            preserving = currentSession.ownedResources(),
            additionalResources = operation.pendingResources,
        )
        return DocumentAcquisitionResult.Cancelled
    }

    fun fail(
        token: DocumentAcquisitionToken,
        currentSession: DocumentSession,
        input: DocumentAcquisitionInput? = null,
        reason: DocumentAcquisitionError = DocumentAcquisitionError.SOURCE_UNAVAILABLE,
    ): DocumentAcquisitionResult {
        val operation = activeOperation
        if (operation?.token != token) {
            input?.let {
                cleanupInput(
                    it,
                    preserving = currentSession.ownedResources(),
                    preservingAcquired = operation?.pendingResources.orEmpty(),
                )
            }
            return DocumentAcquisitionResult.Failure(DocumentAcquisitionError.INTERRUPTED)
        }
        activeOperation = null
        cleanupInput(
            input = input ?: DocumentAcquisitionInput(emptyList()),
            preserving = currentSession.ownedResources(),
            additionalResources = operation.pendingResources,
        )
        return DocumentAcquisitionResult.Failure(reason)
    }

    fun interrupt(
        token: DocumentAcquisitionToken,
        currentSession: DocumentSession,
        input: DocumentAcquisitionInput? = null,
    ): DocumentAcquisitionResult = fail(
        token = token,
        currentSession = currentSession,
        input = input,
        reason = DocumentAcquisitionError.INTERRUPTED,
    )

    /** Ends an invalidated/finished active session and releases only RME-owned temporary files. */
    fun release(session: DocumentSession): DocumentSession {
        val pendingResources = activeOperation?.pendingResources.orEmpty()
        activeOperation = null
        cleanupResources(
            resources = session.ownedResources() + cleanupCandidates(pendingResources),
            preserving = emptyList(),
        )
        return DocumentSession()
    }

    /** Releases detached sessions without disturbing a newer acquisition operation. */
    internal fun releaseDetachedSessions(
        sessions: List<DocumentSession>,
        preservingSession: DocumentSession,
    ) {
        cleanupResources(
            resources = sessions.flatMap(DocumentSession::ownedResources),
            preserving = preservingSession.ownedResources(),
        )
    }

    private fun validate(
        mode: DocumentAcquisitionMode,
        currentSession: DocumentSession,
        input: DocumentAcquisitionInput,
    ): DocumentAcquisitionError? {
        if (input.pages.isEmpty() && input.directPdfSource == null) {
            return DocumentAcquisitionError.EMPTY_INPUT
        }
        val requestedPageCount = input.pages.size +
            if (mode == DocumentAcquisitionMode.APPEND) currentSession.pages.size else 0
        if (requestedPageCount > limits.maxPages) {
            return DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED
        }
        input.pages.forEach { page ->
            validateResource(page.resource)?.let { return it }
            if (page.contentType.lowercase() !in limits.supportedPageContentTypes) {
                return DocumentAcquisitionError.UNSUPPORTED_CONTENT_TYPE
            }
            limits.imageConstraintViolation(page.imageMetadata)?.let { violation ->
                return violation.toAcquisitionError()
            }
        }
        input.directPdfSource?.let { resource ->
            validateResource(resource)?.let { return it }
        }
        input.transientResources.forEach { resource ->
            validateResource(resource)?.let { return it }
        }
        return null
    }

    private fun validateResource(resource: AcquiredResource): DocumentAcquisitionError? {
        if (resource.reference.isNullOrBlank()) return DocumentAcquisitionError.INVALID_REFERENCE
        return when (resource.ownership) {
            DocumentResourceOwnership.USER_OR_EXTERNAL -> {
                if (resource.ownedTemporaryFile == null) null
                else DocumentAcquisitionError.INVALID_OWNERSHIP
            }

            DocumentResourceOwnership.RME_OWNED_TEMPORARY -> {
                val file = resource.ownedTemporaryFile
                if (
                    file == null ||
                    file.path.isBlank() ||
                    file.rootPath.isBlank() ||
                    file.canonicalIdentityOrNull() == null
                ) {
                    DocumentAcquisitionError.INVALID_OWNERSHIP
                } else {
                    null
                }
            }

            DocumentResourceOwnership.RME_OWNED_LIBRARY ->
                DocumentAcquisitionError.INVALID_OWNERSHIP
        }
    }

    private fun validatePendingResource(resource: AcquiredResource): DocumentAcquisitionError? =
        when (resource.ownership) {
            DocumentResourceOwnership.USER_OR_EXTERNAL -> {
                if (resource.ownedTemporaryFile == null) null
                else DocumentAcquisitionError.INVALID_OWNERSHIP
            }

            DocumentResourceOwnership.RME_OWNED_TEMPORARY -> {
                if (resource.ownedTemporaryFile?.canonicalIdentityOrNull() == null) {
                    DocumentAcquisitionError.INVALID_OWNERSHIP
                } else {
                    null
                }
            }

            DocumentResourceOwnership.RME_OWNED_LIBRARY ->
                DocumentAcquisitionError.INVALID_OWNERSHIP
        }

    private fun AcquiredResource.toDocumentResource(): DocumentResource = DocumentResource(
        reference = requireNotNull(reference),
        ownership = ownership,
        ownedTemporaryFile = ownedTemporaryFile,
    )

    private fun cleanupResourceOrNull(resource: AcquiredResource): DocumentResource? {
        if (resource.ownership != DocumentResourceOwnership.RME_OWNED_TEMPORARY) return null
        val ownedFile = resource.ownedTemporaryFile ?: return null
        if (ownedFile.canonicalIdentityOrNull() == null) return null
        return DocumentResource(
            reference = resource.reference.orEmpty(),
            ownership = resource.ownership,
            ownedTemporaryFile = ownedFile,
        )
    }

    private fun cleanupInput(
        input: DocumentAcquisitionInput,
        preserving: List<DocumentResource> = emptyList(),
        preservingAcquired: List<AcquiredResource> = emptyList(),
        additionalResources: List<AcquiredResource> = emptyList(),
    ) {
        val resources = buildList {
            input.pages.mapTo(this) { page -> page.resource }
            input.directPdfSource?.let(::add)
            addAll(input.transientResources)
            addAll(additionalResources)
        }
        cleanupResources(
            resources = cleanupCandidates(resources),
            preserving = preserving + cleanupCandidates(preservingAcquired),
        )
    }

    private fun cleanupAcquiredResources(
        resources: List<AcquiredResource>,
        preserving: List<AcquiredResource>,
        preservingSession: DocumentSession,
    ) {
        cleanupResources(
            resources = cleanupCandidates(resources),
            preserving = preservingSession.ownedResources() + cleanupCandidates(preserving),
        )
    }

    private fun cleanupCandidates(resources: List<AcquiredResource>): List<DocumentResource> =
        resources.mapNotNull(::cleanupResourceOrNull)

    private fun cleanupResources(
        resources: List<DocumentResource>,
        preserving: List<DocumentResource>,
    ) {
        val retainedPaths = preserving
            .mapNotNull { resource -> resource.ownedTemporaryFile?.canonicalIdentityOrNull()?.path }
            .toSet()
        resources
            .mapNotNull { resource ->
                resource.ownedTemporaryFile?.canonicalIdentityOrNull()?.let { identity ->
                    resource to identity.path
                }
            }
            .distinctBy { (_, path) -> path }
            .filterNot { (_, path) -> path in retainedPaths }
            .map { (resource, _) -> resource }
            .forEach(::cleanup)
    }

    private fun cleanup(resource: DocumentResource) {
        if (
            resource.ownership == DocumentResourceOwnership.RME_OWNED_TEMPORARY &&
            resource.ownedTemporaryFile?.canonicalIdentityOrNull() != null
        ) {
            resourceCleaner.delete(resource)
        }
    }

    private fun DocumentImageConstraintViolation.toAcquisitionError(): DocumentAcquisitionError =
        when (this) {
            DocumentImageConstraintViolation.INVALID_METADATA ->
                DocumentAcquisitionError.INVALID_IMAGE_METADATA

            DocumentImageConstraintViolation.SOURCE_BYTES_EXCEEDED ->
                DocumentAcquisitionError.SOURCE_TOO_LARGE

            DocumentImageConstraintViolation.DIMENSIONS_EXCEEDED ->
                DocumentAcquisitionError.IMAGE_DIMENSIONS_EXCEEDED
        }
}
