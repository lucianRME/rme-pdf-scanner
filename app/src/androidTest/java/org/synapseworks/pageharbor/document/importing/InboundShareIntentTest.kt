package org.synapseworks.pageharbor.document.importing

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundShareIntentTest {
    private val first = Uri.parse("content://example.test/shared/first")
    private val second = Uri.parse("content://example.test/shared/second")

    @Test
    fun actionSendExtractsOneSupportedImage() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, first)
        }

        assertEquals(
            ready(first, type = "image/jpeg", flags = Intent.FLAG_GRANT_READ_URI_PERMISSION),
            extractInboundShareInput(intent),
        )
    }

    @Test
    fun actionSendMultiplePreservesOrderAndDeduplicatesClipData() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            clipData = ClipData.newRawUri("shared", second)
        }

        assertEquals(
            InboundShareInput.Ready(
                listOf(
                    InboundShareResource(first, "image/*", 0),
                    InboundShareResource(second, "image/*", 0),
                ),
            ),
            extractInboundShareInput(intent),
        )
    }

    @Test
    fun missingStreamIsAConciseEmptyInputFailure() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf" }

        assertEquals(
            InboundShareInput.Failure(DocumentImportError.EMPTY_INPUT),
            extractInboundShareInput(intent),
        )
    }

    @Test
    fun streamUrisWithBroadOrMixedDeclarationsAreDeferredToSignatureValidation() {
        listOf("text/plain", "*/*", "image/heic").forEach { contentType ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = contentType
                putExtra(Intent.EXTRA_STREAM, first)
            }

            assertEquals(
                ready(first, type = contentType),
                extractInboundShareInput(intent),
            )
        }
    }

    @Test
    fun unsupportedTypeWithoutAnyStreamIsRejected() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }

        assertEquals(
            InboundShareInput.Failure(DocumentImportError.UNSUPPORTED_TYPE),
            extractInboundShareInput(intent),
        )
    }

    @Test
    fun intentDataAndClipDataUriAreIncludedAndDeduplicated() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            data = first
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first))
            // Android API 36 does not preserve a nested Intent.data URI in
            // ClipData.newIntent; a raw ClipData URI is the deliverable contract.
            clipData = ClipData.newRawUri("shared", second)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }

        val result = extractInboundShareInput(intent) as InboundShareInput.Ready

        assertEquals(listOf(first, second), result.uris)
        assertEquals("*/*", result.resources[0].declaredContentType)
        assertEquals("*/*", result.resources[1].declaredContentType)
        assertTrue(result.resources.all {
            it.grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        })
    }

    @Test
    fun unrelatedIntentIsIgnored() {
        assertTrue(extractInboundShareInput(Intent(Intent.ACTION_VIEW)) is InboundShareInput.NotShareIntent)
    }

    private fun ready(
        uri: Uri,
        type: String?,
        flags: Int = 0,
    ) = InboundShareInput.Ready(listOf(InboundShareResource(uri, type, flags)))
}
