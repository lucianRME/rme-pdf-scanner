package org.synapseworks.pageharbor.document.curvature

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
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.protectedpdf.*
import org.synapseworks.pageharbor.image.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class CurvatureInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/curvature-native-${UUID.randomUUID()}").apply{mkdirs()}}
    @After fun cleanup(){root.deleteRecursively()}
    @Test fun independentJpegCorpusKeepsAutomaticGeometryControlsAndSourceBytes()=runBlocking {
        var accepted=0;var heldout=0
        for((i,scene)in CurvatureFixtures.cases().withIndex()){
            val page=CurvatureFixtures.source(root,i);val source=File(Uri.parse(page.source.reference).path!!);val hash=BookTestFixtures.sha(source)
            val processor=CurvatureProcessor(app);val work=processor.begin()
            try{val a=processor.analyze(work,page)
                if(!scene.positive)assertNotEquals(scene.id,CurvatureStatus.PROPOSED,a.estimate.status)
                else if(a.estimate.status==CurvatureStatus.PROPOSED){accepted++;if(scene.partition=="heldout")heldout++
                    val f=a.estimate.field!!;CurvatureCorpus.landmarks(scene).forEach{(x,y)->assertTrue(scene.id,kotlin.math.abs(f.correctedY(x.toFloat(),scene.sourceY(x,y).toFloat())-scene.referenceY(y))<=.012)}
                    val review=CurvatureReview(page,a);val preview=processor.preview(work,review);assertTrue(preview.before.file.isFile&&preview.after.file.isFile)
                    val result=processor.prepare(work,review);assertEquals(480,result.derived.metadata.width);assertTrue(result.derived.metadata.height!!<=960)
                    val output=BitmapFactory.decodeFile(result.derived.source.ownedTemporaryFile!!.path)
                    try{
                        val pixels=IntArray(output.width*output.height);output.getPixels(pixels,0,output.width,0,0,output.width,output.height)
                        fun luminance(c:Int)=(((c ushr 16)and 255)*77+((c ushr 8)and 255)*150+(c and 255)*29)ushr 8
                        val paper=luminance(CurvatureCorpus.flat(scene).pixels[0]);val protected=CurvatureCorpus.protected(scene)
                        val retained=protected.count{(x,y)->
                            val xx=(x*479).toInt();val yy=((scene.referenceY(y)-f.minimumY)*639).toInt()
                            var found=false
                            for(dy in -4..4)for(dx in -4..4){val px=xx+dx;val py=yy+dy
                                if(px in 0 until output.width&&py in 0 until output.height&&luminance(pixels[py*output.width+px])<paper-20)found=true}
                            found
                        }.toDouble()/protected.size
                        assertTrue("JPEG protected-ink survival ${scene.id}: $retained",retained>=.995)
                    }finally{output.recycle()}
                    assertEquals(hash,BookTestFixtures.sha(source))
                }
            }finally{processor.finish(work)}
            assertFalse(work.exists())
        };assertTrue("JPEG positives $accepted",accepted>=22);assertTrue("JPEG heldout $heldout",heldout>=7)
    }
    @Test fun bilinearRasterPreservesIndependentLandmarksEdgesAndTransparentWhitePolicy()=runBlocking {
        val image=Bitmap.createBitmap(240,320,Bitmap.Config.ARGB_8888);image.eraseColor(Color.TRANSPARENT)
        val canvas=Canvas(image);val paint=Paint().apply{color=Color.RED};canvas.drawRect(15f,60f,24f,69f,paint);paint.color=Color.BLUE;canvas.drawRect(211f,254f,220f,263f,paint)
        val field=CurvatureField.manual(listOf(.08f,.02f,-.07f,-.02f,.08f),.08f);val processor=CurvatureProcessor(app)
        val out=processor.render(image,field)
        try{
            for((x,y,color)in listOf(19 to 64 to Color.RED,215 to 258 to Color.BLUE).map{Triple(it.first.first,it.first.second,it.second)}){
                val yy=((field.correctedY(x/239f,y/319f)-field.minimumY)*319).toInt();val pixel=out.getPixel(x,yy)
                if(color==Color.RED)assertTrue(Color.red(pixel)>240&&Color.blue(pixel)<10)else assertTrue(Color.blue(pixel)>240&&Color.red(pixel)<10)
            }
            assertEquals(Color.WHITE,out.getPixel(120,0));assertEquals(Color.WHITE,out.getPixel(120,out.height-1))
            assertEquals(Color.TRANSPARENT,image.getPixel(120,0))
        }finally{out.recycle();image.recycle()}
    }
    @Test fun changedMissingQuotaAndEvictedSourcesCannotLeavePartialCorrection()=runBlocking {
        val page=CurvatureFixtures.source(root);val file=File(Uri.parse(page.source.reference).path!!);val processor=CurvatureProcessor(app);val work=processor.begin()
        try{val a=processor.analyze(work,page);file.appendBytes(byteArrayOf(1));var failed=false
            try{processor.prepare(work,CurvatureReview(page,a))}catch(_:Exception){failed=true};assertTrue(failed)
            assertTrue(work.listFiles().orEmpty().none{it.name.startsWith("page-")||it.name.startsWith("source-")})
            file.delete();assertFalse(processor.analyze(work,page).canProcess)
            work.deleteRecursively();processor.finish(work)
        }finally{processor.finish(work)}
        val fresh=CurvatureFixtures.source(root,1,11);val limited=CurvatureProcessor(app,64);val w=limited.begin()
        try{assertFalse(limited.analyze(w,fresh).canProcess);assertTrue(w.listFiles().isNullOrEmpty())}finally{limited.finish(w)}
    }
    @Test fun cancellationDuringRenderingAndAfterOutputProgressCleansAllUnpublishedFiles()=runBlocking {
        val page=CurvatureFixtures.source(root);val p=CurvatureProcessor(app);val work=p.begin()
        try{val a=p.analyze(work,page)
            for(at in listOf(33,480)){var canceled=false
                try{p.prepare(work,CurvatureReview(page,a)){done,_->if(done>=at)throw CancellationException()}}catch(_:CancellationException){canceled=true}
                assertTrue(canceled);assertTrue(work.listFiles().isNullOrEmpty())
            }
        }finally{p.finish(work)}
    }
    @Test fun midWriteQuotaFailureRemovesTheEncodedPartialFile()=runBlocking {
        val page=CurvatureFixtures.source(root);val original=File(Uri.parse(page.source.reference).path!!);val first=CurvatureFixtures.prepare(app,DocumentSession(listOf(page)))
        val bytes=File(first.replacement.derived.source.ownedTemporaryFile!!.path).length();first.close()
        val p=CurvatureProcessor(app,original.length()+bytes/2);val work=p.begin()
        try{val a=p.analyze(work,page);assertTrue(a.canProcess);var failed=false
            try{p.prepare(work,CurvatureReview(page,a))}catch(_:Exception){failed=true};assertTrue(failed);assertTrue(work.listFiles().isNullOrEmpty())
        }finally{p.finish(work)}
    }
    @Test fun normalAndPasswordPdfAndPhotometricExportReadCorrectedUnfilteredSourceOnce()=runBlocking {
        val page=CurvatureFixtures.source(root).copy(filter=DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=ManualImageAdjustments(20,-25))
        val prepared=CurvatureFixtures.prepare(app,DocumentSession(listOf(page)))
        try{val corrected=prepared.after.pages.single();val file=File(corrected.source.ownedTemporaryFile!!.path);val hash=BookTestFixtures.sha(file)
            val target=java.io.ByteArrayOutputStream();assertEquals(PageExportResult.Success,writeFilteredJpegToDestination(file::inputStream,target,corrected.filter,rotation=corrected.rotation,imageMetadata=corrected.imageMetadata,adjustments=corrected.adjustments))
            assertEquals(hash,BookTestFixtures.sha(file));assertTrue(target.size()>0)
            val pdf=recomposeNormalPdf(app,(normalPdfExportPlan(prepared.after)as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
            try{PDDocument.load(pdf.file).use{assertEquals(1,it.numberOfPages);assertEquals("",PDFTextStripper().getText(it).trim())}
                val encrypted=File(root,"curvature-protected.pdf");assertTrue(PasswordProtectedPdf().create(pdf.file,encrypted,"synthetic-curvature-password".toCharArray())is PasswordPdfResult.Success)
                PDDocument.load(encrypted,"synthetic-curvature-password").use{assertTrue(it.isEncrypted);assertEquals(1,it.numberOfPages)}
            }finally{deleteNormalPdfRecomposition(pdf.file)}
        }finally{prepared.close()}
    }
    @Test fun acceptedM11HalfCanBeAutomaticallyCorrectedWithoutChangingOtherHalf()=runBlocking {
        val scene=CurvatureCorpus.distorted(CurvatureFixtures.cases()[1]);val bitmap=Bitmap.createBitmap(960,640,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(scene.pixels,0,480,0,0,480,640);bitmap.setPixels(scene.pixels,0,480,480,0,480,640)
        val file=File(root,"curved-spread.jpg");try{file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{bitmap.recycle()}
        val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE)
        val split=BookTestFixtures.prepare(app,DocumentSession(listOf(page)),manual=BookSplitLine(.5f,.5f))
        try{val other=split.after.pages[1];val source=File(split.after.pages[0].source.ownedTemporaryFile!!.path);val hash=BookTestFixtures.sha(source)
            val corrected=CurvatureFixtures.prepare(app,split.after)
            try{assertSame(other,corrected.after.pages[1]);assertEquals(hash,BookTestFixtures.sha(source));assertEquals(2,corrected.after.pages.size)}finally{corrected.close()}
        }finally{split.close()}
    }
    @Test fun frozenPasswordCaptureIsNotRetargetedAfterNonlinearPageReplacement()=runBlocking {
        val page=CurvatureFixtures.source(root);val before=DocumentSession(listOf(page));val document=org.synapseworks.pageharbor.PageHarborSessionViewModel();document.installDocumentSessionForTest(before)
        val model=withContext(Dispatchers.Main.immediate){PasswordPdfExportViewModel(app.applicationContext as android.app.Application)}
        try{withContext(Dispatchers.Main.immediate){model.open(normalPdfExportPlan(before))};val source=model.state.source!!;val hash=BookTestFixtures.sha(source)
            val p=CurvatureFixtures.prepare(app,before)
            try{assertTrue(document.applyCurvature(before,document.documentRevision,p.replacement));assertEquals(hash,BookTestFixtures.sha(source));PDDocument.load(source).use{assertEquals(1,it.numberOfPages)}}finally{document.clearScan();p.close()}
        }finally{withContext(Dispatchers.Main.immediate){model.close()}}
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=28)
    @Test fun allImageStagesCalledFromMainExecuteOffMain()=runBlocking {
        val page=CurvatureFixtures.source(root);val processor=CurvatureProcessor(app);val violations=java.util.concurrent.atomic.AtomicInteger()
        withContext(Dispatchers.Main.immediate){val old=android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskWrites().penaltyListener(java.util.concurrent.Executor{it.run()}){v->
                if(v.stackTrace.any{it.className.startsWith("org.synapseworks.pageharbor.document.curvature")||it.className.startsWith("org.synapseworks.pageharbor.document.booklayout")})violations.incrementAndGet()
            }.build())
            try{val w=processor.begin();try{val a=processor.analyze(w,page);processor.preview(w,CurvatureReview(page,a));processor.prepare(w,CurvatureReview(page,a))}finally{processor.finish(w)}}finally{android.os.StrictMode.setThreadPolicy(old)}
        };assertEquals(0,violations.get())
    }
    @Test fun transparentPngPreviewAndAcceptedJpegBothCompositeOntoWhite()=runBlocking {
        val bitmap=Bitmap.createBitmap(240,320,Bitmap.Config.ARGB_8888);val file=File(root,"transparent.png")
        try{bitmap.eraseColor(Color.TRANSPARENT);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,contentType="image/png")
        val hash=BookTestFixtures.sha(file);val p=CurvatureProcessor(app);val work=p.begin()
        try{val a=p.analyze(work,page);assertNotEquals(CurvatureStatus.PROPOSED,a.estimate.status)
            val review=CurvatureReview(page,a,CurvatureField.manual(listOf(.04f,0f,-.04f,0f,.04f),0f),true,true)
            val preview=p.preview(work,review);val output=p.prepare(work,review)
            for(path in listOf(preview.before.file,preview.after.file,File(output.derived.source.ownedTemporaryFile!!.path))){
                val image=BitmapFactory.decodeFile(path.path);try{val c=image.getPixel(20,20);assertTrue(Color.red(c)>=250&&Color.green(c)>=250&&Color.blue(c)>=250)}finally{image.recycle()}
            };assertEquals(hash,BookTestFixtures.sha(file))
        }finally{p.finish(work)}
    }
    @Test fun pixelAndExpandedCanvasLimitsFailBeforeUnboundedOutputAllocation()=runBlocking {
        assertThrows(IllegalArgumentException::class.java){CurvatureField.manual(listOf(.1f,0f,-.1f,0f,.1f),0f).dimensions(2000,6000)}
        val bitmap=Bitmap.createBitmap(6001,2,Bitmap.Config.ARGB_8888);val file=File(root,"oversize.jpg")
        try{bitmap.eraseColor(Color.WHITE);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{bitmap.recycle()}
        val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE)
        val p=CurvatureProcessor(app);val w=p.begin();try{assertFalse(p.analyze(w,page).canProcess)}finally{p.finish(w)}
    }
    @Test fun perspectiveAndVerticalCorrectionUseTheSameReviewedBookPage() = runBlocking {
        val scene = CurvatureFixtures.cases()[1]
        val input = CurvatureCorpus.distorted(scene)
        // The older curve corpus uses gray sentinel pixels outside the warped source.
        // Model a complete light paper outline for this new photographed-page fixture.
        val paper = CurvatureCorpus.flat(scene).pixels.first()
        val completePage = input.pixels.map { if (it == 0xff686868.toInt()) paper else it }.toIntArray()
        val pagePixels = Bitmap.createBitmap(completePage, input.width, input.height, Bitmap.Config.ARGB_8888)
        val photo = Bitmap.createBitmap(640, 820, Bitmap.Config.ARGB_8888)
        val file = File(root, "angled-book.jpg")
        val quad = floatArrayOf(96f, 68f, 524f, 103f, 566f, 759f, 69f, 722f)
        val mapping = android.graphics.Matrix()
        check(mapping.setPolyToPoly(
            floatArrayOf(0f, 0f, input.width.toFloat(), 0f, input.width.toFloat(), input.height.toFloat(), 0f, input.height.toFloat()),
            0, quad, 0, 4,
        ))
        try {
            Canvas(photo).apply {
                drawColor(Color.rgb(50, 55, 60))
                drawBitmap(pagePixels, mapping, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            file.outputStream().use { check(photo.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
        } finally {
            pagePixels.recycle()
            photo.recycle()
        }
        val hash = BookTestFixtures.sha(file)
        val page = DocumentPage(
            DocumentPageId(100), DocumentResource(Uri.fromFile(file).toString(), DocumentResourceOwnership.USER_OR_EXTERNAL),
            DocumentSourceCategory.SCAN, imageMetadata = DocumentImageMetadata(file.length(), 640, 820),
        )
        val processor = CurvatureProcessor(app)
        val work = processor.begin()
        try {
            val analysis = processor.analyze(work, page)
            val proposal = requireNotNull(analysis.perspective)
            for ((index, corner) in proposal.corners.withIndex()) {
                assertTrue("corner $index horizontal error", kotlin.math.abs(corner.x * 640 - quad[index * 2]) <= 12f)
                assertTrue("corner $index vertical error at ${corner.y * 820}", kotlin.math.abs(corner.y * 820 - quad[index * 2 + 1]) <= 12f)
            }
            val curve = requireNotNull(analysis.straightenedEstimate?.field) { "Straightened curvature status: ${analysis.straightenedEstimate}" }
            assertFalse(curve.isIdentity())
            val review = CurvatureReview(page, analysis, field = curve, enabled = true, straighten = true)
            val pair = processor.preview(work, review)
            val replacement = processor.prepare(work, review)
            assertTrue(requireNotNull(pair.after.metadata.width) < requireNotNull(pair.before.metadata.width))
            val output = BitmapFactory.decodeFile(replacement.derived.source.ownedTemporaryFile!!.path)
            try {
                assertTrue(output.width in 445..505)
                assertTrue(output.height in 610..900)
                var checked = 0
                var lightPaper = 0
                var darkText = 0
                for (y in output.height / 10 until output.height * 9 / 10 step 3) {
                    for (x in output.width / 10 until output.width * 9 / 10 step 3) {
                        val pixel = output.getPixel(x, y)
                        if (Color.red(pixel) > 180 && Color.green(pixel) > 150) lightPaper++
                        if (Color.red(pixel) < 125 && Color.green(pixel) < 140) darkText++
                        checked++
                    }
                }
                assertTrue("Paper support $lightPaper/$checked", lightPaper >= checked * .60)
                assertTrue("Printed text support $darkText/$checked", darkText >= checked * .02)
                assertEquals(hash, BookTestFixtures.sha(file))
            } finally {
                output.recycle()
            }
        } finally {
            processor.finish(work)
        }
    }

}
