package org.synapseworks.pageharbor.document.history

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.curvature.*

class HistorySessionTest {
    private fun page(root:File,id:Long,external:Boolean=false):DocumentPage {
        val f=File(root,"$id.bin").apply{writeBytes(ByteArray(16){id.toByte()})}
        return DocumentPage(DocumentPageId(id),DocumentResource(f.toURI().toString(),if(external)DocumentResourceOwnership.USER_OR_EXTERNAL else DocumentResourceOwnership.RME_OWNED_TEMPORARY,
            if(external)null else OwnedTemporaryFile(f.path,root.path)),DocumentSourceCategory.SCAN, imageMetadata=DocumentImageMetadata(16,10,10))
    }
    private fun file(p:DocumentPage)=File(java.net.URI(p.source.reference))
    private suspend fun vm(pages:List<DocumentPage>,limits:HistoryLimits=HistoryLimits()):PageHarborSessionViewModel {
        val vm=PageHarborSessionViewModel();vm.installDocumentSessionForTest(DocumentSession(pages))
        vm.configureHistory(HistorySourceAccess{r->File(java.net.URI(r.reference)).inputStream()},limits,currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher);vm.awaitHistoryIdle();return vm
    }
    @Test fun deletionUndoRedoRevisionBranchAndBoundaryKeepExactlyReachableFiles()=runBlocking {
        val root=Files.createTempDirectory("history-jvm-").toFile()
        try{val a=page(root,1);val b=page(root,2);val external=page(root,3,true);val v=vm(listOf(a,b,external));val rev=v.documentRevision
            assertTrue(v.removePage(2));v.awaitHistoryIdle();assertTrue(file(b).exists());assertEquals(1,v.historyState.undoCount)
            assertTrue(v.undoDocument());v.awaitHistoryIdle();assertSame(b,v.documentPages[1]);assertEquals(rev+2,v.documentRevision)
            assertTrue(v.redoDocument());v.awaitHistoryIdle();assertEquals(rev+3,v.documentRevision)
            assertTrue(v.undoDocument());v.awaitHistoryIdle();assertTrue(v.setPageFilter(1,DocumentFilter.GRAYSCALE));v.awaitHistoryIdle();assertEquals(0,v.historyState.redoCount)
            v.clearScan();v.awaitHistoryIdle();assertFalse(file(a).exists());assertFalse(file(b).exists());assertTrue(file(external).exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun missingChangedAndRevokedRestorationNeverMovesCursorOrCurrentRevision()=runBlocking {
        val root=Files.createTempDirectory("history-jvm-").toFile()
        try{val a=page(root,1);val b=page(root,2);val v=vm(listOf(a,b));v.removePage(2);v.awaitHistoryIdle();val before=v.documentSession;val rev=v.documentRevision
            file(b).writeBytes(byteArrayOf(9));assertTrue(v.undoDocument());v.awaitHistoryIdle();assertSame(before,v.documentSession);assertEquals(rev,v.documentRevision);assertEquals(1,v.historyState.undoCount);assertTrue(v.historyState.unavailable)
            file(b).delete();assertTrue(v.undoDocument());v.awaitHistoryIdle();assertSame(before,v.documentSession);assertEquals(rev,v.documentRevision)
            file(b).writeBytes(ByteArray(16){2});assertTrue(v.undoDocument());v.awaitHistoryIdle();assertEquals(2,v.documentPages.size)
            v.clearScan();v.awaitHistoryIdle()
        }finally{root.deleteRecursively()}
    }
    @Test fun evictedHistorySourceSurvivesAnExportLeaseThenIsReleasedAfterLeaseEnds()=runBlocking {
        val root=Files.createTempDirectory("history-jvm-").toFile()
        try{val a=page(root,1);val b=page(root,2);val v=vm(listOf(a,b),HistoryLimits(entries=1));val lease=v.acquireDocumentSessionLease()!!
            v.removePage(2);v.awaitHistoryIdle();v.setPageFilter(1,DocumentFilter.GRAYSCALE);v.awaitHistoryIdle();assertEquals(1,v.historyState.undoCount);assertTrue(file(b).exists())
            v.releaseDocumentSessionLease(lease);v.awaitHistoryIdle();assertFalse(file(b).exists());assertTrue(file(a).exists());v.clearScan();v.awaitHistoryIdle()
        }finally{root.deleteRecursively()}
    }
    @Test fun splitAndCurvatureTransactionsReplayExactDerivedSourcesAndRejectOldCallbacks()=runBlocking {
        val root=Files.createTempDirectory("history-jvm-").toFile()
        try{val original=page(root,1);val left=page(root,11);val right=page(root,12);val curved=page(root,13);val v=vm(listOf(original));val before=v.documentSession;val revision=v.documentRevision
            fun derived(p:DocumentPage)=BookDerivedPage(p.source,p.imageMetadata)
            val split=BookPageReplacement(original,listOf(derived(left),derived(right)));assertTrue(v.applyBookSplit(before,revision,listOf(split)));v.awaitHistoryIdle();val halves=v.documentSession
            assertTrue(v.applyCurvature(halves,v.documentRevision,CurvatureReplacement(halves.pages[0],derived(curved))));v.awaitHistoryIdle();val corrected=v.documentSession
            repeat(3){assertTrue(v.undoDocument());v.awaitHistoryIdle();assertSame(halves,v.documentSession);assertTrue(v.undoDocument());v.awaitHistoryIdle();assertSame(before,v.documentSession)
                assertFalse(v.applyBookSplit(before,revision,listOf(split)));assertTrue(v.redoDocument());v.awaitHistoryIdle();assertTrue(v.redoDocument());v.awaitHistoryIdle();assertSame(corrected,v.documentSession)}
            assertTrue(file(original).exists()&&file(left).exists()&&file(right).exists()&&file(curved).exists());v.clearScan();v.awaitHistoryIdle();assertTrue(root.listFiles().isNullOrEmpty())
        }finally{root.deleteRecursively()}
    }
    @Test fun directPdfProvenanceDirtyStateAndSavedLibraryRebaseRemainTruthful()=runBlocking {
        val root=Files.createTempDirectory("history-jvm-").toFile()
        try{val a=page(root,1);val pdf=page(root,9).source;val initial=DocumentSession(listOf(a),directPdfSource=pdf,directPdfPageIds=listOf(a.id),originalPdfSources=listOf(pdf),libraryDocument=LibraryDocumentReference("saved","Old"))
            val v=PageHarborSessionViewModel();v.installDocumentSessionForTest(initial);v.configureHistory(HistorySourceAccess{r->File(java.net.URI(r.reference)).inputStream()},main=currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher);v.awaitHistoryIdle()
            assertTrue(v.documentSession.canUseDirectPdf);assertFalse(v.setPageFilter(1,DocumentFilter.ORIGINAL));assertEquals(0,v.historyState.undoCount)
            v.setPageFilter(1,DocumentFilter.GRAYSCALE);v.awaitHistoryIdle();assertFalse(v.documentSession.canUseDirectPdf);assertTrue(v.historyState.dirty)
            v.updateLibraryReference("Renamed","folder");v.awaitHistoryIdle();assertTrue(v.historyState.dirty)
            assertTrue("navigation start: "+v.historyState,v.undoDocument())
            v.awaitHistoryIdle()
            assertTrue("restored direct eligibility: "+v.historyState,v.documentSession.canUseDirectPdf)
            assertFalse(v.historyState.dirty);assertEquals("Renamed",v.documentSession.libraryDocument!!.title)
            assertSame(pdf,v.documentSession.directPdfSource);assertEquals(listOf(pdf),v.documentSession.originalPdfSources)
            v.installDocumentSessionForTest(v.documentSession);v.awaitHistoryIdle();assertEquals(0,v.historyState.redoCount);assertFalse(v.historyState.dirty)
            v.clearScan();v.awaitHistoryIdle();v.disposeHistoryForTest()
        }finally{root.deleteRecursively()}
    }

    @Test fun scannerPickerAndInboundAppendEachRecordOneWholeAcceptedAcquisition()=runBlocking {
        val root=Files.createTempDirectory("history-jvm-").toFile()
        try{val v=vm(listOf(page(root,100)));val states=mutableListOf(v.documentSession)
            for(route in 0..2){val inputs=listOf(page(root,10L+route*2),page(root,11L+route*2))
                if(route==0){assertNotNull(v.beginScannerRequest());v.completeScannerReferencesForTest(org.synapseworks.pageharbor.scanner.createScannerResultSummary(2,null),scannedPageReferences=inputs.map{it.source.reference})}
                else{assertTrue(v.beginImportRequest(if(route==1)org.synapseworks.pageharbor.document.importing.DocumentImportOrigin.PICKER else org.synapseworks.pageharbor.document.importing.DocumentImportOrigin.INBOUND_SHARE))
                    v.completeImportRequest(org.synapseworks.pageharbor.document.importing.DocumentImportPreparationResult.Success(DocumentAcquisitionInput(inputs.map{p->AcquiredDocumentPage(AcquiredResource(p.source.reference,p.source.ownership,p.source.ownedTemporaryFile),DocumentSourceCategory.SELECTED_IMAGE,"image/jpeg",p.imageMetadata)}),0))}
                v.awaitHistoryIdle();states+=v.documentSession;assertEquals(route+1,v.historyState.undoCount);assertEquals(3+route*2,v.documentPages.size)
            }
            assertTrue(v.beginImportRequest(org.synapseworks.pageharbor.document.importing.DocumentImportOrigin.PICKER));v.cancelImportRequest();v.awaitHistoryIdle();assertEquals(3,v.historyState.undoCount)
            for(target in states.dropLast(1).reversed()){assertTrue(v.undoDocument());v.awaitHistoryIdle();assertSame(target,v.documentSession)}
            for(target in states.drop(1)){assertTrue(v.redoDocument());v.awaitHistoryIdle();assertSame(target,v.documentSession)}
            v.clearScan();assertFalse(v.historyState.busy);val next=v.beginContinuousRequest()!!;v.cancelContinuousRequest(next)
            v.awaitHistoryIdle();v.disposeHistoryForTest()
        }finally{root.deleteRecursively()}
    }

    @Test fun openingANewBaselineReadmitsChangedProviderBytesEvenAtTheSameReference()=runBlocking {
        val root=Files.createTempDirectory("history-jvm-").toFile()
        try{val a=page(root,1);val v=vm(listOf(a));val initial=v.documentSession
            val reopened=initial.copy();file(a).writeBytes(ByteArray(16){42});v.installDocumentSessionForTest(reopened);v.awaitHistoryIdle()
            v.setPageFilter(1,DocumentFilter.GRAYSCALE);v.awaitHistoryIdle();assertTrue(v.undoDocument());v.awaitHistoryIdle();assertSame(reopened,v.documentSession);assertFalse(v.historyState.unavailable)
            v.clearScan();v.awaitHistoryIdle();v.disposeHistoryForTest()
        }finally{root.deleteRecursively()}
    }

}
