package org.synapseworks.pageharbor.document.collage

import android.app.Application
import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize
import org.synapseworks.pageharbor.document.history.*
import org.synapseworks.pageharbor.document.redaction.*
import org.synapseworks.pageharbor.document.searchablepdf.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.targetsize.*
import org.synapseworks.pageharbor.ocr.*

class CollageExportInstrumentedTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/collage-export-${UUID.randomUUID()}").apply{mkdirs()};PDFBoxResourceLoader.init(app)}
 @After fun cleanup(){root.deleteRecursively()}
 private suspend fun prepared():CollageFixtures.Prepared{
  val p=AnnotationFixtures.source(root).copy(annotations=AnnotationLayer.of(listOf(SignatureFixtures.mark(),AnnotationMark.create("caption",AnnotationTool.TEXT,AnnotationBounds(.08f,.7f,.9f,.95f),text="Synthetic private caption"))))
  return CollageFixtures.prepare(app,DocumentSession(listOf(p)))
 }
 private fun box(file:File,w:Float,h:Float,password:String?=null){
  val document=if(password==null)PDDocument.load(file)else PDDocument.load(file,password)
  document.use{pdf->assertEquals(w,pdf.getPage(0).mediaBox.width,.002f);assertEquals(h,pdf.getPage(0).mediaBox.height,.002f);assertTrue(pdf.getPage(0).annotations.isEmpty())}
 }
 @Test fun normalPasswordTargetJpegAndFrozenCaptureKeepPhysicalBoxesAndNeutralPixels()=runBlocking {
  val prepared=prepared()
  try{
   val derived=prepared.after.pages[1];assertNull(derived.persistentId);assertTrue(derived.annotations.isEmpty);assertTrue(derived.adjustments.isNeutral);assertEquals(org.synapseworks.pageharbor.image.DocumentFilter.ORIGINAL,derived.filter)
   assertEquals(3,prepared.after.pages.size);assertSame(prepared.before.pages[0],prepared.after.pages[0])
   val frozen=normalPdfExportPlan(prepared.after)as NormalPdfExportPlan.RecomposeFromPages
   assertNull(frozen.pages[0].paperSize);assertNotNull(frozen.pages[1].paperSize)
   val single=prepared.after.copy(pages=listOf(derived))
   for(rotation in DocumentPageRotation.entries){
    val capture=normalPdfExportPlan(single.copy(pages=listOf(derived.copy(rotation=rotation))))as NormalPdfExportPlan.RecomposeFromPages
    val result=recomposeNormalPdf(app,capture.pages)as NormalPdfRecompositionResult.Ready
    try{
     val swap=rotation.ordinal%2==1;val size=derived.paperSize!!;box(result.file,if(swap)size.heightPoints else size.widthPoints,if(swap)size.widthPoints else size.heightPoints)
     if(rotation.ordinal==0){
      val password=File(root,"password.pdf");assertTrue(org.synapseworks.pageharbor.document.protectedpdf.PasswordProtectedPdf().create(result.file,password,"local-password".toCharArray())is org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfResult.Success)
      box(password,size.widthPoints,size.heightPoints,"local-password")
      PDDocument.load(password,"local-password").use{pdf->assertTrue(PDFTextStripper().getText(pdf).isBlank());assertEquals(1,pdf.getPage(0).resources.xObjectNames.count())}
     }
    }finally{deleteNormalPdfRecomposition(result.file)}
   }
   val target=LocalTargetSizePdfEngine(app);val result=target.prepare(targetSizePdfRequest(single,4L*1024*1024),TargetSizeProgressListener{})as TargetSizePdfResult.TargetReached
   try{box(result.prepared.file,derived.paperSize!!.widthPoints,derived.paperSize.heightPoints)}finally{target.discard(result.prepared)}
   val jpeg=pageJpegExportPlan(derived)as PageJpegExportPlan.DirectCopy
   assertEquals(derived.id.value,jpeg.pageId)
   val before=app.contentResolver.openInputStream(derived.source.toAndroidUri())!!.use{it.readBytes()}
   val later=single.copy(pages=listOf(derived.copy(rotation=DocumentPageRotation.DEGREES_90)))
   assertEquals(DocumentPageRotation.DEGREES_0,frozen.pages[1].rotation);assertNotEquals(later.pages[0].rotation,frozen.pages[1].rotation)
   assertArrayEquals(before,app.contentResolver.openInputStream(derived.source.toAndroidUri())!!.use{it.readBytes()})
  }finally{prepared.close()}
 }
 @Test fun freshOcrUsesFlattenedRasterAndSecureVerificationCoversFinalPixelsAndText()=runBlocking {
  val prepared=prepared()
  try{
   val page=prepared.after.pages[1];val bytes=app.contentResolver.openInputStream(page.source.toAndroidUri())!!.use{it.readBytes()};val file=File(root,"collage.jpg").apply{writeBytes(bytes)};var calls=0
   val w=page.imageMetadata.width!!;val h=page.imageMetadata.height!!
   val coordinator=LocalSearchablePdfExportCoordinator(app,ocrEngine=object:OcrEngine{override fun recognize(pages:List<OcrPage>):OcrResult{
    calls++;assertEquals(1,pages.size);assertArrayEquals(bytes,pages.single().openJpegStream().use{it.readBytes()})
    return OcrResult(listOf(OcrPageResult(0,"Synthetic private caption",layout=OcrPageLayout(w,h,lines=listOf(OcrTextLine("Synthetic private caption",OcrTextBounds(30f,40f,400f,80f)))))))
   }})
   val result=coordinator.prepare(SearchablePdfExportRequest(listOf(Uri.fromFile(file)),visualPages=listOf(SearchablePdfVisualPage(page.id.value,Uri.fromFile(file),imageMetadata=page.imageMetadata,paperSize=page.paperSize))))as SearchablePdfPreparedExport.Ready
   try{assertEquals(1,calls);box(result.temporaryFile,page.paperSize!!.widthPoints,page.paperSize.heightPoints);PDDocument.load(result.temporaryFile).use{assertTrue(PDFTextStripper().getText(it).contains("Synthetic private caption"))}}finally{coordinator.discardPreparedExport(result)}
   val region=RedactionRegion(RedactionRegionId("cover"),RedactionCoordinateSpace.SOURCE_NORMALIZED,RedactionBounds(0f,0f,1f,1f),RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE,"p"),true,0)
   val engine=SecureRedactedPdfEngine(app)
   val secured=engine.prepare(SecureRedactedPdfRequest(listOf(SecureRedactedPdfPage("p",file::inputStream,page.imageMetadata,requireNotNull(secureRedactionSha256(file::inputStream)),listOf(region),paperSize=page.paperSize))))as SecureRedactedPdfResult.Success
   try{
    assertTrue(secured.prepared.securelyVerified);box(secured.prepared.file,page.paperSize!!.widthPoints,page.paperSize.heightPoints)
    PDDocument.load(secured.prepared.file).use{pdf->assertTrue(PDFTextStripper().getText(pdf).isBlank());assertNull(pdf.documentCatalog.metadata);val image=com.tom_roush.pdfbox.rendering.PDFRenderer(pdf).renderImage(0)
     try{for((x,y)in listOf(100 to 100,200 to 250,400 to 600))assertTrue(Color.red(image.getPixel(x,y))<5)}finally{image.recycle()}}
   }finally{engine.discard(secured.prepared)}
  }finally{prepared.close()}
 }
 @Test fun oneAtomicAppendRetainsSourcesAndUndoRedoReusesExactAcceptedBytes()=runBlocking {
  val p=prepared();val vm=withContext(Dispatchers.Main.immediate){PageHarborSessionViewModel().also{it.configureHistory(AndroidHistorySourceAccess(app));it.installDocumentSessionForTest(p.before)}}
  try{
   vm.awaitHistoryIdle();val old=vm.documentSession;val revision=vm.documentRevision
   val lease=withContext(Dispatchers.Main.immediate){vm.acquireDocumentSessionLease()!!}
   withContext(Dispatchers.Main.immediate){assertFalse(vm.applyCollage(old,revision,p.output,"different-owner"));assertFalse(vm.applyCollage(old,revision+1,p.output));assertSame(old,vm.documentSession);assertTrue(vm.applyCollage(old,revision,p.output));assertFalse(vm.applyCollage(old,revision,p.output))};vm.awaitHistoryIdle()
   assertEquals(1,vm.historyState.undoCount);assertEquals(HistoryAction.COLLAGE,vm.historyState.undoAction);assertSame(old.pages[0],vm.documentPages[0]);assertSame(old,lease.session)
   val accepted=vm.documentSession;val captured=normalPdfExportPlan(accepted);val files=p.output.pages.map{File(it.source.ownedTemporaryFile!!.path)};val bytes=files.map{it.readBytes()}
   repeat(3){
    withContext(Dispatchers.Main.immediate){assertTrue(vm.undoDocument())};vm.awaitHistoryIdle();assertSame(old,vm.documentSession);assertTrue(files.all{it.isFile})
    withContext(Dispatchers.Main.immediate){assertTrue(vm.redoDocument())};vm.awaitHistoryIdle();assertSame(accepted,vm.documentSession)
    files.zip(bytes).forEach{(f,b)->assertArrayEquals(b,f.readBytes())}
   }
   assertEquals(captured,normalPdfExportPlan(accepted));withContext(Dispatchers.Main.immediate){vm.releaseDocumentSessionLease(lease);vm.clearScan()};vm.awaitHistoryIdle()
  }finally{withContext(Dispatchers.Main.immediate){vm.disposeHistoryForTest()};p.close()}
 }
}
