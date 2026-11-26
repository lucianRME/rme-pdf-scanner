package org.synapseworks.pageharbor.office

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Explicit synthetic two-process protocol. The first instrumentation is
 * expected to die after its fixed checkpoint; a fresh process verifies recovery.
 * Never force-stop the consumer package or change device settings.
 */
class OfficeRestartInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private fun parameters(): Pair<String, String> {
        Assume.assumeTrue(org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED)
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("m35RestartRun")
        val mode = args.getString("m35RestartMode")
        Assume.assumeTrue(run?.matches(Regex("[a-f0-9]{32}")) == true && mode in setOf("native", "preview"))
        return checkNotNull(run) to checkNotNull(mode)
    }
    private fun source(run: String, mode: String) = File(app.cacheDir, "shared-pdfs/m35-restart-$run.${if (mode == "native") "docx" else "pdf"}")
    private fun fixture(mode: String) = if (mode == "native") "m35/docx-pagination.docx" else "m35-preview/pptx-portrait.pdf"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.bufferedReader().readText().trim() }
    @Test fun prepareThenTerminateOnlyThisCallerAtAnOwnedWorkerCheckpoint() = runBlocking<Unit> {
        val (run, mode) = parameters(); recoverOfficeWorkspace(app)
        val original = source(run, mode)
        assertFalse(original.exists()); original.parentFile!!.mkdirs()
        original.writeBytes(instrumentation.context.assets.open(fixture(mode)).use { it.readBytes() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var input: OfficeSource? = null
        try {
            val work = if (mode == "native") {
                input = OfficeSource.open(app, FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", original), "docx") {}
                val output = File(officeSessionDirectory(app), "unpublished.pdf")
                scope.async { convertOffice(app, checkNotNull(input), output) }
            } else scope.async { previewOffice(app, original, 0, 1).recycle() }
            withTimeout(15000) {
                while (true) {
                    val observed = if (mode == "native") liveOfficeEngine(app)!=null
                    else File(app.noBackupFilesDir, "office-preview").listFiles().orEmpty().any { File(it, "worker").length() == 16L }
                    if (observed) break
                    assertFalse("Worker completed before the required death checkpoint", work.isCompleted)
                    delay(5)
                }
            }
            instrumentation.sendStatus(73, Bundle().apply { putString("m35RestartCheckpoint", mode); putInt("callerPid", Process.myPid()) })
            // No shutdown/clear/finally path can simulate this abrupt owner death.
            Process.killProcess(Process.myPid())
            error("Caller termination returned")
        } finally { scope.cancel(); input?.close() }
    }
    @Test fun freshProcessRecoversOwnedCopiesWithoutChangingOriginal() = runBlocking {
        val (run, mode) = parameters(); val original = source(run, mode)
        assertTrue(original.isFile)
        val expected = instrumentation.context.assets.open(fixture(mode)).use { it.readBytes() }
        assertArrayEquals(expected, original.readBytes())
        withTimeout(15000) { recoverOfficeWorkspace(app) }
        for (name in listOf("office-ui", "office-preview")) assertTrue(File(app.noBackupFilesDir, name).listFiles().orEmpty().isEmpty())
        officeCoordinatorIdle(app)
        assertTrue(shell("pidof ${app.packageName}:officeengine").isEmpty())
        assertTrue(shell("pidof ${app.packageName}:officepreview").isEmpty())
        assertArrayEquals(expected, original.readBytes())
        assertTrue(original.delete())
    }
}
