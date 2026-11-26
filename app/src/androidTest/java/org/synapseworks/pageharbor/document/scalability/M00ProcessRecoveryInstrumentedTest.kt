package org.synapseworks.pageharbor.document.scalability

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Process
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.importing.DocumentImportOrigin
import org.synapseworks.pageharbor.document.importing.deleteStaleDocumentImports
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.library.*
import org.synapseworks.pageharbor.scanner.ScannerSpikeState

/** Host can run seed -> force-stop -> verify; default full-suite execution remains self-contained. */
class M00ProcessRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val state = File(context.filesDir, "m00-recovery-state.json")
    private val repository = LibraryRepository(context)

    @Test fun durableLibraryAndInterruptedImportRecovery() = runBlocking {
        assertTrue(context.packageName.endsWith(".m00benchmark"))
        when (InstrumentationRegistry.getArguments().getString("m00RecoveryPhase")) {
            "seed" -> seed()
            "verify" -> verify(requireProcessBoundary = true)
            "cleanup" -> cleanup()
            else -> { seed(); verify(requireProcessBoundary = false); cleanup() }
        }
    }

    private suspend fun seed() {
        assertFalse("An earlier synthetic recovery fixture is still pending", state.exists())
        val source = File(context.cacheDir, "shared-pdfs/m00-recovery.jpg")
        source.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("Synthetic M00", 20f, 100f, Paint().apply { color = Color.BLACK; textSize = 24f })
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { bitmap.recycle() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        val session = PageHarborSessionViewModel()
        session.replaceScan(ScannerSpikeState.ResultSummary(1000, false, null), null, List(1000) { uri })
        assertEquals(1000, session.documentPages.size)
        val saved = repository.saveSession(session.documentSession, "M00 synthetic recovery 1000")
        assertTrue(saved is LibraryResult.Success)
        val id = (saved as LibraryResult.Success).value.id
        val opened = (repository.openDocument(id) as LibraryResult.Success).value.session
        val hashes = JSONArray(opened.pages.map { page ->
            requireNotNull(context.contentResolver.openInputStream(page.source.toAndroidUri())).use { digest(it.readBytes()) }
        })
        assertTrue(session.beginImportRequest(DocumentImportOrigin.PICKER))
        val importRoot = File(context.cacheDir, "document-imports").apply { mkdirs() }
        val orphan = File(importRoot, "import-page-m00-recovery-${UUID.randomUUID()}.jpg")
        source.copyTo(orphan)
        val resource = AcquiredResource(orphan.toURI().toString(), DocumentResourceOwnership.RME_OWNED_TEMPORARY,
            OwnedTemporaryFile(orphan.path, importRoot.path))
        assertEquals(PendingResourceRegistrationResult.Registered, session.registerPendingAcquisitionResources(listOf(resource)))
        state.writeText(JSONObject().put("document_id", id).put("page_hashes", hashes)
            .put("seed_pid", Process.myPid()).put("orphan_name", orphan.name).toString())
        // Deliberately leave the pending, owned import for the next process to recover.
        instrumentation.sendStatus(0, Bundle().apply { putString("m00_recovery_seed", "1000 pages durably saved; owned pending import staged") })
    }

    private suspend fun verify(requireProcessBoundary: Boolean) {
        val data = JSONObject(state.readText())
        if (requireProcessBoundary) assertTrue(data.getInt("seed_pid") != Process.myPid())
        val opened = repository.openDocument(data.getString("document_id"))
        assertTrue(opened is LibraryResult.Success)
        val pages = (opened as LibraryResult.Success).value.session.pages
        assertEquals(1000, pages.size)
        pages.forEachIndexed { index, page ->
            val hash = requireNotNull(context.contentResolver.openInputStream(page.source.toAndroidUri())).use { digest(it.readBytes()) }
            assertEquals(data.getJSONArray("page_hashes").getString(index), hash)
        }
        val orphan = File(File(context.cacheDir, "document-imports"), data.getString("orphan_name"))
        if (orphan.exists()) {
            deleteStaleDocumentImports(context.cacheDir, nowMillis = orphan.lastModified() + 24L * 60 * 60 * 1000 + 1)
            assertFalse(orphan.exists())
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("m00_recovery_verified",
            "{\"pages\":1000,\"all_saved_page_hashes_preserved\":true,\"process_boundary\":$requireProcessBoundary,\"stale_import_recovered\":true}") })
    }

    private suspend fun cleanup() {
        val data = JSONObject(state.readText())
        assertTrue(repository.deleteDocument(data.getString("document_id")) is LibraryResult.Success)
        state.delete()
        File(context.cacheDir, "shared-pdfs/m00-recovery.jpg").delete()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
