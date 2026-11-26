package org.synapseworks.pageharbor.workflow

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class WorkflowQueueInstrumentedTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    private lateinit var q:WorkflowQueue
    private lateinit var recipe:WorkflowRecipe
    @Before fun before() {
        check(context.packageName.endsWith(".phase2test"));root=File(context.noBackupFilesDir,"workflow-test-${UUID.randomUUID()}")
        q=WorkflowQueue(context,WorkflowStore(root));q.active=true
        q.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic preset",preset=WorkflowPreset.NORMAL))
        recipe=q.data().recipes.single()
    }
    @After fun after(){q.active=false;q.store.close();root.deleteRecursively()}
    private fun enqueue(key:String=UUID.randomUUID().toString()):WorkflowJob {
        val source=File(root,"capture-${UUID.randomUUID()}").apply{mkdirs()};val file=File(source,"input.pdf").apply{writeBytes("synthetic owned output".toByteArray())}
        val digest=workflowDigest(file.inputStream());recipe=q.data().recipes.single()
        return q.enqueue(recipe,file,digest.first,digest.second,WorkflowNaming.name(recipe.template,recipe.custom,Instant.EPOCH,ZoneId.of("UTC"),recipe.nextCounter),42,"owner",key,Instant.EPOCH.toString(),"UTC").also{source.deleteRecursively()}
    }
    @Test fun atomicCounterDuplicateSubmissionAndImmutableRecipeSnapshot() {
        val job=enqueue("same");assertEquals(2,q.data().recipes.single().nextCounter)
        assertEquals(job,enqueue("same"));assertEquals(2,q.data().recipes.single().nextCounter)
        val bytes=q.store.pdf(job.id).readBytes();q.saveRecipe(q.data().recipes.single().copy(custom="New",preset=WorkflowPreset.ENCRYPTED))
        assertEquals(job,q.data().jobs.single());assertArrayEquals(bytes,q.store.pdf(job.id).readBytes());assertEquals(WorkflowPreset.NORMAL,job.preset);assertNull(job.tree)
    }
    @Test fun restartPausesAuthorityAndReconcilesOnlyExplicitly() {
        val job=enqueue();q.enable(recipe.id,true);q.status(job.id,WorkflowStatus.WRITING,"content://unknown/partial")
        q.store.close();val reopened=WorkflowStore(root)
        assertFalse(reopened.data.recipes.single().enabled);assertEquals(WorkflowStatus.UNCERTAIN,reopened.data.jobs.single().status)
        assertTrue(reopened.pdf(job.id).isFile)
        val restarted=WorkflowQueue(context,reopened);restarted.active=true
        runBlocking { restarted.run(job.id);restarted.reconcile(job.id) }
        assertEquals(WorkflowStatus.UNCERTAIN,restarted.data().jobs.single().status)
        restarted.keepExternalAndCancel(job.id);assertFalse(reopened.pdf(job.id).exists());reopened.close()
    }
    @Test fun queueBoundCancellationAndTerminalMetadataLimit() {
        val jobs=(1..8).map{enqueue()};assertThrows(IllegalArgumentException::class.java){enqueue()}
        q.cancel(jobs.first().id);assertFalse(q.store.pdf(jobs.first().id).exists());enqueue()
        q.revoke(recipe.id);assertTrue(q.data().jobs.all{it.status==WorkflowStatus.CANCELLED});assertTrue(root.listFiles()!!.none{it.extension=="pdf"})
        q.deleteRecipe(recipe.id);assertTrue(q.data().recipes.isEmpty())
        repeat(16){q.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic bounded recipe",preset=WorkflowPreset.NORMAL))}
        repeat(10){assertThrows(IllegalArgumentException::class.java){q.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Excess recipe",preset=WorkflowPreset.NORMAL))}}
        assertEquals(16,q.data().recipes.size)
    }
    @Test fun unknownTreesMissingSourceLockAndStalePickerNeverWrite() {
        assertFalse(q.destination.localTree("content://remote.example/tree/primary%3AX"));assertFalse(q.destination.localTree("file:///storage/emulated/0/X"));assertFalse(q.destination.localTree("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata"))
        val job=enqueue();q.enable(recipe.id,true)
        val token=q.manualRequest(job.id)!!;assertNull(q.consumeManual("old",android.net.Uri.parse("content://unknown/file")))
        assertNull(q.consumeManual(token,null));assertNull(q.consumeManual(token,android.net.Uri.parse("content://unknown/file")))
        q.suspendWrites();runBlocking{q.run(job.id)};assertNotEquals(WorkflowStatus.SAVED,q.data().jobs.single().status)
        q.active=true;q.store.pdf(job.id).delete();runBlocking{q.run(job.id)};assertEquals(WorkflowStatus.FAILED,q.data().jobs.single().status)
    }
    @Test fun failedAtomicEnqueueKeepsCounterMetadataAndOwnedFilesExact() {
        enqueue();val before=q.data();val bytes=File(root,"queue.json").readBytes()
        withWorkflowMetadataBlocked(root) {
            assertThrows(Exception::class.java){enqueue()}
        }
        assertEquals(before,q.data());assertArrayEquals(bytes,File(root,"queue.json").readBytes())
        assertEquals(1,root.listFiles()!!.count{it.extension=="pdf"})
    }
    @Test fun pickerCrashIsUncertainAndInvalidationCannotBlindRetry() {
        val job=enqueue();q.enable(recipe.id,true);val token=q.manualRequest(job.id)!!
        assertEquals(WorkflowStatus.PICKER,q.data().jobs.single().status)
        q.store.close();val restarted=WorkflowStore(root);assertEquals(WorkflowStatus.UNCERTAIN,restarted.data.jobs.single().status)
        restarted.close()
        val restoredQueue=WorkflowQueue(context,WorkflowStore(root));restoredQueue.active=true
        assertNull(restoredQueue.consumeManual(token,android.net.Uri.parse("content://unknown/result")))
        assertEquals(WorkflowStatus.UNCERTAIN,restoredQueue.data().jobs.single().status);restoredQueue.store.close()
    }
    @Test fun competingOwnersCannotLoadCleanOrWriteTheSameQueue() {
        val job=enqueue();val frozen=q.store.pdf(job.id).readBytes();val second=WorkflowStore(root)
        assertFalse(second.healthy);assertTrue(second.busyOwner)
        assertThrows(IllegalStateException::class.java){second.commit(WorkflowData())}
        assertArrayEquals(frozen,q.store.pdf(job.id).readBytes());second.close();q.store.close()
        val next=WorkflowStore(root);assertTrue(next.healthy);assertEquals(job.id,next.data.jobs.single().id);next.close()
    }
    @Test fun corruptedMetadataAndOrphanCapturesCannotResumeOrLeakToBackup() {
        File(root,"capture-orphan").apply{mkdirs();File(this,"source").writeText("synthetic")}
        q.store.close();val recovered=WorkflowStore(root);assertFalse(File(root,"capture-orphan").exists());assertTrue(root.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        recovered.close();File(root,"queue.json").writeText("broken")
        val broken=WorkflowStore(root);assertFalse(broken.healthy);assertThrows(IllegalStateException::class.java){broken.commit(WorkflowData())}
        assertTrue(recovered.closed);broken.close()
    }
}
