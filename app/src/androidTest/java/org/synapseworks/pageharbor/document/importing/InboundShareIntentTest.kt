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
            putExtra(Intent.EXTRA_STREAM, first)
        }

        assertEquals(InboundShareInput.Ready(listOf(first)), extractInboundShareInput(intent))
    }

    @Test
    fun actionSendMultiplePreservesOrderAndDeduplicatesClipData() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            clipData = ClipData.newRawUri("shared", second)
        }

        assertEquals(
            InboundShareInput.Ready(listOf(first, second)),
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
    fun unsupportedAndBroadTypesAreRejected() {
        listOf("text/plain", "*/*", "image/heic").forEach { contentType ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = contentType
                putExtra(Intent.EXTRA_STREAM, first)
            }

            assertEquals(
                InboundShareInput.Failure(DocumentImportError.UNSUPPORTED_TYPE),
                extractInboundShareInput(intent),
            )
        }
    }

    @Test
    fun unrelatedIntentIsIgnored() {
        assertTrue(extractInboundShareInput(Intent(Intent.ACTION_VIEW)) is InboundShareInput.NotShareIntent)
    }
}
