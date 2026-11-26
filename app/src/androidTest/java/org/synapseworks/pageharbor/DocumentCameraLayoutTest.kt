package org.synapseworks.pageharbor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.ui.home.CaptureShutter
import org.synapseworks.pageharbor.ui.home.DocumentCameraLayout

class DocumentCameraLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun portraitFillsWindowWithShutterAtBottomRight() = checkGeometry(false, LayoutDirection.Ltr)
    @Test fun landscapeFillsWindowWithShutterAtBottomRight() = checkGeometry(true, LayoutDirection.Ltr)
    @Test fun rtlKeepsPhysicalThumbPositionAndButtonLabel() = checkGeometry(true, LayoutDirection.Rtl)

    private fun checkGeometry(wide: Boolean, direction: LayoutDirection) {
        var clicked = false
        val windowHandlesInsets = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                // These miniature panels live in a full phone window. Consume the phone's insets
                // at its real edges before allocating the independent landscape or portrait panel.
                Box(if (windowHandlesInsets.value) Modifier.fillMaxSize().safeDrawingPadding() else Modifier) {
                    Box(Modifier.size(304.dp, if (wide) 176.dp else 408.dp).testTag("bounds")) {
                        DocumentCameraLayout(
                            viewfinder = { Box(it.testTag("image")) },
                            controls = { Text("One page", it.testTag("actions")) },
                            shutter = { CaptureShutter("Capture page", "shutter", true) { clicked = true } },
                        )
                    }
                }
            }
        }
        fun reportGeometry(stage: String) {
            val values = listOf("bounds", "image", "shutter").associateWith {
                compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.toString()
            }
            val report = "$stage wide=$wide direction=$direction $values"
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", report + "\n") })
        }
        reportGeometry("insets inherited from outer window")
        compose.runOnIdle { windowHandlesInsets.value = true }
        compose.waitForIdle()
        reportGeometry("insets consumed by outer window")
        val frame = compose.onNodeWithTag("bounds").fetchSemanticsNode().boundsInRoot
        val image = compose.onNodeWithTag("image").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithContentDescription("Capture page").assertIsDisplayed().assertHasClickAction()
        val shutter = action.fetchSemanticsNode().boundsInRoot
        assertTrue(shutter.center.x > frame.left + frame.width * .75f)
        assertTrue(shutter.center.y > frame.top + frame.height * .65f)
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue(shutter.width >= minimum && shutter.height >= minimum)
        assertEquals(frame.left, image.left, 1f)
        assertEquals(frame.right, image.right, 1f)
        assertEquals(frame.top, image.top, 1f)
        assertEquals(frame.bottom, image.bottom, 1f)
        action.performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
}
