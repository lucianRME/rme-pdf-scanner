package org.synapseworks.pageharbor

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportManifestTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun supportedSendAndSendMultipleTypesResolveToMainActivity() {
        listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
            listOf("image/jpeg", "image/png", "image/webp", "application/pdf").forEach { type ->
                assertTrue(rmeCanHandle(action, type))
            }
        }
    }

    @Test
    fun unsupportedShareTypesAreNotAdvertised() {
        listOf("text/plain", "image/heic", "application/zip").forEach { type ->
            assertFalse(rmeCanHandle(Intent.ACTION_SEND, type))
            assertFalse(rmeCanHandle(Intent.ACTION_SEND_MULTIPLE, type))
        }
    }

    private fun rmeCanHandle(action: String, type: String): Boolean {
        val intent = Intent(action).apply {
            this.type = type
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(context.packageName)
        }
        return context.packageManager.queryIntentActivities(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).any { result -> result.activityInfo.packageName == context.packageName }
    }
}
