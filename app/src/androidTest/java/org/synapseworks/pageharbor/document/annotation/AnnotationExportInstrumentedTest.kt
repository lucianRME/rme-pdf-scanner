package org.synapseworks.pageharbor.document.annotation

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
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.history.*
import org.synapseworks.pageharbor.document.redaction.*
import org.synapseworks.pageharbor.document.searchablepdf.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.targetsize.*
import org.synapseworks.pageharbor.ocr.*

class AnnotationExportInstrumentedTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/annotation-${UUID.randomUUID()}").apply{mkdirs()};PDFBoxResourceLoader.init(app)}
 @After fun cleanup(){root.deleteRecursively()}
 private fun bitmap(file:File):Bitmap=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->pdf.openPage(0).use{p->Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888).also{it.eraseColor(Color.WHITE);p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}}}}
 private fun normal(s:DocumentSession)=normalPdfExportPlan(s)as NormalPdfExportPlan.RecomposeFromPages
 @Test fun normalJpegAndSizedAppearancePreserveCapturedLayersAndOriginalBytes()=runBlocking {
  val source=AnnotationFixtures.source(root);val file=File(Uri.parse(source.source.reference).path!!);val bytes=file.readBytes()
  val before=DocumentSession(listOf(source));val after=before.copy(pages=listOf(source.copy(annotations=AnnotationFixtures.layer(),filter=org.synapseworks.pageharbor.image.DocumentFilter.GRAYSCALE)))
  val frozen=normal(after)
  val later=after.copy(pages=listOf(after.pages[0].copy(annotations=AnnotationLayer.EMPTY)))
  assertEquals(7,frozen.pages[0].annotations.marks.size);assertTrue(later.pages[0].annotations.isEmpty)
  for(rotation in DocumentPageRotation.entries){
   val session=after.copy(pages=listOf(after.pages[0].copy(rotation=rotation)))
   val result=recomposeNormalPdf(app,normal(session).pages)as NormalPdfRecompositionResult.Ready
   try{PDDocument.load(result.file).use{pdf->assertTrue(pdf.getPage(0).annotations.isEmpty());assertTrue(PDFTextStripper().getText(pdf).isBlank())};val rendered=bitmap(result.file);try{val p=AnnotationPoint(.15f,.5f).rotated(rotation.degrees);val c=rendered.getPixel((p.x*rendered.width).toInt(),(p.y*rendered.height).toInt());assertEquals(166f,Color.blue(c).toFloat(),12f);val pixels=IntArray(rendered.width*rendered.height);rendered.getPixels(pixels,0,rendered.width,0,0,rendered.width,rendered.height);assertTrue(pixels.count{Color.red(it)<245||Color.green(it)<245||Color.blue(it)<245}>100)}finally{rendered.recycle()}}finally{deleteNormalPdfRecomposition(result.file)}
  }
  for(sized in listOf(false,true)){
   val output=File(root,if(sized)"sized.jpg"else"full.jpg")
   val result=if(sized)writeSizedJpegToDestination(file::inputStream,output.outputStream(),source.filter,source.rotation,source.imageMetadata,SizedJpegEncoding(400,300,90),annotations=after.pages[0].annotations)
    else writeFilteredJpegToDestination(file::inputStream,output.outputStream(),source.filter,annotations=after.pages[0].annotations)
   assertEquals(PageExportResult.Success,result);val rendered=BitmapFactory.decodeFile(output.path);val pixels=IntArray(rendered.width*rendered.height);rendered.getPixels(pixels,0,rendered.width,0,0,rendered.width,rendered.height);assertTrue(pixels.count{Color.green(it)<220}>100);rendered.recycle()
  }
  val frozenPdf=recomposeNormalPdf(app,frozen.pages)as NormalPdfRecompositionResult.Ready
  try {
   val protected=File(root,"protected.pdf")
   assertTrue(org.synapseworks.pageharbor.document.protectedpdf.PasswordProtectedPdf().create(frozenPdf.file,protected,"local-password".toCharArray()) is org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfResult.Success)
   PDDocument.load(protected,"local-password").use{pdf->assertEquals(1,pdf.numberOfPages);assertTrue(pdf.getPage(0).annotations.isEmpty());assertTrue(pdf.getPage(0).resources.xObjectNames.iterator().hasNext())}
  }finally{deleteNormalPdfRecomposition(frozenPdf.file)}
  val sizeEngine=LocalTargetSizePdfEngine(app)
  val sizeResult=sizeEngine.prepare(targetSizePdfRequest(after,2L*1024*1024),TargetSizeProgressListener{})
  assertTrue(sizeResult is TargetSizePdfResult.TargetReached)
  val sizedPdf=(sizeResult as TargetSizePdfResult.TargetReached).prepared
  try{val rendered=bitmap(sizedPdf.file);val pixels=IntArray(rendered.width*rendered.height);rendered.getPixels(pixels,0,rendered.width,0,0,rendered.width,rendered.height);assertTrue(pixels.count{Color.green(it)<220}>100);rendered.recycle()}finally{sizeEngine.discard(sizedPdf)}
  assertArrayEquals(bytes,file.readBytes())
 }
 @Test fun searchableUsesOnlyOriginalOcrAndRedactionCoversFinalAnnotationPixels()=runBlocking {
  val source=AnnotationFixtures.source(root);val file=File(Uri.parse(source.source.reference).path!!);val layer=AnnotationFixtures.layer();var calls=0
  val coordinator=LocalSearchablePdfExportCoordinator(app,ocrEngine=object:OcrEngine{override fun recognize(pages:List<OcrPage>):OcrResult{calls++;assertArrayEquals(file.readBytes(),pages.single().openJpegStream().use{it.readBytes()});return OcrResult(listOf(OcrPageResult(0,"Original source only",layout=OcrPageLayout(800,600,lines=listOf(OcrTextLine("Original source only",OcrTextBounds(20f,10f,300f,40f)))))))}})
  val prepared=coordinator.prepare(SearchablePdfExportRequest(listOf(Uri.fromFile(file)),visualPages=listOf(SearchablePdfVisualPage(source.id.value,Uri.fromFile(file),imageMetadata=source.imageMetadata,annotations=layer))))as SearchablePdfPreparedExport.Ready
  try{assertEquals(1,calls);PDDocument.load(prepared.temporaryFile).use{pdf->val text=PDFTextStripper().getText(pdf);assertTrue(text.contains("Original source only"));assertFalse(text.contains("Café"));assertTrue(pdf.getPage(0).annotations.isEmpty())}}finally{coordinator.discardPreparedExport(prepared)}
  val region=RedactionRegion(RedactionRegionId("cover"),RedactionCoordinateSpace.SOURCE_NORMALIZED,RedactionBounds(0f,.6f,1f,1f),RedactionTarget(RedactionTargetKind.DOCUMENT_PAGE,"p"),true,0)
  val engine=SecureRedactedPdfEngine(app)
  val secured=engine.prepare(SecureRedactedPdfRequest(listOf(SecureRedactedPdfPage("p",file::inputStream,source.imageMetadata,requireNotNull(secureRedactionSha256(file::inputStream)),listOf(region),annotations=layer))))as SecureRedactedPdfResult.Success
  try{assertTrue(secured.prepared.securelyVerified);val rendered=bitmap(secured.prepared.file);try{assertTrue(Color.red(rendered.getPixel(200,450))<5);assertEquals(166f,Color.blue(rendered.getPixel(120,300)).toFloat(),12f)}finally{rendered.recycle()};PDDocument.load(secured.prepared.file).use{pdf->assertTrue(PDFTextStripper().getText(pdf).isBlank());assertTrue(pdf.getPage(0).annotations.isEmpty())}}finally{engine.discard(secured.prepared)}
 }
 @Test fun interruptedAndInvalidNormalVisualRenderingLeavesNoTemporaryJpegs()=runBlocking {
  val page=AnnotationFixtures.source(root).copy(annotations=AnnotationFixtures.layer())
  val plan=normal(DocumentSession(listOf(page))).pages.single()
  val scratch=File(root,"visuals").apply{mkdirs()}
  val provider=NormalPdfVisualStreamProvider(app,scratch,isCancellationRequested={true})
  try{provider.open(plan);fail("Cancelled rendering accepted")}catch(_:CancellationException){}
  assertTrue(scratch.listFiles().orEmpty().isEmpty())
  val invalid=plan.copy(annotations=AnnotationLayer.of(listOf(AnnotationMark.create("overflow",AnnotationTool.TEXT,AnnotationBounds(.1f,.1f,.2f,.12f),text="Café Résumé"))))
  try{NormalPdfVisualStreamProvider(app,scratch).open(invalid);fail("Invalid text accepted")}catch(_:java.io.IOException){}
  assertTrue(scratch.listFiles().orEmpty().isEmpty())
 }
 @Test fun importedPdfCommentsStayInOriginalProvenanceAndDoNotBecomeEditableOrHiddenRmeContent()=runBlocking {
  val file=File(root,"third-party.pdf")
  PDDocument().use{doc->val page=com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(800f,600f));doc.addPage(page)
   val note=com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText();note.rectangle=com.tom_roush.pdfbox.pdmodel.common.PDRectangle(20f,20f,40f,40f);note.contents="Third party comment sentinel";page.annotations=listOf(note);doc.save(file)}
  val original=file.readBytes();val vm=PageHarborSessionViewModel()
  try {
   assertTrue(vm.beginImportRequest(org.synapseworks.pageharbor.document.importing.DocumentImportOrigin.PICKER))
   val processor=org.synapseworks.pageharbor.document.importing.DocumentImportProcessor(app){resource->vm.registerPendingAcquisitionResources(listOf(resource))==PendingResourceRegistrationResult.Registered}
   val imported=processor.prepare(listOf(Uri.fromFile(file)),DocumentSourceCategory.SELECTED_IMAGE,20)as org.synapseworks.pageharbor.document.importing.DocumentImportPreparationResult.Success
   vm.completeImportRequest(imported)
   val page=vm.documentPages.single();assertEquals(DocumentSourceCategory.RENDERED_PDF_PAGE,page.sourceCategory);assertTrue(page.annotations.isEmpty)
   val edited=vm.documentSession.copy(pages=listOf(page.copy(annotations=AnnotationFixtures.layer())))
   assertFalse(edited.canUseDirectPdf);assertEquals(1,edited.originalPdfSources.size)
   val result=recomposeNormalPdf(app,normal(edited).pages)as NormalPdfRecompositionResult.Ready
   try { PDDocument.load(result.file).use{pdf->assertTrue(pdf.getPage(0).annotations.isEmpty());assertFalse(PDFTextStripper().getText(pdf).contains("Third party comment sentinel"))} }finally{deleteNormalPdfRecomposition(result.file)}
   assertArrayEquals(original,file.readBytes());PDDocument.load(file).use{assertEquals(1,it.getPage(0).annotations.size)}
  }finally{vm.clearScan()}
 }
 @Test fun acceptedEditsAreWholeHistoryTransactionsAndCapturesNeverRetarget()=runBlocking {
  val source=AnnotationFixtures.source(root);val vm=withContext(Dispatchers.Main.immediate){PageHarborSessionViewModel().also{it.configureHistory(AndroidHistorySourceAccess(app));it.installDocumentSessionForTest(DocumentSession(listOf(source)))}}
  try{vm.awaitHistoryIdle();val old=vm.documentSession;val revision=vm.documentRevision
   val lease=withContext(Dispatchers.Main.immediate){vm.acquireDocumentSessionLease()!!}
   withContext(Dispatchers.Main.immediate){assertTrue(vm.applyAnnotations(source,revision,vm.documentOwnerToken,AnnotationFixtures.layer()));assertFalse(vm.applyAnnotations(source,revision,vm.documentOwnerToken,AnnotationLayer.EMPTY))};vm.awaitHistoryIdle()
   val snapshots=mutableListOf(old,vm.documentSession)
   suspend fun change(layer:AnnotationLayer){withContext(Dispatchers.Main.immediate){val p=vm.documentPages[0];assertTrue(vm.applyAnnotations(p,vm.documentRevision,vm.documentOwnerToken,layer))};vm.awaitHistoryIdle();snapshots+=vm.documentSession}
   var layer=vm.documentPages[0].annotations
   layer=layer.put(layer.marks[0].withStyle(layer.marks[0].style.copy(rgb=0x2040D0,opacity=.5f)));change(layer)
   layer=layer.put(layer.marks[0].withBounds(layer.marks[0].bounds.moved(.1f,.05f)));change(layer)
   layer=layer.put(layer.marks[0].withBounds(layer.marks[0].bounds.resized(.8f)));change(layer)
   layer=layer.remove(layer.marks[0].id);change(layer)
   layer=layer.put(layer.marks.last().withText("Edited Café"));change(layer)
   assertEquals(6,vm.historyState.undoCount);assertTrue(lease.session.pages[0].annotations.isEmpty)
   for(expected in snapshots.dropLast(1).asReversed()){withContext(Dispatchers.Main.immediate){assertTrue(vm.undoDocument())};vm.awaitHistoryIdle();assertSame(expected,vm.documentSession)}
   for(expected in snapshots.drop(1)){withContext(Dispatchers.Main.immediate){assertTrue(vm.redoDocument())};vm.awaitHistoryIdle();assertSame(expected,vm.documentSession)}
   withContext(Dispatchers.Main.immediate){assertTrue(vm.undoDocument())};vm.awaitHistoryIdle()
   change(vm.documentPages[0].annotations.remove(vm.documentPages[0].annotations.marks.last().id));assertEquals(0,vm.historyState.redoCount)
   withContext(Dispatchers.Main.immediate){vm.releaseDocumentSessionLease(lease);vm.clearScan()};vm.awaitHistoryIdle()
  }finally{withContext(Dispatchers.Main.immediate){vm.disposeHistoryForTest()}}
 } @Test fun signatureGroupsAreWholeHistoryTransactionsAndCapturesNeverRetarget()=runBlocking {
  val source=AnnotationFixtures.source(root);val vm=withContext(Dispatchers.Main.immediate){PageHarborSessionViewModel().also{it.configureHistory(AndroidHistorySourceAccess(app));it.installDocumentSessionForTest(DocumentSession(listOf(source)))}}
  try{vm.awaitHistoryIdle();val old=vm.documentSession;val revision=vm.documentRevision
   val lease=withContext(Dispatchers.Main.immediate){vm.acquireDocumentSessionLease()!!}
   withContext(Dispatchers.Main.immediate){assertTrue(vm.applyAnnotations(source,revision,vm.documentOwnerToken,org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer()));assertFalse(vm.applyAnnotations(source,revision,vm.documentOwnerToken,AnnotationLayer.EMPTY))};vm.awaitHistoryIdle()
   val snapshots=mutableListOf(old,vm.documentSession)
   suspend fun change(layer:AnnotationLayer){withContext(Dispatchers.Main.immediate){val p=vm.documentPages[0];assertTrue(vm.applyAnnotations(p,vm.documentRevision,vm.documentOwnerToken,layer))};vm.awaitHistoryIdle();snapshots+=vm.documentSession}
   var layer=vm.documentPages[0].annotations
   layer=layer.put(layer.marks[0].withStyle(layer.marks[0].style.copy(rgb=0x2040D0,opacity=.5f)));change(layer)
   layer=layer.put(layer.marks[0].withBounds(layer.marks[0].bounds.moved(.1f,.05f)));change(layer)
   layer=layer.put(layer.marks[0].withBounds(layer.marks[0].bounds.resized(.8f)));change(layer)
   layer=layer.remove(layer.marks[0].id);change(layer)
   layer=layer.put(layer.marks.last().withText("Edited Café"));change(layer)
   assertEquals(6,vm.historyState.undoCount);assertTrue(lease.session.pages[0].annotations.isEmpty)
   for(expected in snapshots.dropLast(1).asReversed()){withContext(Dispatchers.Main.immediate){assertTrue(vm.undoDocument())};vm.awaitHistoryIdle();assertSame(expected,vm.documentSession)}
   for(expected in snapshots.drop(1)){withContext(Dispatchers.Main.immediate){assertTrue(vm.redoDocument())};vm.awaitHistoryIdle();assertSame(expected,vm.documentSession)}
   withContext(Dispatchers.Main.immediate){assertTrue(vm.undoDocument())};vm.awaitHistoryIdle()
   change(vm.documentPages[0].annotations.remove(vm.documentPages[0].annotations.marks.last().id));assertEquals(0,vm.historyState.redoCount)
   withContext(Dispatchers.Main.immediate){vm.releaseDocumentSessionLease(lease);vm.clearScan()};vm.awaitHistoryIdle()
  }finally{withContext(Dispatchers.Main.immediate){vm.disposeHistoryForTest()}}
 }
}
