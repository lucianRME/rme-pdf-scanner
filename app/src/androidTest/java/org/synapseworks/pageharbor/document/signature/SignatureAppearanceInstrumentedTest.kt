package org.synapseworks.pageharbor.document.signature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.redaction.*
import org.synapseworks.pageharbor.document.searchablepdf.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.targetsize.*
import org.synapseworks.pageharbor.ocr.*

class SignatureAppearanceInstrumentedTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/synthetic-ink-${UUID.randomUUID()}").apply{mkdirs()};PDFBoxResourceLoader.init(app)}
 @After fun cleanup(){root.deleteRecursively()}
 private fun pdfBitmap(file:File):Bitmap=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->pdf.openPage(0).use{p->Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888).also{it.eraseColor(Color.WHITE);p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}}}}
 // Literal placement oracles fixed before export testing: dot(.61,.28), L(.16,.28→.16,.52→.64,.52), white gap(.4,.28).
 private fun checkInk(bitmap:Bitmap,rotation:Int=0){
  fun pixel(x:Float,y:Float):Int {val (a,b)=when(rotation){90->1f-y to x;180->1f-x to 1f-y;270->y to 1f-x;else->x to y};return bitmap.getPixel((a*bitmap.width).toInt(),(b*bitmap.height).toInt())}
  assertTrue(Color.red(pixel(.61f,.28f))<60);assertTrue(Color.red(pixel(.16f,.4f))<60);assertTrue(Color.red(pixel(.4f,.52f))<60)
  assertTrue(Color.red(pixel(.4f,.28f))>240)
 }
 @Test fun independentDotMultiStrokeSpacingTransparencyAndEveryPageRotation(){
  val mark=SignatureFixtures.mark()
  for(rotation in DocumentPageRotation.entries){
   val swap=rotation.degrees%180!=0;val bitmap=Bitmap.createBitmap(if(swap)600 else 800,if(swap)800 else 600,Bitmap.Config.ARGB_8888)
   try{bitmap.eraseColor(Color.TRANSPARENT);AnnotationRenderer.draw(bitmap,AnnotationLayer.of(listOf(mark)),rotation);checkInk(bitmap,rotation.degrees)
    assertEquals(255,Color.alpha(bitmap.getPixel(0,0)))}finally{bitmap.recycle()}
  }
  val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
  try{bitmap.eraseColor(Color.WHITE);AnnotationRenderer.draw(bitmap,AnnotationLayer.of(listOf(mark.withStyle(mark.style.copy(opacity=.5f)))),DocumentPageRotation.DEGREES_0);assertEquals(128f,Color.red(bitmap.getPixel(488,168)).toFloat(),2f)}finally{bitmap.recycle()}
 }
 @Test fun frozenNormalJpegPasswordAndTargetSizeKeepInkWithoutOriginalMutation()=runBlocking {
  val page=AnnotationFixtures.source(root);val source=File(Uri.parse(page.source.reference).path!!);val bytes=source.readBytes()
  val session=DocumentSession(listOf(page.copy(annotations=AnnotationLayer.of(listOf(SignatureFixtures.mark())))))
  val plan=normalPdfExportPlan(session)as NormalPdfExportPlan.RecomposeFromPages
  val later=session.copy(pages=listOf(session.pages.single().copy(annotations=AnnotationLayer.EMPTY)));assertTrue(later.pages.single().annotations.isEmpty)
  assertFalse(session.canUseDirectPdf);assertTrue(pageJpegExportPlan(session.pages.single())is PageJpegExportPlan.Filtered)
  for(rotation in DocumentPageRotation.entries){
   val pages=plan.pages.map{it.copy(rotation=rotation)};val result=recomposeNormalPdf(app,pages)as NormalPdfRecompositionResult.Ready
   try{val image=pdfBitmap(result.file);try{checkInk(image,rotation.degrees)}finally{image.recycle()}
    if(rotation==DocumentPageRotation.DEGREES_0){val output=File(root,"protected.pdf")
     assertTrue(org.synapseworks.pageharbor.document.protectedpdf.PasswordProtectedPdf().create(result.file,output,"local-password".toCharArray())is org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfResult.Success)
     PDDocument.load(output,"local-password").use{doc->val image=com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(0);try{checkInk(image)}finally{image.recycle()}}
    }
   }finally{deleteNormalPdfRecomposition(result.file)}
  }
  for(sized in listOf(false,true)){
   val output=File(root,"$sized.jpg");val result=if(sized)writeSizedJpegToDestination(source::inputStream,output.outputStream(),page.filter,page.rotation,page.imageMetadata,SizedJpegEncoding(400,300,95),annotations=session.pages.single().annotations)
    else writeFilteredJpegToDestination(source::inputStream,output.outputStream(),page.filter,annotations=session.pages.single().annotations)
   assertEquals(PageExportResult.Success,result);val image=BitmapFactory.decodeFile(output.path);try{checkInk(image)}finally{image.recycle()}
  }
  val engine=LocalTargetSizePdfEngine(app);val result=engine.prepare(targetSizePdfRequest(session,2L*1024*1024),TargetSizeProgressListener{})as TargetSizePdfResult.TargetReached
  try{val image=pdfBitmap(result.prepared.file);try{checkInk(image)}finally{image.recycle()}}finally{engine.discard(result.prepared)}
  assertArrayEquals(bytes,source.readBytes())
 }
 @Test fun croppedRotatedPdfImportsKeepSignatureInRenderedPageCoordinates()=runBlocking {
  for(rotation in listOf(0,90,180,270)){
   val file=File(root,"cropped-$rotation.pdf")
   PDDocument().use{doc->val page=com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(420f,300f));page.cropBox=com.tom_roush.pdfbox.pdmodel.common.PDRectangle(20f,30f,300f,200f);page.rotation=rotation;doc.addPage(page);doc.save(file)}
   val original=file.readBytes();val vm=org.synapseworks.pageharbor.PageHarborSessionViewModel()
   try{
    assertTrue(vm.beginImportRequest(org.synapseworks.pageharbor.document.importing.DocumentImportOrigin.PICKER))
    val processor=org.synapseworks.pageharbor.document.importing.DocumentImportProcessor(app){resource->vm.registerPendingAcquisitionResources(listOf(resource))==PendingResourceRegistrationResult.Registered}
    val imported=processor.prepare(listOf(Uri.fromFile(file)),DocumentSourceCategory.SELECTED_IMAGE,20)as org.synapseworks.pageharbor.document.importing.DocumentImportPreparationResult.Success
    vm.completeImportRequest(imported);val page=vm.documentPages.single();assertEquals(DocumentSourceCategory.RENDERED_PDF_PAGE,page.sourceCategory)
    val placed=vm.documentSession.copy(pages=listOf(page.copy(annotations=AnnotationLayer.of(listOf(SignatureFixtures.mark())))))
    val plan=normalPdfExportPlan(placed)as NormalPdfExportPlan.RecomposeFromPages
    val exported=recomposeNormalPdf(app,plan.pages)as NormalPdfRecompositionResult.Ready
    try{val image=pdfBitmap(exported.file);try{checkInk(image)}finally{image.recycle()}}finally{deleteNormalPdfRecomposition(exported.file)}
    assertArrayEquals(original,file.readBytes())
   }finally{vm.clearScan()}
  }
 }
 @Test fun originalOcrAndSecureRedactionExcludeRecoverableSignatureContent()=runBlocking {
  val page=AnnotationFixtures.source(root);val file=File(Uri.parse(page.source.reference).path!!);val layer=AnnotationLayer.of(listOf(SignatureFixtures.mark()));var calls=0
  val coordinator=LocalSearchablePdfExportCoordinator(app,ocrEngine=object:OcrEngine{override fun recognize(pages:List<OcrPage>):OcrResult{calls++;assertArrayEquals(file.readBytes(),pages.single().openJpegStream().use{it.readBytes()});return OcrResult(listOf(OcrPageResult(0,"Original only",layout=OcrPageLayout(800,600,lines=listOf(OcrTextLine("Original only",OcrTextBounds(20f,10f,300f,40f)))))))}})
  val prepared=coordinator.prepare(SearchablePdfExportRequest(listOf(Uri.fromFile(file)),visualPages=listOf(SearchablePdfVisualPage(page.id.value,Uri.fromFile(file),imageMetadata=page.imageMetadata,annotations=layer))))as SearchablePdfPreparedExport.Ready
  try{assertEquals(1,calls);val image=pdfBitmap(prepared.temporaryFile);try{checkInk(image)}finally{image.recycle()};PDDocument.load(prepared.temporaryFile).use{pdf->assertTrue(PDFTextStripper().getText(pdf).contains("Original only"));assertTrue(pdf.getPage(0).annotations.isEmpty())}}finally{coordinator.discardPreparedExport(prepared)}
  val region=RedactionRegion(RedactionRegionId("cover"),RedactionCoordinateSpace.SOURCE_NORMALIZED,RedactionBounds(0f,0f,1f,1f),RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE,"p"),true,0)
  val engine=SecureRedactedPdfEngine(app);val secured=engine.prepare(SecureRedactedPdfRequest(listOf(SecureRedactedPdfPage("p",file::inputStream,page.imageMetadata,requireNotNull(secureRedactionSha256(file::inputStream)),listOf(region),annotations=layer))))as SecureRedactedPdfResult.Success
  try{assertTrue(secured.prepared.securelyVerified);val image=pdfBitmap(secured.prepared.file);try{assertTrue(Color.red(image.getPixel(488,168))<5);assertTrue(Color.red(image.getPixel(320,168))<5)}finally{image.recycle()};PDDocument.load(secured.prepared.file).use{pdf->assertTrue(PDFTextStripper().getText(pdf).isBlank());assertTrue(pdf.getPage(0).annotations.isEmpty());assertNull(pdf.documentCatalog.metadata)}}finally{engine.discard(secured.prepared)}
 }
}
