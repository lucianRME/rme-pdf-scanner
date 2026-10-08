package org.synapseworks.pageharbor

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.cardcopy.CardCopyAcquisition
import org.synapseworks.pageharbor.document.cardcopy.CardCopySide
import org.synapseworks.pageharbor.document.cardcopy.CardCopySideState
import org.synapseworks.pageharbor.document.cardcopy.CardCopyWorkflowState

class CardCopyActivityLifecycleTest {
    @Test
    fun preparedResultAndStableSourcesSurviveActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = File(context.cacheDir, "card-copy-recreation-${System.nanoTime()}").apply { mkdirs() }
        val front = fixture(File(fixtureRoot, "front.jpg"), Color.RED)
        val back = fixture(File(fixtureRoot, "back.jpg"), Color.BLUE)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val workflow = activity.cardCopyWorkflowForTest()
                    workflow.open()
                    assertTrue(workflow.beginAcquisition(CardCopySide.FRONT, CardCopyAcquisition.IMPORT))
                    workflow.onAcquisitionResult(Uri.fromFile(front))
                }
                waitForState(scenario) { state ->
                    (state as? CardCopyWorkflowState.AcquireSides)?.front is CardCopySideState.Ready
                }
                scenario.onActivity { activity ->
                    val workflow = activity.cardCopyWorkflowForTest()
                    assertTrue(workflow.beginAcquisition(CardCopySide.BACK, CardCopyAcquisition.IMPORT))
                    workflow.onAcquisitionResult(Uri.fromFile(back))
                }
                waitForState(scenario) { state ->
                    (state as? CardCopyWorkflowState.AcquireSides)?.back is CardCopySideState.Ready
                }
                scenario.onActivity { activity ->
                    val workflow = activity.cardCopyWorkflowForTest()
                    workflow.continueToReview()
                    workflow.continueToLayout()
                    workflow.showPreview()
                    workflow.prepare()
                }
                waitForState(scenario) { it is CardCopyWorkflowState.Prepared }

                var preparedPath = ""
                scenario.onActivity { activity ->
                    val state = activity.cardCopyWorkflowStateForTest() as CardCopyWorkflowState.Prepared
                    preparedPath = state.prepared.file.path
                    assertTrue(state.front.file.isFile)
                    assertTrue(state.back.file.isFile)
                    assertTrue(state.prepared.file.isFile)
                }

                scenario.recreate()

                scenario.onActivity { activity ->
                    val state = activity.cardCopyWorkflowStateForTest() as CardCopyWorkflowState.Prepared
                    assertEquals(preparedPath, state.prepared.file.path)
                    assertTrue(state.front.file.isFile)
                    assertTrue(state.back.file.isFile)
                    assertTrue(state.prepared.file.isFile)
                    state.prepared.file.inputStream().use { input ->
                        assertEquals('%'.code, input.read())
                    }
                    activity.cardCopyWorkflowForTest().close()
                }
            }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    private fun waitForState(
        scenario: ActivityScenario<MainActivity>,
        predicate: (CardCopyWorkflowState) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var reached = false
        while (!reached && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity -> reached = predicate(activity.cardCopyWorkflowStateForTest()) }
            if (!reached) SystemClock.sleep(20L)
        }
        assertTrue("Timed out waiting for card-copy state", reached)
    }

    private fun fixture(file: File, color: Int): File {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
