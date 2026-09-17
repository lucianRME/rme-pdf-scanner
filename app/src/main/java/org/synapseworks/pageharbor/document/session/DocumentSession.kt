package org.synapseworks.pageharbor.document.session

import java.io.File
import java.io.IOException
import org.synapseworks.pageharbor.image.DocumentFilter

/** A stable identity that exists only for the lifetime of one in-memory document workflow. */
@JvmInline
value class DocumentPageId(val value: Long)

/** Describes where a page entered RME without coupling the session to an acquisition API. */
enum class DocumentSourceCategory {
    SCAN,
    SELECTED_IMAGE,
    INBOUND_SHARE,
    RENDERED_PDF_PAGE,
    LIBRARY,
}

/** App-owned library files have a separate explicit lifetime from cache cleanup. */
enum class DocumentResourceOwnership {
    USER_OR_EXTERNAL,
    RME_OWNED_TEMPORARY,
    RME_OWNED_LIBRARY,
}

/** File metadata used only to constrain cleanup to the private root that created the file. */
data class OwnedTemporaryFile(
    val path: String,
    val rootPath: String,
)

/** File metadata used to prove that a persistent page belongs to RME's private library root. */
data class OwnedLibraryFile(
    val path: String,
    val rootPath: String,
)

/** Canonical identity used by every ownership, preservation, deduplication and deletion check. */
internal data class CanonicalOwnedTemporaryFile(
    val file: File,
    val root: File,
) {
    val path: String = file.path
}

/**
 * Returns a deletion-safe identity only when the file is strictly below its declared private root.
 * Canonicalization resolves dot segments and symlinks; uncertainty always preserves the file.
 */
internal fun OwnedTemporaryFile.canonicalIdentityOrNull(): CanonicalOwnedTemporaryFile? {
    val identity = canonicalAppFileOrNull(path, rootPath) ?: return null
    return CanonicalOwnedTemporaryFile(file = identity.first, root = identity.second)
}

internal fun OwnedLibraryFile.canonicalIdentityOrNull(): CanonicalOwnedTemporaryFile? {
    val identity = canonicalAppFileOrNull(path, rootPath) ?: return null
    return CanonicalOwnedTemporaryFile(file = identity.first, root = identity.second)
}

