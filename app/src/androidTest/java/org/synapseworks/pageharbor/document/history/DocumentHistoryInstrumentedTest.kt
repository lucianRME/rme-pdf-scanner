package org.synapseworks.pageharbor.document.history

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.curvature.*
import org.synapseworks.pageharbor.document.batch.*
import org.synapseworks.pageharbor.document.protectedpdf.*
import org.synapseworks.pageharbor.image.*
import com.tom_roush.pdfbox.pdmodel.PDDocument

class DocumentHistoryInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/history-${UUID.randomUUID()}").apply{mkdirs()}}
    private val models=mutableListOf<PageHarborSessionViewModel>()
    @After fun cleanup()=runBlocking {for(v in models){withContext(Dispatchers.Main.immediate){v.clearScan()};v.awaitHistoryIdle();withContext(Dispatchers.Main.immediate){v.disposeHistoryForTest()}};root.deleteRecursively();Unit}
    private fun source(id:Long)=CurvatureFixtures.source(root,1,id).let{p->p.copy(source=p.source.copy(ownership=DocumentResourceOwnership.RME_OWNED_TEMPORARY,ownedTemporaryFile=OwnedTemporaryFile(File(Uri.parse(p.source.reference).path!!).path,root.path)))}
    private suspend fun model(pages:List<DocumentPage>,limits:HistoryLimits=HistoryLimits(),access:HistorySourceAccess=AndroidHistorySourceAccess(app)):PageHarborSessionViewModel {
        val v=PageHarborSessionViewModel();models+=v;withContext(Dispatchers.Main.immediate){v.installDocumentSessionForTest(DocumentSession(pages));v.configureHistory(access,limits)};v.awaitHistoryIdle();return v
    }
    private suspend fun edit(v:PageHarborSessionViewModel,f:()->Unit){withContext(Dispatchers.Main.immediate){f()};v.awaitHistoryIdle()}
    private fun file(p:DocumentPage)=p.source.ownedTemporaryFile?.let{File(it.path)}?:File(Uri.parse(p.source.reference).path!!)
    @Test fun realMixedAcquisitionBatchSplitCurvatureUndoAllRedoAllReuseExactBytes()=runBlocking {
        val a=source(10);val b=source(20);val c=source(30);val v=model(listOf(a,b));val snapshots=mutableListOf(v.documentSession)
        suspend fun step(f:()->Unit){edit(v,f);snapshots+=v.documentSession}
        step{assertTrue(v.rotatePageClockwise(20))};step{assertTrue(v.setPageFilter(20,DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1))}
        step{assertTrue(v.setPageAdjustments(20,ManualImageAdjustments(20,-25)))}
        val expected=org.synapseworks.pageharbor.document.batch.BatchDocumentCapture(v.documentRevision,v.documentSession)
        step{assertTrue(v.commitBatchEdit(expected,prepareBatchEdit(v.documentSession,setOf(10,20),BatchPageEdit(brightness=12)).session))}
        step{assertTrue(v.reorderPages(listOf(20,10)))};step{assertTrue(v.removePage(20))}
        step{val t=v.beginContinuousRequest()!!;assertTrue(v.completeContinuousRequest(t,v.documentRevision,DocumentAcquisitionInput(listOf(AcquiredDocumentPage(AcquiredResource(c.source.reference,c.source.ownership,c.source.ownedTemporaryFile),DocumentSourceCategory.SCAN,"image/jpeg",c.imageMetadata)))))}
        val split=BookTestFixtures.prepare(app,v.documentSession,0,BookSplitLine(.5f,.5f))
        var curve:CurvatureFixtures.Prepared?=null
        try{step{assertTrue(v.applyBookSplit(v.documentSession,v.documentRevision,listOf(split.replacement)))}
            curve=CurvatureFixtures.prepare(app,v.documentSession,0,CurvatureField.manual(listOf(.04f,0f,-.05f,0f,.04f),.04f))
            step{assertTrue(v.applyCurvature(v.documentSession,v.documentRevision,curve!!.replacement))}
            val resources=DocumentHistory.resources(snapshots).filter{it.ownedTemporaryFile!=null}.map{File(it.ownedTemporaryFile!!.path)}.distinct()
            val hashes=resources.associateWith{BookTestFixtures.sha(it)};val revision=v.documentRevision
            repeat(3){for(s in snapshots.dropLast(1).reversed()){edit(v){assertTrue(v.undoDocument())};assertSame(s,v.documentSession)}
                assertFalse(v.historyState.dirty);for(s in snapshots.drop(1)){edit(v){assertTrue(v.redoDocument())};assertSame(s,v.documentSession)}}
            assertEquals(revision+6*(snapshots.size-1),v.documentRevision);hashes.forEach{(f,h)->assertEquals(h,BookTestFixtures.sha(f))}
            assertFalse(v.applyBookSplit(snapshots.first(),1,listOf(split.replacement)))
            edit(v){v.clearScan()};assertTrue(resources.none{it.exists()})
        }finally{curve?.close();split.close()}
    }
    @Test fun batchDialogUndoUsesOneGeneralEntryAndRedoNeverRunsBatchAgain()=runBlocking {
        val v=model(listOf(source(10),source(20)));val before=v.documentSession
        edit(v){v.batchEditor.open();v.batchEditor.selectAll();v.batchEditor.setEdit(BatchPageEdit(brightness=15,clockwiseQuarterTurns=1));v.batchEditor.apply()}
        withTimeout(30_000){while(v.batchEditor.state?.busy==true)delay(10)};v.awaitHistoryIdle();val after=v.documentSession
        assertEquals(1,v.historyState.undoCount);assertTrue(v.batchEditor.canUndoCommitted)
        edit(v){v.batchEditor.undo()};assertSame(before,v.documentSession);assertEquals(BatchEditOutcome.UNDONE,v.batchEditor.state!!.outcome);assertEquals(1,v.historyState.redoCount)
        edit(v){v.batchEditor.close();assertTrue(v.redoDocument())};assertSame(after,v.documentSession)
        edit(v){assertTrue(v.rotatePageClockwise(10))};assertEquals(2,v.historyState.undoCount);edit(v){v.clearScan()}
    }
    @Test fun byteLimitCanonicalAliasesLeaseAndBoundaryNeverDeleteLibraryOrExternal()=runBlocking {
        val a=source(10);val b=source(20);val alias=b.copy(id=DocumentPageId(21),source=b.source.copy(reference=Uri.fromFile(File(root,"./"+file(b).name)).toString(),ownedTemporaryFile=OwnedTemporaryFile(File(root,"./"+file(b).name).path,root.path)))
        val external=source(30).let{it.copy(source=it.source.copy(ownership=DocumentResourceOwnership.USER_OR_EXTERNAL,ownedTemporaryFile=null))}
        val library=source(40).let{it.copy(source=it.source.copy(ownership=DocumentResourceOwnership.RME_OWNED_LIBRARY,ownedTemporaryFile=null,ownedLibraryFile=OwnedLibraryFile(file(it).path,root.path)))}
        val v=model(listOf(a,b,alias,external,library),HistoryLimits(temporaryBytes=1));val lease=withContext(Dispatchers.Main.immediate){v.acquireDocumentSessionLease()!!}
        edit(v){assertTrue(v.removePage(20))};edit(v){assertTrue(v.removePage(21))};assertEquals(0L,v.historyState.retainedBytes);assertTrue(file(b).exists())
        edit(v){v.releaseDocumentSessionLease(lease)};assertEquals(0,v.historyState.undoCount);assertTrue(v.historyState.limited);assertFalse(file(b).exists())
        edit(v){v.clearScan()};assertFalse(file(a).exists());assertTrue(file(external).exists());assertTrue(file(library).exists())
    }
    @Test fun blockedRestoreRejectsRapidEditsAndBackgroundOrBoundaryCancelsPublication()=runBlocking {
        val blocked=AtomicBoolean(false);var entered=CountDownLatch(1);var release=CountDownLatch(1)
        val delegate=AndroidHistorySourceAccess(app)
        val v=model(listOf(source(10),source(20)),access=HistorySourceAccess{r->if(blocked.get()){entered.countDown();check(release.await(10,TimeUnit.SECONDS))};delegate.open(r)})
        edit(v){assertTrue(v.removePage(20))};val current=v.documentSession;val revision=v.documentRevision
        blocked.set(true);withContext(Dispatchers.Main.immediate){assertTrue(v.undoDocument());assertFalse(v.undoDocument());assertFalse(v.rotatePageClockwise(10))}
        assertTrue(entered.await(10,TimeUnit.SECONDS));withContext(Dispatchers.Main.immediate){v.setHistoryActive(false)};blocked.set(false);release.countDown();v.awaitHistoryIdle()
        assertSame(current,v.documentSession);assertEquals(revision,v.documentRevision);assertEquals(1,v.historyState.undoCount)
        edit(v){v.setHistoryActive(true)};blocked.set(true);entered=CountDownLatch(1);release=CountDownLatch(1)
        withContext(Dispatchers.Main.immediate){assertTrue(v.undoDocument())};assertTrue(entered.await(10,TimeUnit.SECONDS))
        withContext(Dispatchers.Main.immediate){v.clearScan()};blocked.set(false);release.countDown();v.awaitHistoryIdle();assertTrue(v.documentPages.isEmpty());assertEquals(0,v.historyState.undoCount)
    }
    @Test fun revokedProviderFailureKeepsPositionThenRestoresAfterPermissionReturns()=runBlocking {
        val revoke=AtomicBoolean(false);val delegate=AndroidHistorySourceAccess(app);val v=model(listOf(source(10),source(20)),access=HistorySourceAccess{r->if(revoke.get())throw SecurityException();delegate.open(r)})
        edit(v){v.removePage(20)};val current=v.documentSession;val revision=v.documentRevision;revoke.set(true)
        edit(v){assertTrue(v.undoDocument())};assertSame(current,v.documentSession);assertEquals(revision,v.documentRevision);assertTrue(v.historyState.unavailable)
        revoke.set(false);edit(v){assertTrue(v.undoDocument())};assertEquals(2,v.documentPages.size);edit(v){v.clearScan()}
    }
    @Test fun restoredContentNormalPasswordAndFrozenCaptureKeepSourceIdentity()=runBlocking {
        val before=DocumentSession(listOf(source(10)));val prepared=CurvatureFixtures.prepare(app,before)
        val history=HistoryFixtures.restored(app,prepared)
        try{val restored=history.session;val p=restored.pages.single();val hash=BookTestFixtures.sha(file(p))
            val pdf=recomposeNormalPdf(app,(normalPdfExportPlan(restored)as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
            try{val output=File(root,"protected.pdf");assertTrue(PasswordProtectedPdf().create(pdf.file,output,"synthetic-history-pass".toCharArray())is PasswordPdfResult.Success)
                PDDocument.load(output,"synthetic-history-pass").use{assertTrue(it.isEncrypted);assertEquals(1,it.numberOfPages)};assertEquals(hash,BookTestFixtures.sha(file(p)))
                val captured=pdf.file.readBytes();val v=model(restored.pages)
                val frozen=withContext(Dispatchers.Main.immediate){PasswordPdfExportViewModel(app.applicationContext as android.app.Application)}
                try{withContext(Dispatchers.Main.immediate){frozen.open(normalPdfExportPlan(restored))};val frozenFile=frozen.state.source!!;val frozenHash=BookTestFixtures.sha(frozenFile)
                    edit(v){v.rotatePageClockwise(p.id.value)};edit(v){assertTrue(v.undoDocument())};edit(v){assertTrue(v.redoDocument())}
                    assertEquals(frozenHash,BookTestFixtures.sha(frozenFile));PDDocument.load(frozenFile).use{assertEquals(1,it.numberOfPages)};assertArrayEquals(captured,pdf.file.readBytes())
                }finally{withContext(Dispatchers.Main.immediate){frozen.close()}}
            }finally{deleteNormalPdfRecomposition(pdf.file)}
        }finally{history.close();prepared.close()}
    }
    @Test fun finalDisposalKeepsActiveCapturedSourcesUntilTheLastLeaseExits()=runBlocking {
        val a=source(10);val b=source(20);val store=ViewModelStore()
        val v=withContext(Dispatchers.Main.immediate){ViewModelProvider(store,ViewModelProvider.NewInstanceFactory())[PageHarborSessionViewModel::class.java].also{
            it.installDocumentSessionForTest(DocumentSession(listOf(a,b)));it.configureHistory(AndroidHistorySourceAccess(app))}}
        v.awaitHistoryIdle();val lease=withContext(Dispatchers.Main.immediate){v.acquireDocumentSessionLease()!!}
        edit(v){assertTrue(v.removePage(20))};withContext(Dispatchers.Main.immediate){store.clear()};v.awaitHistoryIdle()
        assertTrue(file(a).exists()&&file(b).exists());assertEquals(lease.session.pages,listOf(a,b))
        edit(v){v.releaseDocumentSessionLease(lease)};assertFalse(file(a).exists());assertFalse(file(b).exists())
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=28)
    @Test fun admissionValidationAccountingAndCleanupNeverPerformMainThreadDiskIo()=runBlocking {
        val a=source(10);val b=source(20);val v=PageHarborSessionViewModel();models+=v;val violations=java.util.concurrent.atomic.AtomicInteger()
        withContext(Dispatchers.Main.immediate){val prior=android.os.StrictMode.getThreadPolicy();android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyListener(java.util.concurrent.Executor{it.run()}){violation->
            if(violation.stackTrace.any{it.className.startsWith("org.synapseworks.pageharbor.document.history")||it.className.startsWith("org.synapseworks.pageharbor.document.session")})violations.incrementAndGet()
        }.build())
            try{v.installDocumentSessionForTest(DocumentSession(listOf(a,b)));v.configureHistory(AndroidHistorySourceAccess(app));v.awaitHistoryIdle();v.removePage(20);v.awaitHistoryIdle();assertTrue(v.undoDocument());v.awaitHistoryIdle();v.clearScan();v.awaitHistoryIdle()}
            finally{android.os.StrictMode.setThreadPolicy(prior)}};assertEquals(0,violations.get())
    }
}
