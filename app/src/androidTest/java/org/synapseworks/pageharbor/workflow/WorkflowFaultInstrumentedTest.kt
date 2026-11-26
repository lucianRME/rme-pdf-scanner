package org.synapseworks.pageharbor.workflow

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class WorkflowFaultInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File;private lateinit var q:WorkflowQueue;private lateinit var job:WorkflowJob
    @Before fun before() {
        root=File(app.noBackupFilesDir,"m30-fault-${UUID.randomUUID()}");q=WorkflowQueue(app,WorkflowStore(root));q.active=true
        q.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic faults",preset=WorkflowPreset.NORMAL))
        nextJob();q.enable(job.recipeId,true)
    }
    private fun nextJob() {
        val recipe=q.data().recipes.single();val file=File(root,"input").apply{writeBytes(ByteArray(150000){3})};val digest=workflowDigest(file.inputStream())
        val at=java.time.Instant.parse("2026-11-08T00:00:00Z");val name=WorkflowNaming.name(recipe.template,recipe.custom,at,java.time.ZoneId.of("UTC"),recipe.nextCounter)
        job=q.enqueue(recipe,file,digest.first,digest.second,name,1,"owner",UUID.randomUUID().toString(),at.toString(),"UTC")
    }
    @After fun after() { WorkflowFaultProvider.release?.countDown();q.active=false;q.store.close();root.deleteRecursively() }
    @Test fun openAndWriteFailuresRemainUncertainAndNeverRetryBlindly() = runBlocking {
        val uri=WorkflowFaultProvider.reset(app,job.name);WorkflowFaultProvider.readOnlyWrite=true;q.run(job.id,uri)
        assertEquals(WorkflowStatus.UNCERTAIN,q.data().jobs.single().status);assertTrue(WorkflowFaultProvider.bytes(app).isEmpty())
        val opens=WorkflowFaultProvider.opened;q.run(job.id,uri);assertEquals(opens,WorkflowFaultProvider.opened)
        q.reconcile(job.id);assertEquals(WorkflowStatus.UNCERTAIN,q.data().jobs.single().status)
    }
    @Test fun lockOrCancellationWhileDescriptorIsOpeningPreventsAnyChunk()=runBlocking {
        for(lock in listOf(true,false)) {
            if(!lock) { q.keepExternalAndCancel(job.id);q.remove(job.id);nextJob() };val uri=WorkflowFaultProvider.reset(app,job.name)
            WorkflowFaultProvider.entered=CountDownLatch(1);WorkflowFaultProvider.release=CountDownLatch(1)
            val work=launch(Dispatchers.IO){q.run(job.id,uri)}
            assertTrue(WorkflowFaultProvider.entered!!.await(10,TimeUnit.SECONDS))
            if(lock)q.suspendWrites()else q.cancel(job.id)
            WorkflowFaultProvider.release!!.countDown();work.join();assertTrue(WorkflowFaultProvider.bytes(app).isEmpty());assertEquals(WorkflowStatus.UNCERTAIN,q.data().jobs.single().status);q.active=true
        }
    }
    @Test fun existingNonemptyAliasesAreNeverOpenedAndCorruptedSnapshotsFailBeforeDestination()=runBlocking {
        val uri=WorkflowFaultProvider.reset(app,job.name);File(app.cacheDir,"m30-fault-target").writeBytes(byteArrayOf(9))
        q.run(job.id,uri);assertEquals(0,WorkflowFaultProvider.opened);assertArrayEquals(byteArrayOf(9),WorkflowFaultProvider.bytes(app));assertEquals(WorkflowStatus.UNCERTAIN,q.data().jobs.single().status)
        q.keepExternalAndCancel(job.id);q.remove(job.id);nextJob();q.store.pdf(job.id).appendBytes(byteArrayOf(1));WorkflowFaultProvider.reset(app,job.name)
        q.run(job.id,uri);assertEquals(0,WorkflowFaultProvider.opened);assertEquals(WorkflowStatus.FAILED,q.data().jobs.single().status)
    }
    @Test fun failedRevokeMetadataStillClosesTheWriteGate()=runBlocking<Unit> {
        val uri=WorkflowFaultProvider.reset(app,job.name)
        WorkflowFaultProvider.entered=CountDownLatch(1);WorkflowFaultProvider.release=CountDownLatch(1)
        val work=async(Dispatchers.IO){runCatching{q.run(job.id,uri)}}
        assertTrue(WorkflowFaultProvider.entered!!.await(10,TimeUnit.SECONDS))
        withWorkflowMetadataBlocked(root) {
            assertThrows(Exception::class.java){q.revoke(job.recipeId)}
        }
        WorkflowFaultProvider.release!!.countDown();work.await()
        assertTrue(WorkflowFaultProvider.bytes(app).isEmpty());assertNotEquals(WorkflowStatus.SAVED,q.data().jobs.single().status)
    }
    @Test fun reconciliationRequiresTheReviewedNameAsWellAsExactBytes()=runBlocking {
        val uri=WorkflowFaultProvider.reset(app,job.name)
        File(app.cacheDir,"m30-fault-target").writeBytes(q.store.pdf(job.id).readBytes())
        q.status(job.id,WorkflowStatus.UNCERTAIN,uri.toString());WorkflowFaultProvider.name="Another.pdf"
        q.reconcile(job.id);assertEquals(WorkflowStatus.UNCERTAIN,q.data().jobs.single().status)
        WorkflowFaultProvider.name=job.name;q.reconcile(job.id);assertEquals(WorkflowStatus.SAVED,q.data().jobs.single().status)
    }
    @Test fun competingRunsSerializeAndOnlyOnePublishes()=runBlocking {
        val uri=WorkflowFaultProvider.reset(app,job.name)
        coroutineScope{repeat(4){launch(Dispatchers.IO){q.run(job.id,uri)}}}
        assertEquals(WorkflowStatus.SAVED,q.data().jobs.single().status);assertEquals(1,WorkflowFaultProvider.opened);assertEquals(job.bytes,WorkflowFaultProvider.bytes(app).size.toLong())
    }
}
