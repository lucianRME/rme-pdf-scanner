package org.synapseworks.pageharbor.workflow

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.synapseworks.pageharbor.MainActivity

/** Synthetic M35 workflow only: lifecycle observation has a finite deadline.
 * KEEP_SCREEN_ON applies to this owned foreground test window; it neither
 * dismisses keyguard nor changes a device setting. The caller finishes it.
 */
internal fun Instrumentation.startOfficeRecipeMain(evidence: File, report: JSONObject): MainActivity {
    val monitor = Instrumentation.ActivityMonitor(MainActivity::class.java.name, null, false)
    val started = SystemClock.elapsedRealtime()
    var observed: MainActivity? = null
    addMonitor(monitor)
    fun stage(value: String) {
        report.put("startupStage", value)
        sendStatus(17, Bundle().apply { putString("m35RecipeStartup", value) })
    }
    try {
        stage("LAUNCH_MAIN")
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        observed = monitor.waitForActivityWithTimeout(15000) as? MainActivity
        check(observed != null) { "MAIN_LIFECYCLE_DEADLINE" }
        stage("MAIN_OBSERVED")
        val ready = CountDownLatch(1)
        val activity = observed
        Handler(Looper.getMainLooper()).post {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ready.countDown()
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "MAIN_DISPATCH_DEADLINE" }
        stage("MAIN_READY")
        report.put("startupElapsedMs", SystemClock.elapsedRealtime() - started)
            .put("ownedTestWindowKeepScreenOn", true)
        File(evidence, "startup.json").writeText(report.toString(2))
        return activity
    } catch (failure: Throwable) {
        // Symbols only: no messages, arguments, file names, paths or line numbers.
        report.put("stage", "BOUNDED_MAIN_STARTUP_FAILED")
            .put("failureClass", failure.javaClass.simpleName)
            .put("startupElapsedMs", SystemClock.elapsedRealtime() - started)
            .put("mainThreadState", Looper.getMainLooper().thread.state.name)
            .put("mainStackSymbols", JSONArray(Looper.getMainLooper().thread.stackTrace.take(24).map { "${it.className}.${it.methodName}" }))
        File(evidence, "failure.json").writeText(report.toString(2))
        observed?.let { activity -> Handler(Looper.getMainLooper()).post { activity.finish() } }
        throw failure
    } finally {
        removeMonitor(monitor)
    }
}
