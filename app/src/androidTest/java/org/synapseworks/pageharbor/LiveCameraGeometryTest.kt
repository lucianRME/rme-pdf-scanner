package org.synapseworks.pageharbor

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveCameraGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun allLocalScannersFillTheWindowAndShareThePhysicalThumbPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        check(pkg.endsWith(".phase2test"))
        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.CAMERA)
        val activity = compose.activity
        fun geometry(imageTag: String, actionTag: String) {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag(imageTag).fetchSemanticsNodes().size == 1 }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val image = compose.onNodeWithTag(imageTag).fetchSemanticsNode().boundsInRoot
            val action = compose.onNodeWithTag(actionTag).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            assertEquals(imageTag, root.width, image.width, 2f)
            assertEquals(imageTag, root.height, image.height, 2f)
            assertTrue(actionTag, action.center.x > root.right - root.width * .25f)
            assertTrue(actionTag, action.center.y > root.bottom - root.height * .25f)
        }
        fun back() = compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle { activity.openContinuousCapture(singlePage = true) }
        for (mode in listOf("document", "batch", "book")) {
            compose.onNodeWithTag("capture-mode-" + mode).performScrollTo().performClick()
            geometry("capture-viewfinder", "capture-shutter")
        }
        compose.onNodeWithTag("capture-open-card-copy").performScrollTo().performClick()
        geometry("id-viewfinder", "id-shutter")
        compose.onAllNodesWithTag("id-shutter").assertCountEquals(1)
        back()

        compose.runOnIdle { activity.openBusinessCard() }
        geometry("card-viewfinder", "card-shutter")
        back()
        compose.runOnIdle { activity.openReceipt() }
        geometry("receipt-viewfinder", "receipt-shutter")
        back()
        compose.runOnIdle { activity.openTable() }
        geometry("table-viewfinder", "table-shutter")
        back()
        compose.runOnIdle { activity.openQrScanner() }
        geometry("qr-viewfinder", "qr-pause")
        compose.onNodeWithTag("qr-pause").performClick()
        compose.onNodeWithTag("qr-resume").assertIsDisplayed().performClick()
        geometry("qr-viewfinder", "qr-pause")
        back()
    }
}

