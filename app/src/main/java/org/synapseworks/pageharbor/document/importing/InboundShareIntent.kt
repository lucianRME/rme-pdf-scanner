package org.synapseworks.pageharbor.document.importing

import android.content.Intent
import android.net.Uri
import java.util.Locale

sealed interface InboundShareInput {
    data class Ready(val resources: List<InboundShareResource>) : InboundShareInput {
        val uris: List<Uri> get() = resources.map(InboundShareResource::uri)
    }
    data class Failure(val reason: DocumentImportError) : InboundShareInput
    data object NotShareIntent : InboundShareInput
}

/** The MIME is a routing hint. The byte signature is checked inside the normal importer. */
internal fun InboundShareInput.Ready.isSinglePhotoShare(action: String?): Boolean =
    action == Intent.ACTION_SEND && resources.singleOrNull()?.declaredContentType.let { type ->
        type?.startsWith("image/") == true && isSupportedDeclaredShareType(type)
    }

data class InboundShareResource(
    val uri: Uri,
    /** A routing hint only. Importers must validate the stream signature before decoding. */
    val declaredContentType: String?,
    val grantFlags: Int,
)

/** Extracts stream references without reading or recording display names or document data. */
fun extractInboundShareInput(intent: Intent, privateAuthorities: Set<String> = emptySet()): InboundShareInput {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
        return InboundShareInput.NotShareIntent
    }
    val declaredType = intent.type.normalizedContentType()
    val grantFlags = intent.flags and SHARE_GRANT_FLAGS
    val candidates = buildList {
        intent.streamUris().forEach { uri -> add(uri to declaredType) }
        intent.data?.let { uri -> add(uri to declaredType) }
        intent.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                val item = clipData.getItemAt(index)
                item.uri?.let { uri -> add(uri to declaredType) }
            }
        }
    }
    // Public share input may name only another provider's content. Internal capture, picker and
    // saved-document URLs do not pass through this external Android entry point.
    if (candidates.any { (uri, _) ->
            val authority = uri.authority.orEmpty().substringAfterLast('@')
            uri.scheme != "content" || authority.isBlank() || authority in privateAuthorities
        }) return InboundShareInput.Failure(DocumentImportError.UNREADABLE_SOURCE)
    val resources = linkedMapOf<String, InboundShareResource>()
    candidates.forEach { (uri, type) ->
        val key = uri.toString()
        val previous = resources[key]
        resources[key] = if (previous == null) {
            InboundShareResource(uri, type, grantFlags)
        } else {
            previous.copy(
                declaredContentType = previous.declaredContentType.preferSpecificType(type),
                grantFlags = previous.grantFlags or grantFlags,
            )
        }
    }
    return if (resources.isEmpty()) {
        val reason = if (declaredType != null && !isSupportedDeclaredShareType(declaredType)) {
            DocumentImportError.UNSUPPORTED_TYPE
        } else {
            DocumentImportError.EMPTY_INPUT
        }
        InboundShareInput.Failure(reason)
    } else {
        // Mixed or broad declarations are accepted here; the importer validates every signature.
        InboundShareInput.Ready(resources.values.toList())
    }
}

private fun String?.normalizedContentType(): String? = this
    ?.substringBefore(';')
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf(String::isNotEmpty)

private fun String?.preferSpecificType(candidate: String?): String? = when {
    this == null || this == "*/*" -> candidate ?: this
    else -> this
}

private fun Intent.streamUris(): List<Uri> = try {
    @Suppress("DEPRECATION")
    when (val stream = extras?.get(Intent.EXTRA_STREAM)) {
        is Uri -> listOf(stream)
        is ArrayList<*> -> stream.filterIsInstance<Uri>()
        is Array<*> -> stream.filterIsInstance<Uri>()
        else -> emptyList()
    }
} catch (_: RuntimeException) {
    emptyList()
}

private const val SHARE_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
