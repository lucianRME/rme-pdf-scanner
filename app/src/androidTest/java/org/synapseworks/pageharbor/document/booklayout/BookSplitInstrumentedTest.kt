package org.synapseworks.pageharbor.document.booklayout

import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.protectedpdf.*
import org.synapseworks.pageharbor.image.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class BookSplitInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/book-native-${UUID.randomUUID()}").apply{mkdirs()}}
    @After fun cleanup(){root.deleteRecursively()}
    @Test fun allFrozenPositiveJpegSpreadsDetectAndKeepBothHalfSourcesInCorrectOrder()=runBlocking {
        var confident=0
        for(index in 0 until 24){
            val source=BookTestFixtures.source(root,index);val file=File(Uri.parse(source.source.reference).path!!);val before=BookTestFixtures.sha(file)
            val p=BookSplitProcessor(app);val work=p.begin()
            try {
                val a=p.analyze(work,source);if(a.detection.status==BookDetectionStatus.CONFIDENT)confident++
                assertEquals("synthetic JPEG ${index+1}",BookDetectionStatus.CONFIDENT,a.detection.status)
                val line=a.detection.line!!;val truth=BookTestFixtures.cases()[index];assertTrue(kotlin.math.abs(line.top-truth.top)<=.01f);assertTrue(kotlin.math.abs(line.bottom-truth.bottom)<=.01f)
                val review=BookReviewedPage(source,a);val preview=p.preview(work,review,BookReadingOrder.LEFT_TO_RIGHT);assertEquals(2,preview.halves.size)
                for(order in BookReadingOrder.entries){
                    val results=p.prepare(work,listOf(review),order).single().halves;val expected=line.halves(480,320,order)
                    for(i in 0..1){val half=results[i];assertEquals(expected[i].width,half.metadata.width);assertEquals(320,half.metadata.height);assertTrue(File(half.source.ownedTemporaryFile!!.path).isFile)}
                }
                assertEquals(before,BookTestFixtures.sha(file))
            }finally{p.finish(work)}
            assertFalse(work.exists())
        }
        assertEquals(24,confident)
    }
    @Test fun negativeAndAmbiguousJpegControlsStayUnsplitUntilExplicitManualCorrection()=runBlocking {
        for(index in 24 until 40){val source=BookTestFixtures.source(root,index);val p=BookSplitProcessor(app);val work=p.begin()
            try{val a=p.analyze(work,source);assertNotEquals(BookDetectionStatus.CONFIDENT,a.detection.status);assertFalse(BookReviewedPage(source,a).split)}finally{p.finish(work)}}
    }
    @Test fun slopingManualCropHasNoOtherHalfPixelsAndDoesNotBurnThePreviewGuideIntoOutputs()=runBlocking {
        val f=File(root,"two-colours.png");val bitmap=Bitmap.createBitmap(400,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240)for(x in 0 until 400)bitmap.setPixel(x,y,if(x<(.4f+.1f*y/239)*400)Color.RED else Color.GREEN)
        try{f.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(f).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,contentType="image/png")
        val prepared=BookTestFixtures.prepare(app,DocumentSession(listOf(page)),manual=BookSplitLine(.4f,.5f))
        try {
            val images=prepared.after.pages.map{BitmapFactory.decodeFile(it.source.ownedTemporaryFile!!.path)}
            try{assertTrue(Color.red(images[0].getPixel(30,120))>220);assertTrue(Color.green(images[0].getPixel(30,120))<20)
                assertTrue(Color.green(images[1].getPixel(images[1].width-30,120))>220);assertTrue(Color.red(images[1].getPixel(images[1].width-30,120))<20)
                assertTrue(Color.red(images[0].getPixel(images[0].width-4,4))>220&&Color.green(images[0].getPixel(images[0].width-4,4))>220)
            }finally{images.forEach{it.recycle()}}
        }finally{prepared.close()}
    }
    @Test fun changedMissingAndOverBudgetSourcesLeaveNoPartialOutput()=runBlocking {
        val source=BookTestFixtures.source(root);val p=BookSplitProcessor(app);val work=p.begin()
        try {
            val a=p.analyze(work,source);File(Uri.parse(source.source.reference).path!!).appendBytes(byteArrayOf(1))
            var failed=false;try{p.prepare(work,listOf(BookReviewedPage(source,a)),BookReadingOrder.LEFT_TO_RIGHT)}catch(_:Exception){failed=true};assertTrue(failed)
            assertTrue(work.listFiles().orEmpty().none{it.name.startsWith("page-")||it.name.startsWith("source-")})
            File(Uri.parse(source.source.reference).path!!).delete();assertFalse(p.analyze(work,source).canSplit)
        }finally{p.finish(work)}
        val fresh=BookTestFixtures.source(root,1,11);val limited=BookSplitProcessor(app,64);val w=limited.begin()
        try{assertFalse(limited.analyze(w,fresh).canSplit);assertTrue(w.listFiles().isNullOrEmpty())}finally{limited.finish(w)}
    }
    @Test fun cancellationAfterFirstBatchResultDeletesAllUnpublishedHalves()=runBlocking {
        val page=BookTestFixtures.source(root);val p=BookSplitProcessor(app);val work=p.begin()
        try {
            val a=p.analyze(work,page);var canceled=false
            try{p.prepare(work,listOf(BookReviewedPage(page,a),BookReviewedPage(page.copy(id=DocumentPageId(11)),a)),BookReadingOrder.LEFT_TO_RIGHT){_,_->throw CancellationException()}}catch(_:CancellationException){canceled=true}
            assertTrue(canceled);assertTrue(work.listFiles().orEmpty().none{it.name.startsWith("page-")||it.name.startsWith("source-")})
        }finally{p.finish(work)}
    }
    @Test fun normalJpegAndPasswordExportsUseDerivedHalvesWithExactlyOneFilterManualPass()=runBlocking {
        val original=BookTestFixtures.source(root).copy(filter=DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=ManualImageAdjustments(20,-25))
        val p=BookTestFixtures.prepare(app,DocumentSession(listOf(original)))
        try {
            for(page in p.after.pages){val file=File(page.source.ownedTemporaryFile!!.path);val bytes=file.readBytes();val expected=java.io.ByteArrayOutputStream()
                assertEquals(PageExportResult.Success,writeFilteredJpegToDestination(file::inputStream,expected,page.filter,rotation=page.rotation,imageMetadata=page.imageMetadata,adjustments=page.adjustments));assertArrayEquals(bytes,file.readBytes());assertTrue(expected.size()>0)}
            val pdf=recomposeNormalPdf(app,(normalPdfExportPlan(p.after)as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
            try {
                PDDocument.load(pdf.file).use{assertEquals(2,it.numberOfPages);assertEquals("",PDFTextStripper().getText(it).trim())}
                val encrypted=File(root,"protected.pdf");val password="synthetic-book-password".toCharArray()
                assertTrue(PasswordProtectedPdf().create(pdf.file,encrypted,password)is PasswordPdfResult.Success)
                PDDocument.load(encrypted,"synthetic-book-password").use{assertEquals(2,it.numberOfPages);assertTrue(it.isEncrypted)}
            }finally{deleteNormalPdfRecomposition(pdf.file)}
        }finally{p.close()}
    }
    @Test fun allExifOrientationsHonorTheSameUprightHalfCoordinateSystem()=runBlocking {
        val scene=BookSpreadCorpus.render(BookTestFixtures.cases()[0]);val w=scene.width;val h=scene.height
        for(orientation in 1..8){
            val rw=if(orientation>=5)h else w;val rh=if(orientation>=5)w else h;val raw=IntArray(rw*rh)
            for(y in 0 until h)for(x in 0 until w){
                val point=when(orientation){
                    1->x to y;2->w-1-x to y;3->w-1-x to h-1-y;4->x to h-1-y
                    5->y to x;6->y to w-1-x;7->h-1-y to w-1-x;else->h-1-y to x
                };raw[point.second*rw+point.first]=scene.pixels[y*w+x]
            }
            val bitmap=Bitmap.createBitmap(raw,rw,rh,Bitmap.Config.ARGB_8888);val file=File(root,"exif-$orientation.jpg")
            try{file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{bitmap.recycle()}
            androidx.exifinterface.media.ExifInterface(file.path).apply{setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
            val page=DocumentPage(DocumentPageId(orientation.toLong()),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE)
            val before=BookTestFixtures.sha(file);val result=BookTestFixtures.prepare(app,DocumentSession(listOf(page)))
            try{assertEquals(2,result.after.pages.size);assertTrue(result.after.pages.all{it.imageMetadata.height==320})
                val halves=result.after.pages.map{BitmapFactory.decodeFile(it.source.ownedTemporaryFile!!.path)}
                try{val left=halves[0].getPixel(90,130);val right=halves[1].getPixel(380-(480-halves[1].width),200)
                    assertTrue("Upright left illustration",Color.blue(left)>150&&Color.red(left)<100)
                    assertTrue("Upright right illustration",Color.red(right)>150&&Color.blue(right)<110)
                }finally{halves.forEach{it.recycle()}}
                assertEquals(before,BookTestFixtures.sha(file))}
            finally{result.close()}
        }
    }
    @Test fun repeatedSpreadBatchProgressAndLateCancellationDoNotLoseOrDuplicatePages()=runBlocking {
        val first=BookTestFixtures.source(root);val pages=(1L..12L).map{first.copy(id=DocumentPageId(it))};val processor=BookSplitProcessor(app);val work=processor.begin()
        try{val a=processor.analyze(work,first);val progress=mutableListOf<Int>();val results=processor.prepare(work,pages.map{BookReviewedPage(it,a)},BookReadingOrder.RIGHT_TO_LEFT){done,total->assertEquals(12,total);progress+=done}
            assertEquals((1..12).toList(),progress);val updated=bookSplitSession(DocumentSession(pages),results)!!;assertEquals(24,updated.pages.size);assertEquals(24,updated.pages.map{it.id}.distinct().size);assertEquals(24,updated.pages.map{it.source.reference}.distinct().size)
        }finally{processor.finish(work)}
    }
    @Test fun secondHalfWriteFailureRemovesTheFirstCompletedHalf()=runBlocking {
        val page=BookTestFixtures.source(root,1);val first=BookTestFixtures.prepare(app,DocumentSession(listOf(page)))
        val lengths=first.replacement.halves.map{File(it.source.ownedTemporaryFile!!.path).length()};first.close()
        val budget=File(Uri.parse(page.source.reference).path!!).length()+lengths[0]+lengths[1]/2
        val processor=BookSplitProcessor(app,budget);val work=processor.begin()
        try{val a=processor.analyze(work,page);assertTrue(a.canSplit);var failed=false
            try{processor.prepare(work,listOf(BookReviewedPage(page,a)),BookReadingOrder.LEFT_TO_RIGHT)}catch(_:Exception){failed=true}
            assertTrue(failed);assertTrue(work.listFiles().isNullOrEmpty())
        }finally{processor.finish(work)}
    }
    @Test fun passwordCaptureStaysFrozenWhenTheCurrentSpreadIsLaterReplaced()=runBlocking {
        val page=BookTestFixtures.source(root);val before=DocumentSession(listOf(page));val plan=normalPdfExportPlan(before)
        val document=org.synapseworks.pageharbor.PageHarborSessionViewModel();document.installDocumentSessionForTest(before);val revision=document.documentRevision
        lateinit var model:PasswordPdfExportViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync{model=PasswordPdfExportViewModel(app.applicationContext as android.app.Application)}
        try {
            withContext(Dispatchers.Main.immediate){model.open(plan)}
            val frozen=requireNotNull(model.state.source);val hash=BookTestFixtures.sha(frozen)
            val result=BookTestFixtures.prepare(app,before)
            try{assertTrue(document.applyBookSplit(before,revision,listOf(result.replacement)));assertEquals(2,document.documentPages.size);assertEquals(hash,BookTestFixtures.sha(frozen));PDDocument.load(frozen).use{assertEquals(1,it.numberOfPages)}}finally{document.clearScan();result.close()}
        }finally{withContext(Dispatchers.Main.immediate){model.close()}}
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=28)
    @Test fun detectionAndRasterWritingCalledFromMainRunOnTheIoWorker()=runBlocking {
        val page=BookTestFixtures.source(root);val processor=BookSplitProcessor(app);val violations=java.util.concurrent.atomic.AtomicInteger()
        withContext(Dispatchers.Main.immediate){
            val before=android.os.StrictMode.getThreadPolicy();android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskWrites().penaltyListener(java.util.concurrent.Executor{it.run()}){v->
                if(v.stackTrace.any{it.className.startsWith("org.synapseworks.pageharbor.document.booklayout")})violations.incrementAndGet()
            }.build())
            try{val work=processor.begin();try{val a=processor.analyze(work,page);processor.prepare(work,listOf(BookReviewedPage(page,a)),BookReadingOrder.LEFT_TO_RIGHT)}finally{processor.finish(work)}}
            finally{android.os.StrictMode.setThreadPolicy(before)}
        }
        assertEquals(0,violations.get())
    }

    @Test fun importedPdfSnapshotSplitsAsRasterWithoutCopyingInvisibleOriginalText()=runBlocking {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val image=BookTestFixtures.source(root);val sourceImage=File(Uri.parse(image.source.reference).path!!);val pdf=File(root,"original-spread.pdf")
        PDDocument().use{document->
            val page=com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(480f,320f));document.addPage(page)
            val embedded=sourceImage.inputStream().use{com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(document,it)}
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document,page).use{content->content.drawImage(embedded,0f,0f,480f,320f)
                content.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);content.beginText();content.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12f);content.newLineAtOffset(20f,20f);content.showText("RME_HIDDEN_SOURCE");content.endText()}
            document.save(pdf)
        }
        PDDocument.load(pdf).use{assertTrue(PDFTextStripper().getText(it).contains("RME_HIDDEN_SOURCE"))}
        val before=BookTestFixtures.sha(pdf);val session=org.synapseworks.pageharbor.PageHarborSessionViewModel()
        assertTrue(session.beginImportRequest(org.synapseworks.pageharbor.document.importing.DocumentImportOrigin.PICKER))
        val importer=org.synapseworks.pageharbor.document.importing.DocumentImportProcessor(app){resource->session.registerPendingAcquisitionResources(listOf(resource))==PendingResourceRegistrationResult.Registered}
        val imported=importer.prepare(listOf(Uri.fromFile(pdf)),DocumentSourceCategory.SELECTED_IMAGE,20)as org.synapseworks.pageharbor.document.importing.DocumentImportPreparationResult.Success
        session.completeImportRequest(imported)
        val original=session.documentSession;assertEquals(DocumentSourceCategory.RENDERED_PDF_PAGE,original.pages.single().sourceCategory)
        val lease=session.acquireDocumentSessionLease()!!;val revision=session.documentRevision
        val prepared=BookTestFixtures.prepare(app,original)
        try {
            assertTrue(session.applyBookSplit(original,revision,listOf(prepared.replacement)))
            assertNull(session.documentSession.directPdfSource);assertEquals(original.originalPdfSources,session.documentSession.originalPdfSources)
            val out=recomposeNormalPdf(app,(normalPdfExportPlan(session.documentSession)as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
            try{PDDocument.load(out.file).use{assertEquals(2,it.numberOfPages);assertFalse(PDFTextStripper().getText(it).contains("RME_HIDDEN_SOURCE"))}}finally{deleteNormalPdfRecomposition(out.file)}
            assertEquals(before,BookTestFixtures.sha(pdf))
        }finally{session.releaseDocumentSessionLease(lease);session.clearScan();prepared.close()}
    }

    @Test fun oversizedImageDimensionsAreRejectedBeforeAnalysisAndCropping()=runBlocking {
        for((width,height)in listOf(6001 to 2,4000 to 3001)){
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val file=File(root,"too-large-$width.jpg")
            try{bitmap.eraseColor(Color.WHITE);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{bitmap.recycle()}
            val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE)
            val processor=BookSplitProcessor(app);val work=processor.begin()
            try{assertFalse(processor.analyze(work,page).canSplit);assertTrue(work.listFiles().isNullOrEmpty())}finally{processor.finish(work)}
        }
    }

    @Test fun transparentPngGuideAndDerivedJpegsUseTheSameWhiteBackground()=runBlocking {
        val bitmap=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);val file=File(root,"transparent.png")
        try{bitmap.eraseColor(Color.TRANSPARENT);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        val before=BookTestFixtures.sha(file);val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,contentType="image/png")
        val processor=BookSplitProcessor(app);val work=processor.begin()
        try{val analysis=processor.analyze(work,page);assertNotEquals(BookDetectionStatus.CONFIDENT,analysis.detection.status)
            val review=BookReviewedPage(page,analysis,line=BookSplitLine(.5f,.5f),split=true,manual=true)
            val preview=processor.preview(work,review,BookReadingOrder.LEFT_TO_RIGHT)
            val outputs=processor.prepare(work,listOf(review),BookReadingOrder.LEFT_TO_RIGHT).single().halves
            for(path in listOf(preview.spread.file)+outputs.map{File(it.source.ownedTemporaryFile!!.path)}){
                val image=BitmapFactory.decodeFile(path.path);try{val pixel=image.getPixel(20,20);assertTrue(Color.red(pixel)>=250&&Color.green(pixel)>=250&&Color.blue(pixel)>=250)}finally{image.recycle()}
            }
            assertEquals(before,BookTestFixtures.sha(file))
        }finally{processor.finish(work)}
    }

}
