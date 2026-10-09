package org.synapseworks.pageharbor.ui.sharesafe

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSafePreviewDecoderTest {
    @Test
    fun boundedPreviewDecodesAResolverUriAndLimitsItsLongestEdge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "share-safe-preview-synthetic.jpg")
        val bitmap = Bitmap.createBitmap(1_600, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()

        val decoded = decodeBoundedPreview(context.contentResolver, Uri.fromFile(source))

        try {
            assertNotNull(decoded)
            assertTrue(requireNotNull(decoded).width <= 1_400)
            assertTrue(decoded.height <= 1_400)
        } finally {
            decoded?.recycle()
            source.delete()
        }
    }
}
