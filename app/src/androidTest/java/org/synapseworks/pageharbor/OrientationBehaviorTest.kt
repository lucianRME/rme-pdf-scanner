package org.synapseworks.pageharbor

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.AppOrientationPolicy
import org.synapseworks.pageharbor.ui.PageHarborScreen

class OrientationBehaviorTest {
    @Test
    fun navigationDocumentOcrAndBackFollowScreenAndDeviceClass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        try {
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            val initialActivity = awaitActivity { it.sessionScreenForTest() == PageHarborScreen.Home }
            val compact = initialActivity.resources.configuration.smallestScreenWidthDp <
                AppOrientationPolicy.LARGE_SCREEN_MIN_SMALLEST_WIDTH_DP
            val landscapeRotation = if (
                initialActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            ) {
                UiAutomation.ROTATION_FREEZE_0
            } else {
                UiAutomation.ROTATION_FREEZE_90
            }
            val homeRequest = if (compact) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            awaitActivity { activity ->
                activity.requestedOrientation == homeRequest &&
                    (!compact || activity.resources.configuration.orientation ==
                        Configuration.ORIENTATION_PORTRAIT)
            }

            instrumentation.runOnMainSync {
                currentActivity().restoreCompletedSessionForTest(scanSummary())
            }
            awaitActivity { activity ->
                activity.sessionScreenForTest() == PageHarborScreen.ScanResult &&
                    activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            automation.setRotation(landscapeRotation)
            val landscapeDocumentActivity = awaitActivity { activity ->
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    activity.sessionScreenForTest() == PageHarborScreen.ScanResult
            }

            instrumentation.runOnMainSync { landscapeDocumentActivity.recreate() }
            awaitActivity { activity ->
                activity !== landscapeDocumentActivity &&
                    activity.sessionScreenForTest() == PageHarborScreen.ScanResult &&
                    activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitActivity { activity ->
                activity.sessionScreenForTest() == PageHarborScreen.Home &&
                    activity.requestedOrientation == homeRequest &&
                    (!compact || activity.resources.configuration.orientation ==
                        Configuration.ORIENTATION_PORTRAIT)
            }

            instrumentation.runOnMainSync {
                currentActivity().restoreCompletedSessionForTest(
                    summary = scanSummary(),
                    ocrResult = OcrResult(
                        listOf(OcrPageResult(pageIndex = 0, text = "Orientation test")),
                    ),
                    screen = PageHarborScreen.OcrResult,
                )
            }
            awaitActivity { activity ->
                activity.sessionScreenForTest() == PageHarborScreen.OcrResult &&
                    activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            automation.setRotation(landscapeRotation)
            awaitActivity { activity ->
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    activity.sessionScreenForTest() == PageHarborScreen.OcrResult
            }
        } finally {
            instrumentation.runOnMainSync { currentActivityOrNull()?.finish() }
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun currentActivity(): MainActivity = requireNotNull(currentActivityOrNull())

    private fun currentActivityOrNull(): MainActivity? {
        var activity: MainActivity? = null
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .firstOrNull()
            ?.let { activity = it }
        return activity
    }

    private fun awaitActivity(predicate: (MainActivity) -> Boolean): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(100) {
            instrumentation.waitForIdleSync()
            var matched: MainActivity? = null
            instrumentation.runOnMainSync {
                currentActivityOrNull()?.takeIf(predicate)?.let { matched = it }
            }
            if (matched != null) return requireNotNull(matched)
            Thread.sleep(100L)
        }
        var finalActivity: MainActivity? = null
        instrumentation.runOnMainSync {
            finalActivity = currentActivityOrNull()
        }
        assertEquals(true, finalActivity?.let(predicate))
        return requireNotNull(finalActivity)
    }

    private fun scanSummary() = ScannerSpikeState.ResultSummary(
        jpegPageCount = 1,
        hasPdf = true,
        pdfPageCount = 1,
    )
}
