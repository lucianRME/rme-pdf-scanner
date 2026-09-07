package org.synapseworks.pageharbor

import android.content.ClipDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.ui.copyPlainTextToClipboard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClipboardTextTest {
    @Test
    fun productionClipboardPathWritesExactPlainTextAndCleansUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        dismissNotificationShade(instrumentation)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        ) as MainActivity
        val expectedText = "RME PDF Scanner clipboard test"
        var clip: ClipData? = null
        var description: ClipDescription? = null
        var copied = false

        try {
            instrumentation.waitForIdleSync()
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            waitForWindowFocus(activity)
            instrumentation.runOnMainSync {
                copied = copyPlainTextToClipboard(activity, "Recognized text", expectedText)
                val clipboardManager = activity.getSystemService(ClipboardManager::class.java)
                clip = clipboardManager.primaryClip
                description = clipboardManager.primaryClipDescription
            }

            assertTrue(copied)
            assertNotNull(clip)
            assertNotNull(description)
            assertTrue(description!!.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
            assertEquals(expectedText, clip!!.getItemAt(0).coerceToText(activity).toString())
        } finally {
            instrumentation.runOnMainSync {
                val clipboardManager = activity.getSystemService(ClipboardManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboardManager.clearPrimaryClip()
                } else {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                activity.finishAndRemoveTask()
            }
            instrumentation.waitForIdleSync()
        }
    }

    /**
     * Android 10+ restricts clipboard access to the focused app. A previously opened notification
     * shade can leave this test activity RESUMED without giving its window focus on Samsung devices.
     * Dismissing that system overlay is test-host isolation; [waitForWindowFocus] still verifies the
     * foreground prerequisite before production clipboard code runs.
     */
    private fun dismissNotificationShade(instrumentation: android.app.Instrumentation) {
        val globalAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        } else {
            AccessibilityService.GLOBAL_ACTION_BACK
        }
        instrumentation.uiAutomation.performGlobalAction(globalAction)
        instrumentation.waitForIdleSync()
    }

    private fun waitForWindowFocus(activity: MainActivity) {
        if (activity.window.decorView.hasWindowFocus()) return

        val focused = CountDownLatch(1)
        val decorView = activity.window.decorView
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) focused.countDown()
        }
        try {
            activity.runOnUiThread {
                if (decorView.hasWindowFocus()) {
                    focused.countDown()
                } else {
                    decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                }
            }
            assertTrue("RME PDF Scanner activity did not gain window focus", focused.await(5, TimeUnit.SECONDS))
        } finally {
            activity.runOnUiThread {
                if (decorView.viewTreeObserver.isAlive) {
                    decorView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                }
            }
        }
    }
}
