package org.synapseworks.pageharbor.document.batch

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*

class BatchEditingCoordinatorTest {
    private fun source(n:Int=4)=DocumentSession((1L..n.toLong()).map{DocumentPage(DocumentPageId(it),DocumentResource("synthetic",DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN)})
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(block:()->Boolean)=runBlocking {withTimeout(30_000){while(!block())delay(10)}}
    private fun snapshot(vm:PageHarborSessionViewModel)=BatchDocumentCapture(vm.documentRevision,vm.documentSession)
    @Test fun cancellationDiscardsEvenAnUncooperativePreparedResultAndLateProgress() {
        val vm=PageHarborSessionViewModel();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        lateinit var editor:SelectedPageBatchEditor
        main{vm.installDocumentSessionForTest(source());editor=SelectedPageBatchEditor(scope,{snapshot(vm)},vm::commitBatchEdit,prepare={s,ids,e,_,progress ->
            check(Looper.myLooper()!=Looper.getMainLooper());entered.countDown();check(release.await(30,TimeUnit.SECONDS))
            try{prepareBatchEdit(s,ids,e,onProgress=progress)}finally{finished.countDown()}
        });editor.open();editor.selectAll();editor.setEdit(BatchPageEdit(brightness=20));editor.apply()}
        val revision=vm.documentRevision
        try {
            assertTrue(entered.await(30,TimeUnit.SECONDS));main{editor.apply();editor.cancel()}
            release.countDown();assertTrue(finished.await(30,TimeUnit.SECONDS));waitFor{editor.state?.outcome==BatchEditOutcome.CANCELLED}
            main{assertEquals(revision,vm.documentRevision);assertEquals(source(),vm.documentSession);assertEquals(0,editor.progress.value.second);assertFalse(editor.state!!.busy)}
        }finally{release.countDown();main{editor.close();scope.cancel()}}
    }
    @Test fun failureReportsNoChangesAndNeverPublishesPreparedPages() {
        val vm=PageHarborSessionViewModel();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);lateinit var editor:SelectedPageBatchEditor
        try {
            main{vm.installDocumentSessionForTest(source());editor=SelectedPageBatchEditor(scope,{snapshot(vm)},vm::commitBatchEdit,prepare={_,_,_,_,_->throw java.io.IOException()});editor.open();editor.selectAll();editor.setEdit(BatchPageEdit(contrast=25));editor.apply()}
            waitFor{editor.state?.outcome==BatchEditOutcome.FAILED};main{assertEquals(source(),vm.documentSession);assertFalse(editor.state!!.canUndo);assertFalse(editor.state!!.busy)}
        }finally{main{editor.close();scope.cancel()}}
    }
    @Test fun ownerChangeDuringPreparationRejectsAllOldResults() {
        val vm=PageHarborSessionViewModel();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val entered=CountDownLatch(1);val release=CountDownLatch(1);lateinit var editor:SelectedPageBatchEditor
        try {
            main{vm.installDocumentSessionForTest(source());editor=SelectedPageBatchEditor(scope,{snapshot(vm)},vm::commitBatchEdit,prepare={s,ids,e,_,p->entered.countDown();check(release.await(30,TimeUnit.SECONDS));prepareBatchEdit(s,ids,e,onProgress=p)});editor.open();editor.selectAll();editor.setEdit(BatchPageEdit(brightness=20));editor.apply()}
            assertTrue(entered.await(30,TimeUnit.SECONDS));main{vm.reorderPages(listOf(4,3,2,1))};release.countDown()
            waitFor{editor.state?.outcome==BatchEditOutcome.STALE};main{assertTrue(vm.documentPages.all{it.adjustments.isNeutral});assertEquals(listOf(4L,3L,2L,1L),vm.documentPages.map{it.id.value});assertFalse(editor.state!!.canUndo)}
        }finally{release.countDown();main{editor.close();scope.cancel()}}
    }
    @Test fun largeMetadataBatchPublishesOnceAndUndoCannotOverwriteAnotherEdit() {
        val vm=PageHarborSessionViewModel();var revision=0L
        main{vm.installDocumentSessionForTest(source(10_000));revision=vm.documentRevision;vm.batchEditor.open();vm.batchEditor.selectRange("1-5000");vm.batchEditor.setEdit(BatchPageEdit(brightness=20));vm.batchEditor.apply();vm.batchEditor.apply()}
        waitFor{vm.batchEditor.state?.outcome==BatchEditOutcome.APPLIED}
        main{
            assertEquals(revision+1,vm.documentRevision);assertEquals(5000,vm.batchEditor.progress.value.second)
            assertEquals(5000,vm.documentPages.count{it.adjustments.brightness==20})
            vm.setPageFilter(9999,DocumentFilter.GRAYSCALE);vm.batchEditor.undo()
            assertEquals(revision+2,vm.documentRevision);assertEquals(DocumentFilter.GRAYSCALE,vm.documentPages[9998].filter)
            assertTrue(vm.documentPages.take(5000).all{it.adjustments.brightness==20});assertEquals(BatchEditOutcome.STALE,vm.batchEditor.state!!.outcome);vm.batchEditor.close()
        }
    }
}
