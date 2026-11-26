package org.synapseworks.pageharbor.table

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.*

class TableExportInstrumentedTest {
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext.applicationContext as Application
    private val models=mutableListOf<TableViewModel>()
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));M22DocumentsProvider.reset(app)}
    @After fun cleanup(){M22DocumentsProvider.release?.countDown();i.runOnMainSync{models.forEach{it.close()}};M22DocumentsProvider.reset(app)}
    private fun ready(value:String):TableViewModel=TableViewModel(app).also{m->models+=m;m.open();m.manual();var s=m.state!!;m.edit(s.owner,s.epoch,s.grid.rows[0][0].id,value);m.review(s.owner,s.epoch);s=m.state!!;m.confirm(s.owner,s.epoch,s.review!!.id,true);assertTrue(m.beginSave(s.owner,s.epoch,s.review.id))}
    private fun await(condition:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<end){if(condition())return;Thread.sleep(25)};fail("Table operation timeout")}
    @Test fun nonContentDestinationCannotOverwriteAnAppPrivateFile(){
        val f=java.io.File(app.filesDir,"m22-synthetic-guard").apply{writeText("UNCHANGED")}
        try{i.runOnMainSync{val m=ready("SYNTHETIC");m.destination(android.net.Uri.fromFile(f));assertEquals(TablePhase.REVIEW,m.state!!.phase);assertTrue(m.state!!.problem!!.startsWith("Unsupported destination"))};assertEquals("UNCHANGED",f.readText());assertEquals(0,M22DocumentsProvider.opens.get())}finally{f.delete()}
    }
    @Test fun actualProviderWritesFrozenReviewedBytesAndClosesStream(){
        val uri=M22DocumentsProvider.create(app);lateinit var m:TableViewModel
        i.runOnMainSync{m=ready("0012");m.destination(uri)};await{m.state?.problem=="CSV saved."};await{M22DocumentsProvider.closes.get()==1}
        val bytes=app.contentResolver.openInputStream(uri)!!.use{it.readBytes()};assertEquals("\"0012\",\"\"\r\n\"\",\"\"\r\n",bytes.toString(Charsets.UTF_8));assertEquals(1,M22DocumentsProvider.opens.get())
    }
    @Test fun deniedProviderAndImmediateLockGuardNeverClaimSaveOrLeakContent(){
        val uri=M22DocumentsProvider.create(app);lateinit var m:TableViewModel;M22DocumentsProvider.denyWrites=true
        i.runOnMainSync{m=ready("SYNTHETIC");m.destination(uri)};await{m.state?.phase==TablePhase.REVIEW};assertTrue(m.state!!.problem!!.contains("partial CSV"));assertFalse(m.state!!.confirmed)
        assertEquals(0,M22DocumentsProvider.root(app).listFiles()!!.single().length().toInt());M22DocumentsProvider.denyWrites=false;M22DocumentsProvider.opens.set(0)
        i.runOnMainSync{val s=m.state!!;m.confirm(s.owner,s.epoch,s.review!!.id,true);assertTrue(m.beginSave(s.owner,s.epoch,s.review.id));m.destination(uri){false}}
        await{m.state?.phase==TablePhase.REVIEW};assertEquals(0,M22DocumentsProvider.opens.get())
    }
    @Test fun staleDestinationAfterCanceledExportAndCorrectionCannotOpenAnyStream(){
        val old=M22DocumentsProvider.create(app);val next=M22DocumentsProvider.create(app);lateinit var m:TableViewModel
        i.runOnMainSync{m=ready("OLD");m.cancelExport();var s=m.state!!;m.edit(s.owner,s.epoch,s.grid.rows[0][0].id,"NEW");m.review(s.owner,s.epoch);s=m.state!!;m.confirm(s.owner,s.epoch,s.review!!.id,true)
            assertFalse(m.beginSave(s.owner,s.epoch,s.review.id));m.destination(old);assertEquals(0,M22DocumentsProvider.opens.get());assertTrue(m.beginSave(s.owner,s.epoch,s.review.id));m.destination(next)}
        await{m.state?.problem=="CSV saved."};assertEquals(1,M22DocumentsProvider.opens.get());assertTrue(app.contentResolver.openInputStream(next)!!.bufferedReader().use{it.readText()}.contains("NEW"));assertEquals(0,app.contentResolver.openInputStream(old)!!.use{it.readBytes()}.size)
    }
    @Test fun blockingCanceledWriterRemainsExclusiveAcrossViewModelOwnersAndClosesOnce(){
        val first=M22DocumentsProvider.create(app);val second=M22DocumentsProvider.create(app);val entered=CountDownLatch(1);val release=CountDownLatch(1);M22DocumentsProvider.entered=entered;M22DocumentsProvider.release=release
        lateinit var a:TableViewModel;lateinit var b:TableViewModel
        i.runOnMainSync{a=ready("FIRST");a.destination(first)}
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));i.runOnMainSync{a.close();b=ready("SECOND");b.destination(second)};Thread.sleep(300);assertEquals(1,M22DocumentsProvider.opens.get());release.countDown()
            await{b.state?.problem=="CSV saved."};await{M22DocumentsProvider.closes.get()==2};assertEquals(2,M22DocumentsProvider.opens.get());assertEquals(0,app.contentResolver.openInputStream(first)!!.use{it.readBytes()}.size)
            assertTrue(app.contentResolver.openInputStream(second)!!.bufferedReader().use{it.readText()}.contains("SECOND"));assertNull(a.state)
        }finally{release.countDown()}
    }
    @Test fun returnedDestinationWaitsForForegroundAndLockInvalidatesIt(){
        val uri=M22DocumentsProvider.create(app);lateinit var m:TableViewModel
        i.runOnMainSync{m=ready("PRIVATE SYNTHETIC");m.background(false);m.destination(uri);assertEquals(TablePhase.WAITING,m.state!!.phase);m.background(true);assertEquals(0,M22DocumentsProvider.opens.get());m.lock();m.setActive(true)}
        Thread.sleep(200);assertNull(m.state);assertEquals(0,M22DocumentsProvider.opens.get())
    }
}
