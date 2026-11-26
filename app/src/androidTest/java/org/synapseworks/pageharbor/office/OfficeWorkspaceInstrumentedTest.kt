package org.synapseworks.pageharbor.office

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Owned synthetic copies only; never clear app data or signal arbitrary PIDs. */
class OfficeWorkspaceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private lateinit var root: File
    @Before fun setup() = runBlocking {
        Assume.assumeTrue(org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED)
        recoverOfficeWorkspace(app)
        root = File(app.cacheDir, "shared-pdfs/m35-workspace-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { if (::root.isInitialized) root.deleteRecursively() }
    private fun source(): OfficeSource {
        val file = File(root, "source.docx").apply { writeBytes(instrumentation.context.assets.open("m35/docx-pagination.docx").use { it.readBytes() }) }
        return OfficeSource.open(app, FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file), "docx") {}
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.bufferedReader().readText().trim() }
    @Test fun descriptorModesUseSupportedApiAndPreserveAllBytes() {
        val file = File(root, "descriptor.bin").apply { writeBytes(ByteArray(256) { it.toByte() }) }
        val before = file.readBytes()
        for ((mode, expected) in listOf(ParcelFileDescriptor.MODE_READ_ONLY to android.system.OsConstants.O_RDONLY,
            ParcelFileDescriptor.MODE_READ_WRITE to android.system.OsConstants.O_RDWR,
            ParcelFileDescriptor.MODE_WRITE_ONLY to android.system.OsConstants.O_WRONLY)) {
            ParcelFileDescriptor.open(file, mode).use {
                assertEquals(expected, org.synapseworks.m35.workerprobe.OfficeFd.flags(it) and android.system.OsConstants.O_ACCMODE)
            }
            assertArrayEquals(before, file.readBytes())
        }
    }
    @Test fun interruptedBeforeParseLeaseAndOldUiCopiesRecoverOnlyAfterIdle() = runBlocking {
        val lease = officePreviewLease(app)
        File(lease, "pixels").writeBytes(ByteArray(4096))
        val old = officeSessionDirectory(app)
        File(old, "reviewed.pdf").writeText("SYNTHETIC INTERRUPTED COPY")
        recoverOfficeWorkspace(app)
        assertFalse(lease.exists()); assertFalse(old.exists())
        officeCoordinatorIdle(app)
    }
    @Test fun malformedWorkerJournalIsQuarantinedUntilExplicitlyResolved() = runBlocking {
        val lease = officePreviewLease(app)
        try {
            val marker = File(lease, "worker").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            try { recoverOfficePreviewLeases(app); fail("Malformed ownership must not be deleted") } catch (_: Exception) {}
            assertTrue(marker.exists())
        } finally { lease.deleteRecursively() }
    }
    @Test fun aggregateShareStorageRefusesBeforeNativeLoadAndRecovers() = runBlocking {
        val retained = File(app.cacheDir, "shared-pdfs/office-${UUID.randomUUID()}.pdf")
        try {
            RandomAccessFile(retained, "rw").use { it.setLength(9L * 1024 * 1024) }
            source().use { source ->
                try { convertOffice(app, source, File(root, "refused.pdf")); fail("Retained share bytes must count") }
                catch (failure: OfficeFailure) { assertEquals("TEMP_LIMIT", failure.code) }
                source.unchanged {}
                assertTrue(shell("pidof ${app.packageName}:officeengine").isEmpty())
                officeCoordinatorIdle(app)
            }
        } finally { retained.delete() }
        source().use { source -> assertTrue(convertOffice(app, source, File(root, "recovered.pdf")).pages > 0) }
    }
    @Test fun actualNativeCancellationConfirmsWorkerDeathAndCleanup() = runBlocking {
        officeWorkerProbe.set(null)
        source().use { source ->
            val task = async(Dispatchers.IO) { convertOffice(app, source, File(root, "cancelled.pdf")) }
            var seen = false
            var lease:File?=null
            var retainedLiveLease=false
            try {
                withTimeout(15000) {
                    while (!task.isCompleted) {
                        if (liveOfficeEngine(app)!=null) { seen = true; break }
                        delay(2)
                    }
                }
                assertTrue("Cancel must reach an actual native process", seen)
                val identity=checkNotNull(officeWorkerProbe.get())
                lease=officePreviewLease(app)
                recordOfficePreview(app,checkNotNull(lease),identity[0],identity[1])
                assertFalse("A live protected worker must retain its preview journal",previewGone(app,checkNotNull(lease)))
                retainedLiveLease=true
            } finally {
                task.cancelAndJoin()
                File(app.getExternalFilesDir(null),"m35-worker-observation.json").writeText(JSONObject().apply {
                    put("observed",seen)
                    put("retainedLiveLease",retainedLiveLease)
                    officeWorkerProbe.get()?.let { put("pid",it[0]);put("binderUid",it[1]);put("procOwner",it[2]);put("existenceErrno",it[3]) }
                }.toString())
            }
            officeCoordinatorIdle(app)
            assertTrue(File(app.noBackupFilesDir,"office-engine/jobs").listFiles().isNullOrEmpty())
            val pid=checkNotNull(officeWorkerProbe.get())[0]
            val dead=assertThrows(android.system.ErrnoException::class.java){android.system.Os.kill(pid,0)}
            assertEquals(android.system.OsConstants.ESRCH,dead.errno)
            assertTrue(previewGone(app,checkNotNull(lease)))
            assertTrue(checkNotNull(lease).deleteRecursively())
            source.unchanged {}
        }
    }
    @Test fun frozenAggregateParserBoundsExecuteInAndroidBeforeConversion() {
        val manifest = JSONObject(instrumentation.context.assets.open("m35-bounds/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
        for (index in 0 until manifest.length()) {
            val case = manifest.getJSONObject(index)
            val file = File(root, case.getString("id") + ".docx")
            file.writeBytes(instrumentation.context.assets.open("m35-bounds/" + file.name).use { it.readBytes() })
            assertEquals(case.getString("sha256"), officeDigest(file) {})
            val expected = case.getString("expected")
            try {
                org.synapseworks.m35.workerprobe.OfficeAdmission.inspect(file, "docx")
                assertEquals("ADMITTED", expected)
            } catch (failure: Exception) {
                assertEquals(expected, org.synapseworks.m35.workerprobe.OfficeAdmission.failureCode(failure))
            }
        }
    }
}
