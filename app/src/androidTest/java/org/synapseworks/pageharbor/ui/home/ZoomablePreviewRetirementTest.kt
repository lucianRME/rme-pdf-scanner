package org.synapseworks.pageharbor.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class ZoomablePreviewRetirementTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accessibilityActionsZoomPanAndResetOnPageChange() {
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
        var page by mutableStateOf(1)
        compose.setContent {
            ZoomableDocumentPreview(image, page, "Test page", Modifier.size(180.dp))
        }
        val preview = compose.onNodeWithContentDescription("Test page")
        fun use(label: String) {
            val actions = preview.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle { assertTrue(actions.single { it.label == label }.action()) }
        }
        use("Zoom in")
        preview.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 200%"))
        use("View right")
        use("Zoom in")
        use("Fit page")
        preview.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 100%"))
        use("Zoom in")
        compose.runOnIdle { page = 2 }
        preview.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 100%"))
    }

    @Test
    fun staleLayerRedrawAfterRetirementDoesNotReadOrRetainReleasedPixels() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val image = bitmap.asImageBitmap()
        var width by mutableStateOf(240.dp)
        try {
            compose.setContent {
                PageHarborTheme {
                    ZoomableDocumentPreview(
                        bitmap = image,
                        resetKey = "synthetic-retirement",
                        description = "Synthetic retirement preview",
                        modifier = Modifier.size(width, 180.dp),
                    )
                }
            }
            val preview = compose.onNodeWithContentDescription("Synthetic retirement preview")
            val live = preview.captureToImage()
            val liveCenter = live.toPixelMap()[live.width / 2, live.height / 2]
            assertTrue(liveCenter.red > .9f && liveCenter.green < .1f && liveCenter.blue < .1f)

            // A recorded layer can outlive its owner. Force re-recording at a new window size
            // while it still holds the retired ImageBitmap, matching the observed Canvas error.
            compose.runOnIdle {
                bitmap.recycle()
                width = 241.dp
            }
            val retired = preview.captureToImage()
            val retiredCenter = retired.toPixelMap()[retired.width / 2, retired.height / 2]
            assertTrue("Cleanup must not be deferred to retain drawable pixels", bitmap.isRecycled)
            assertFalse(
                "Released document pixels must not be rendered by a stale layer",
                retiredCenter.red > .9f && retiredCenter.green < .1f && retiredCenter.blue < .1f,
            )
        } finally {
            compose.runOnIdle { if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }
}
