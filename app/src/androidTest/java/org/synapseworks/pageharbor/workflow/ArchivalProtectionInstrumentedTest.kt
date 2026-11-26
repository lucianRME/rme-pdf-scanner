package org.synapseworks.pageharbor.workflow

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.archival.PdfA1bGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.*

class ArchivalProtectionInstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation();private val app=instrumentation.targetContext
    private lateinit var root:File;private lateinit var source:File
    @Before fun setup() { root=File(app.noBackupFilesDir,"m31-protection-${UUID.randomUUID()}").apply{mkdirs()};source=File(root,"source.png");source.writeBytes(instrumentation.context.assets.open("m31/rgb.png").use{it.readBytes()}) }
    @After fun cleanup(){root.deleteRecursively()}
    private fun document()=DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),512,384))))
    @Test fun sourceAliasesTruncationAndReplacementAreExcludedByActualDescriptor() {
        val guard=workflowSourceGuard(app,Uri.fromFile(source));val original=source.readBytes()
        val alias=Uri.parse("content://${app.packageName}.m30fault/alias")
        fun different(file:File)=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_WRITE).use { workflowDifferentSources(app,it,alias,listOf(guard)) }
        assertFalse(different(source));assertArrayEquals(original,source.readBytes())
        source.writeBytes(byteArrayOf());assertFalse(different(source));assertEquals(0,source.length())
        ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY).use {
            source.delete();source.writeBytes(original)
            assertNotEquals(guard.inode,workflowSourceGuard(app,Uri.fromFile(source)).inode)
            assertFalse(different(source));assertArrayEquals(original,source.readBytes())
            // Hold the old inode until the distinct-file check; inode reuse is a conservative refusal.
            val separate=File(root,"new.pdf").apply{writeBytes(byteArrayOf())};assertTrue(different(separate))
            ParcelFileDescriptor.open(separate,ParcelFileDescriptor.MODE_READ_WRITE).use { fd -> assertFalse(workflowDifferentSources(app,fd,alias,listOf(guard.copy(uri="content://unavailable.synthetic/source")))) }
        }
        assertTrue(guard.toString().contains("redacted"))
    }
    @Test fun sourceChangesCancellationMissingFilesAndCapacityFailuresCleanArchivalCaptures()=runBlocking<Unit> {
        val before=source.readBytes();var calls=0
        assertThrows(IllegalStateException::class.java){runBlocking { captureWorkflow(app,root,document(),WorkflowPreset.ARCHIVAL,null,charArrayOf()){++calls<4} } }
        assertTrue(root.listFiles()!!.none{it.isDirectory})
        calls=0
        assertThrows(IllegalArgumentException::class.java){runBlocking { captureWorkflow(app,root,document(),WorkflowPreset.ARCHIVAL,null,charArrayOf()){if(++calls==4)source.appendBytes(byteArrayOf(1));true} } }
        assertTrue(root.listFiles()!!.none{it.isDirectory});source.writeBytes(before)
        source.delete();assertThrows(Exception::class.java){runBlocking { captureWorkflow(app,root,document(),WorkflowPreset.ARCHIVAL,null,charArrayOf()){true} } };assertTrue(root.listFiles()!!.none{it.isDirectory})
        source.writeBytes(before);val tooMany=document().copy(pages=(1L..21L).map{document().pages.single().copy(id=DocumentPageId(it))})
        assertThrows(IllegalArgumentException::class.java){runBlocking { captureWorkflow(app,root,tooMany,WorkflowPreset.ARCHIVAL,null,charArrayOf()){true} } }
    }
    @Test fun generatorRejectsOcrMalformedImagesAndCancelledWritesWithoutRetainedOutput()=runBlocking<Unit> {
        val output=File(root,"test.pdf");val generator=PdfA1bGenerator(app)
        assertThrows(IllegalArgumentException::class.java){runBlocking { generator.generate(emptyList(),output,root) } }
        assertThrows(IllegalArgumentException::class.java){runBlocking { generator.generate(listOf(SearchablePdfPage({source.inputStream()},OcrPageResult(0,"synthetic"))),output,root) } }
        assertThrows(Exception::class.java){runBlocking { generator.generate(listOf(SearchablePdfPage({source.inputStream()})),output,root) } };assertFalse(output.exists())
        assertThrows(IllegalStateException::class.java){runBlocking { generator.generate(listOf(SearchablePdfPage({source.inputStream()})),output,root){false} } };assertFalse(output.exists())
    }
    @Test fun privateWorkflowOneMigratesAtomicallyToTwoAndRetainsFrozenJobs()=runBlocking<Unit> {
        val store=WorkflowStore(File(root,"queue"));assertTrue(store.healthy);val queue=WorkflowQueue(app,store);queue.active=true
        queue.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic migration",preset=WorkflowPreset.NORMAL));val recipe=queue.data().recipes.single()
        val captured=captureWorkflow(app,store.root,document(),WorkflowPreset.NORMAL,null,charArrayOf()){true}
        val at="2026-11-08T12:00:00Z";val name=WorkflowNaming.name(recipe.template,recipe.custom,java.time.Instant.parse(at),java.time.ZoneId.of("UTC"),1)
        val job=queue.enqueue(recipe,captured.output,captured.digest,captured.bytes,name,1,"synthetic","migration",at,"UTC");val frozen=store.pdf(job.id).readBytes()
        queue.status(job.id,WorkflowStatus.WRITING);queue.closeOwner()
        val file=File(root,"queue/queue.json");val json=JSONObject(file.readText());json.put("version",1);json.getJSONArray("jobs").getJSONObject(0).remove("sourceGuards");file.writeText(json.toString())
        val reopened=WorkflowStore(File(root,"queue"));try {
            assertTrue(reopened.healthy);assertEquals(recipe.id,reopened.data.recipes.single().id);assertFalse(reopened.data.recipes.single().enabled)
            assertEquals(2,reopened.data.recipes.single().nextCounter);assertEquals(WorkflowStatus.UNCERTAIN,reopened.data.jobs.single().status)
            assertArrayEquals(frozen,reopened.pdf(job.id).readBytes());assertEquals(2,JSONObject(file.readText()).getInt("version"))
        } finally{reopened.close()}
        val bad=JSONObject(file.readText()).put("version",4);file.writeText(bad.toString());val declined=WorkflowStore(File(root,"queue"))
        try{assertFalse(declined.healthy);assertEquals(bad.toString(),file.readText());assertArrayEquals(frozen,declined.pdf(job.id).readBytes())}finally{declined.close()}
    }
    @Test fun archivalJobsPersistSourceExclusionsAcrossRecoveryAndRejectMissingGuards()=runBlocking<Unit> {
        val store=WorkflowStore(File(root,"queue"));val queue=WorkflowQueue(app,store);queue.active=true
        try {
            queue.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic archival",preset=WorkflowPreset.ARCHIVAL));val recipe=queue.data().recipes.single()
            val captured=captureWorkflow(app,store.root,document(),WorkflowPreset.ARCHIVAL,null,charArrayOf()){true}
            val at="2026-11-08T12:00:00Z";val name=WorkflowNaming.name(recipe.template,recipe.custom,java.time.Instant.parse(at),java.time.ZoneId.of("UTC"),1)
            assertThrows(IllegalArgumentException::class.java){queue.enqueue(recipe,captured.output,captured.digest,captured.bytes,name,1,"synthetic","same",at,"UTC")}
            val job=queue.enqueue(recipe,captured.output,captured.digest,captured.bytes,name,1,"synthetic","same",at,"UTC",captured.sourceGuards);val bytes=store.pdf(job.id).readBytes();queue.closeOwner()
            val next=WorkflowStore(store.root);try { assertTrue(next.healthy);assertEquals(job.sourceGuards,next.data.jobs.single().sourceGuards);assertArrayEquals(bytes,next.pdf(job.id).readBytes());assertFalse(next.data.recipes.single().enabled) } finally {next.close()}
        } finally { queue.closeOwner() }
    }
}
