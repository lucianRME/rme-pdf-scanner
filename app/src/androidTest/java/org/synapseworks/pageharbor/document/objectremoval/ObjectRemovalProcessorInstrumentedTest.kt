package org.synapseworks.pageharbor.document.objectremoval

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.history.*
import org.synapseworks.pageharbor.document.protectedpdf.*

class ObjectRemovalProcessorInstrumentedTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/m28-processor-${UUID.randomUUID()}").apply{mkdirs()}}
 @After fun cleanup(){root.deleteRecursively()}
 private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
 private fun file(page:DocumentPage)=page.source.ownedTemporaryFile?.let{File(it.path)}?:File(Uri.parse(page.source.reference).path!!)
 @Test fun actualSourceSnapshotAndLosslessResultPreserveEveryUnselectedPixel()=runBlocking {
  val page=ObjectRemovalFixtures.source(root);val input=file(page).readBytes();val prepared=ObjectRemovalFixtures.prepare(app,DocumentSession(listOf(page)))
  try{val snapshot=File(prepared.output.snapshot.ownedTemporaryFile!!.path);assertArrayEquals(input,snapshot.readBytes());val result=file(prepared.after.pages[0]);val before=BitmapFactory.decodeByteArray(input,0,input.size);val after=BitmapFactory.decodeFile(result.path);val effective=InstrumentationRegistry.getInstrumentation().context.assets.open("m28/dark-spot-512-effective.png").use{BitmapFactory.decodeStream(it)}!!
   try{assertEquals(before.width,after.width);assertEquals(before.height,after.height);for(y in 0 until before.height)for(x in 0 until before.width)if(effective.getPixel(x,y)and 255==0)assertEquals(before.getPixel(x,y),after.getPixel(x,y));assertEquals(232,after.getPixel(286,245)and 255)}finally{before.recycle();after.recycle();effective.recycle()}
   assertEquals(prepared.output.resultDigest,sha(result.readBytes()));assertArrayEquals(input,file(page).readBytes())
  }finally{prepared.close()}
 }
 @Test fun failedWritesAndCancelledPreparationRemoveEveryUnacceptedFile()=runBlocking {
  val page=ObjectRemovalFixtures.source(root);val original=file(page).readBytes();val failing=ObjectRemovalProcessor(app,maximumOutputBytes=32);val work=failing.begin()
  try{val analysis=failing.open(work,page);try{failing.preview(work,page,analysis,ObjectRemovalFixtures.mask());fail()}catch(_:IllegalStateException){};assertTrue(work.listFiles()!!.none{it.name.startsWith("result-")})}finally{failing.finish(work)}
  assertFalse(work.exists())
  val processor=ObjectRemovalProcessor(app);val cancelled=processor.begin();try{val a=processor.open(cancelled,page);val job=launch(Dispatchers.IO){processor.preview(cancelled,page,a,ObjectRemovalFixtures.mask())};yield();job.cancelAndJoin()}finally{processor.finish(cancelled)}
  assertFalse(cancelled.exists());assertArrayEquals(original,file(page).readBytes())
 }
 @Test fun sourceReplacementResultCorruptionAndEvictedWorkspaceNeverVerify()=runBlocking {
  val page=ObjectRemovalFixtures.source(root);val before=file(page).readBytes();val prepared=ObjectRemovalFixtures.prepare(app,DocumentSession(listOf(page)))
  try{file(page).appendBytes(byteArrayOf(0));try{prepared.processor.verify(prepared.work,prepared.analysis,prepared.output);fail()}catch(_:IllegalStateException){};file(page).writeBytes(before)
   val result=file(prepared.after.pages[0]);result.appendBytes(byteArrayOf(0));try{prepared.processor.verify(prepared.work,prepared.analysis,prepared.output);fail()}catch(_:IllegalStateException){}
   prepared.work.deleteRecursively();try{prepared.processor.verify(prepared.work,prepared.analysis,prepared.output);fail()}catch(_:Exception){}
   assertArrayEquals(before,file(page).readBytes())
  }finally{prepared.close()}
 }
 @Test fun sourceAndResultBytesSurviveHistoryEvenAfterTheExternalSourceChanges()=runBlocking {
  val original=DocumentSession(listOf(ObjectRemovalFixtures.source(root)));val source=file(original.pages[0]);val originalBytes=source.readBytes();val prepared=ObjectRemovalFixtures.prepare(app,original)
  val vm=PageHarborSessionViewModel();withContext(Dispatchers.Main){vm.installDocumentSessionForTest(original);vm.configureHistory(AndroidHistorySourceAccess(app),HistoryLimits())};vm.awaitHistoryIdle()
  try{withContext(Dispatchers.Main){assertTrue(vm.applyObjectRemoval(original,vm.documentRevision,prepared.output))};vm.awaitHistoryIdle();val accepted=vm.documentSession;val acceptedBytes=file(accepted.pages[0]).readBytes();assertEquals(1,vm.historyState.undoCount)
   source.appendBytes(byteArrayOf(0));withContext(Dispatchers.Main){assertTrue(vm.undoDocument())};vm.awaitHistoryIdle();assertArrayEquals(originalBytes,file(vm.documentPages[0]).readBytes());assertFalse(vm.historyState.dirty)
   withContext(Dispatchers.Main){assertTrue(vm.redoDocument())};vm.awaitHistoryIdle();assertSame(accepted,vm.documentSession);assertArrayEquals(acceptedBytes,file(vm.documentPages[0]).readBytes());assertTrue(source.length()>originalBytes.size)
  }finally{withContext(Dispatchers.Main){vm.clearScan()};vm.awaitHistoryIdle();withContext(Dispatchers.Main){vm.disposeHistoryForTest()};prepared.close()}
 }
 @Test fun unretainableHistoryBudgetDeclinesBeforeSessionPublication()=runBlocking {
  val original=DocumentSession(listOf(ObjectRemovalFixtures.source(root)));val prepared=ObjectRemovalFixtures.prepare(app,original);val vm=PageHarborSessionViewModel()
  withContext(Dispatchers.Main){vm.installDocumentSessionForTest(original);vm.configureHistory(AndroidHistorySourceAccess(app),HistoryLimits(temporaryBytes=1))};vm.awaitHistoryIdle();val revision=vm.documentRevision
  try{withContext(Dispatchers.Main){assertFalse(vm.applyObjectRemoval(original,revision,prepared.output))};assertSame(original,vm.documentSession);assertEquals(revision,vm.documentRevision);assertEquals(0,vm.historyState.undoCount)}finally{withContext(Dispatchers.Main){vm.clearScan()};vm.awaitHistoryIdle();withContext(Dispatchers.Main){vm.disposeHistoryForTest()};prepared.close()}
 }
 @Test fun passwordCaptureBeforeRemovalKeepsItsExactOriginalAppearance()=runBlocking {
  val original=DocumentSession(listOf(ObjectRemovalFixtures.source(root)));val prepared=ObjectRemovalFixtures.prepare(app,original);val vm=PageHarborSessionViewModel();val password=withContext(Dispatchers.Main){PasswordPdfExportViewModel(app)}
  withContext(Dispatchers.Main){vm.installDocumentSessionForTest(original);vm.configureHistory(AndroidHistorySourceAccess(app),HistoryLimits());password.open(normalPdfExportPlan(original))};vm.awaitHistoryIdle();val captured=requireNotNull(password.state.source);val bytes=captured.readBytes()
  try{withContext(Dispatchers.Main){assertTrue(vm.applyObjectRemoval(original,vm.documentRevision,prepared.output))};vm.awaitHistoryIdle();assertArrayEquals(bytes,captured.readBytes());val secret="synthetic-m28-capture".toCharArray();withContext(Dispatchers.Main){password.prepare(secret)};withTimeout(30_000){while(password.state.busy)delay(10)};assertNotNull(password.state.output);assertArrayEquals(bytes,captured.readBytes());assertTrue(secret.all{it=='\u0000'})}finally{withContext(Dispatchers.Main){password.close();vm.clearScan()};vm.awaitHistoryIdle();withContext(Dispatchers.Main){vm.disposeHistoryForTest()};prepared.close()}
 }
 @Test fun exifReflectionAndQuarterTurnsKeepSourceAxesAndLayerCoordinates()=runBlocking {
  for(orientation in listOf(2,6,8))for(rotation in DocumentPageRotation.entries){
   val initial=ObjectRemovalFixtures.source(root).copy(rotation=rotation,annotations=org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer());val original=file(initial)
   androidx.exifinterface.media.ExifInterface(original.path).apply{setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
   val bytes=original.readBytes();val page=initial.copy(imageMetadata=initial.imageMetadata.copy(sourceByteCount=original.length()));val processor=ObjectRemovalProcessor(app);val work=processor.begin()
   try{val analysis=processor.open(work,page);fun mapped(x:Int,y:Int):Pair<Int,Int> = when(orientation){2->511-x to y;6->383-y to x;else->y to 511-x}
    val center=mapped(286,245);val mask=InpaintMask.paint(analysis.width,analysis.height,listOf(RemovalStroke(listOf(org.synapseworks.pageharbor.document.annotation.AnnotationPoint((center.first+.5f)/analysis.width,(center.second+.5f)/analysis.height)),14)))
    val proposal=processor.preview(work,page,analysis,mask);assertEquals(RemovalStatus.READY,proposal.status);val prepared=requireNotNull(proposal.prepared);processor.verify(work,analysis,prepared)
    val after=requireNotNull(removalPublication(DocumentSession(listOf(page)),prepared)).after.pages.single();assertEquals(rotation,after.rotation);assertSame(page.annotations,after.annotations)
    val result=BitmapFactory.decodeFile(file(after).path);val raw=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
    try{assertEquals(analysis.width,result.width);assertEquals(analysis.height,result.height);assertEquals(232,result.getPixel(center.first,center.second)and 255)
     for((x,y)in listOf(0 to 0,25 to 30,350 to 75,45 to 330,100 to 112)){val q=mapped(x,y);assertEquals(raw.getPixel(x,y),result.getPixel(q.first,q.second))}
    }finally{result.recycle();raw.recycle()}
    assertArrayEquals(bytes,original.readBytes());assertArrayEquals(bytes,File(prepared.snapshot.ownedTemporaryFile!!.path).readBytes())
   }finally{processor.finish(work)}
  }
 }
}
