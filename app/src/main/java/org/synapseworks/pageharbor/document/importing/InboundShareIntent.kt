package org.synapseworks.pageharbor.document.importing

import android.content.Intent
import android.net.Uri
import android.os.Build

sealed interface InboundShareInput {
    data class Ready(val uris: List<Uri>) : InboundShareInput
    data class Failure(val reason: DocumentImportError) : InboundShareInput
    data object NotShareIntent : InboundShareInput
}

/** Extracts only grantable stream URIs and never reads or records display names or document data. */
fun extractInboundShareInput(intent: Intent): InboundShareInput {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
        return InboundShareInput.NotShareIntent
    }
    if (!isSupportedDeclaredShareType(intent.type)) {
        return InboundShareInput.Failure(DocumentImportError.UNSUPPORTED_TYPE)
    }
    val streams = buildList {
        if (intent.action == Intent.ACTION_SEND) {
            intent.streamUri()?.let(::add)
        } else {
            addAll(intent.streamUris())
        }
        intent.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                clipData.getItemAt(index).uri?.let(::add)
            }
        }
    }.distinctBy(Uri::toString)
    return if (streams.isEmpty()) {
        InboundShareInput.Failure(DocumentImportError.EMPTY_INPUT)
    } else {
        InboundShareInput.Ready(streams)
    }
}

private fun Intent.streamUri(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
    @Suppress("DEPRECATION")
    (getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
}

private fun Intent.streamUris(): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
} else {
    @Suppress("DEPRECATION")
    getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}