private fun canonicalAppFileOrNull(path: String, rootPath: String): Pair<File, File>? {
    if (path.isBlank() || rootPath.isBlank()) return null
    return try {
        val canonicalRoot = File(rootPath).canonicalFile
        val canonicalFile = File(path).canonicalFile
        if (
            canonicalFile == canonicalRoot ||
            !canonicalFile.toPath().startsWith(canonicalRoot.toPath())
        ) {
            null
        } else {
            canonicalFile to canonicalRoot
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/** Known metadata supplied by an adapter; null values mean the provider did not expose it. */
data class DocumentImageMetadata(
    val sourceByteCount: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Opaque access reference plus explicit ownership. The core session never parses a URI or opens a
 * file; Android adapters resolve [reference] only when a processor needs a stream.
 */
@ConsistentCopyVisibility
data class DocumentResource internal constructor(
    val reference: String,
    val ownership: DocumentResourceOwnership,
    internal val ownedTemporaryFile: OwnedTemporaryFile? = null,
    internal val ownedLibraryFile: OwnedLibraryFile? = null,
)

/** Safe construction boundary for one FileProvider URI below RME's private library root. */
fun createLibraryDocumentResource(
    reference: String,
    path: String,
    rootPath: String,
): DocumentResource? {
    val owned = OwnedLibraryFile(path = path, rootPath = rootPath)
    if (owned.canonicalIdentityOrNull() == null) return null
    return DocumentResource(
        reference = reference,
        ownership = DocumentResourceOwnership.RME_OWNED_LIBRARY,
        ownedLibraryFile = owned,
    )
}

/** Non-destructive clockwise page rotation retained with stable page identity. */
enum class DocumentPageRotation(val degrees: Int) {
    DEGREES_0(0),
    DEGREES_90(90),
    DEGREES_180(180),
    DEGREES_270(270),
    ;

    fun clockwise(): DocumentPageRotation = entries[(ordinal + 1) % entries.size]
}

/** One ordered page in the active, session-local document. */
data class DocumentPage(
    val id: DocumentPageId,
    val source: DocumentResource,
    val sourceCategory: DocumentSourceCategory,
    val contentType: String = "image/jpeg",
    val imageMetadata: DocumentImageMetadata = DocumentImageMetadata(),
    val rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    val filter: DocumentFilter = DocumentFilter.ORIGINAL,
    /** Stable only for pages already stored in RME's persistent local library. */
    val persistentId: String? = null,
)

/** Persistent identity attached only while a saved library document is open for editing. */
data class LibraryDocumentReference(
    val documentId: String,
    val title: String,
    val folderId: String? = null,
)

/**
 * The complete active document. A scanner PDF is an optimization snapshot, never the source of
 * page order: it is usable only while the effective pages still match [directPdfPageIds].
 */
data class DocumentSession(
    val pages: List<DocumentPage> = emptyList(),
    val directPdfSource: DocumentResource? = null,
    internal val directPdfPageIds: List<DocumentPageId> = emptyList(),
    val libraryDocument: LibraryDocumentReference? = null,
) {
    init {
        require(pages.map(DocumentPage::id).distinct().size == pages.size) {
            "Document page identities must be unique."
        }
        require(directPdfSource != null || directPdfPageIds.isEmpty()) {
            "A direct PDF page snapshot requires a direct PDF source."
        }
    }

    val canUseDirectPdf: Boolean
        get() = directPdfSource != null &&
            pages.map(DocumentPage::id) == directPdfPageIds &&
            pages.all { page ->
                page.filter == DocumentFilter.ORIGINAL &&
                    page.rotation == DocumentPageRotation.DEGREES_0
            }

    fun setFilter(pageId: DocumentPageId, filter: DocumentFilter): DocumentSession? =
        updatePage(pageId) { page -> page.copy(filter = filter) }

    fun rotateClockwise(pageId: DocumentPageId): DocumentSession? =
        updatePage(pageId) { page -> page.copy(rotation = page.rotation.clockwise()) }

    fun reorder(pageIds: List<DocumentPageId>): DocumentSession? {
        if (pageIds.size != pages.size || pageIds.distinct().size != pages.size) return null
        val pagesById = pages.associateBy(DocumentPage::id)
        val reordered = pageIds.map { pageId -> pagesById[pageId] ?: return null }
        return copy(pages = reordered)
    }

    fun move(pageId: DocumentPageId, offset: Int): DocumentSession? {
        if (offset == 0) return null
        val currentIndex = pages.indexOfFirst { page -> page.id == pageId }
        if (currentIndex < 0) return null
        val destinationIndex = currentIndex + offset
        if (destinationIndex !in pages.indices) return null
        val reordered = pages.toMutableList()
        val page = reordered.removeAt(currentIndex)
        reordered.add(destinationIndex, page)
        return copy(pages = reordered)
    }

    fun remove(pageId: DocumentPageId): DocumentSession? {
        if (pages.none { page -> page.id == pageId }) return null
        return copy(
            pages = pages.filterNot { page -> page.id == pageId },
            directPdfSource = null,
            directPdfPageIds = emptyList(),
        )
    }

    internal fun ownedResources(): List<DocumentResource> = buildList {
        pages.mapTo(this, DocumentPage::source)
        directPdfSource?.let(::add)
    }.filter { resource ->
        resource.ownership == DocumentResourceOwnership.RME_OWNED_TEMPORARY
    }.distinctBy { resource ->
        resource.ownedTemporaryFile?.canonicalIdentityOrNull()?.path ?: resource.reference
    }

    private fun updatePage(
        pageId: DocumentPageId,
        transform: (DocumentPage) -> DocumentPage,
    ): DocumentSession? {
        val index = pages.indexOfFirst { page -> page.id == pageId }
        if (index < 0) return null
        return copy(
            pages = pages.toMutableList().apply {
                this[index] = transform(this[index])
            },
        )
    }
}
