package org.synapseworks.pageharbor.pptx

import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.synapseworks.pageharbor.*
import org.synapseworks.pageharbor.docx.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.ocr.*
import org.synapseworks.pageharbor.workflow.*

internal var pptxSmokeStage="start"
internal fun Instrumentation.pptxGenerationChecks()=runBlocking<Unit> {
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context
    val root=File(app.noBackupFilesDir,"m34-generation-${UUID.randomUUID()}").apply{mkdirs()}
    val evidence=File(app.getExternalFilesDir(null),"m34-evidence").apply{mkdirs()}
    val rows=org.json.JSONArray();val runtime=Runtime.getRuntime();val initial=runtime.totalMemory()-runtime.freeMemory();var observed=initial
    try {
        val json=inputs.assets.open("m34/objects.json").bufferedReader().use{it.readText()}
        fun media(name:String,w:Int,h:Int):PptxMedia {
            val f=File(root,"$name.jpg")
            if(!f.exists())if(name.endsWith("figure"))f.writeBytes(inputs.assets.open("m34/$name.jpg").use{it.readBytes()})else {
                val bitmap=BitmapFactory.decodeStream(inputs.assets.open("m34/$name.png"));try{f.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}}finally{bitmap.recycle()}
            }
            val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(f.path,b);return PptxMedia(f,b.outWidth,b.outHeight,PptxWriter.digest(f))
        }
        for(size in PptxSize.entries)for(mode in PptxMode.entries) {
            pptxSmokeStage="generation-$mode-$size";val d=PptxDeck(FrozenPptxCorpus.pages(json,size,::media),mode,size);PptxRender.fit(d)
            val out=File(root,"$mode-$size.pptx");val start=SystemClock.elapsedRealtime();PptxWriter.write(d,out);val elapsed=SystemClock.elapsedRealtime()-start
            check(elapsed<15000);out.copyTo(File(evidence,out.name),true);val pdf=File(evidence,"plan-$mode-$size.pdf");pdf.delete();PptxRender.pdf(d,pdf){}
            observed=maxOf(observed,runtime.totalMemory()-runtime.freeMemory());rows.put(org.json.JSONObject().put("mode",mode).put("size",size).put("bytes",out.length()).put("millis",elapsed))
        }
        val pages=FrozenPptxCorpus.pages(json,PptxSize.WIDE,::media)
        val ten=PptxDeck(List(10){pages.first()});val out=File(evidence,"ten.pptx");out.delete();val start=SystemClock.elapsedRealtime();PptxWriter.write(ten,out);check(SystemClock.elapsedRealtime()-start<15000)
        observed=maxOf(observed,runtime.totalMemory()-runtime.freeMemory());check(observed-initial<=96L*1024*1024)
        val bad=PptxDeck(listOf(pages.last().copy(items=pages.last().items.map{it.copy(text="W".repeat(1000))})),PptxMode.EDITABLE);check(runCatching{PptxRender.fit(bad)}.isFailure)
        val invalidFont=PptxDeck(listOf(pages.last().copy(items=pages.last().items.map{it.copy(font=100000000f)})),PptxMode.EDITABLE)
        check(runCatching{PptxRender.bitmap(invalidFont,0){}}.exceptionOrNull() is IllegalArgumentException)
        val cancelled=File(root,"cancel.pptx");check(runCatching{PptxWriter.write(ten,cancelled,progress={error("Synthetic cancellation")})}.isFailure&&!cancelled.exists())
        val sources=pages.map{p->DocumentPage(DocumentPageId(pages.indexOf(p)+1L),DocumentResource(Uri.fromFile(File(root,"${listOf("landscape","portrait","long")[pages.indexOf(p)]}.png").apply{writeBytes(inputs.assets.open("m34/${listOf("landscape","portrait","long")[pages.indexOf(p)]}.png").use{it.readBytes()})}).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(1,p.visual.width,p.visual.height))}
        val ocr=OcrResult(pages.mapIndexed{i,p->OcrPageResult(i,p.source.sourceText,layout=OcrPageLayout(p.source.widthPx,p.source.heightPx,lines=p.source.lines.map{OcrTextLine(it.text,it.bounds)}))})
        val capture=capturePptx(app,root,DocumentSession(sources),ocr){true}
        try {
            check(capture.deck.pages.all{it.geometry&&it.items.none{b->b.corrected}})
            File(evidence,"automatic-proposals.json").writeText(org.json.JSONArray(capture.deck.pages.map{p->org.json.JSONObject().put("sourceText",p.source.sourceText).put("items",org.json.JSONArray(p.items.map{b->org.json.JSONObject().put("text",b.text).put("sourceIds",org.json.JSONArray(b.sourceIds)).put("box",b.box?.let{org.json.JSONArray(listOf(it.left,it.top,it.right,it.bottom))}).put("corrected",b.corrected)}))}).toString())
            check(runCatching{cropPptxPicture(capture,0,DocxBox(.07f,.07f,.40f,.2f)) {}}.isFailure)
            val crop=cropPptxPicture(capture,0,DocxBox(.72f,.27f,.92f,.52f)){};check(crop.first.file.length()>0)
        }finally{capture.source.frozen.directory.deleteRecursively()}
        pptxSmokeStage="rotated-current-appearance"
        val rotated=capturePptx(app,root,DocumentSession(listOf(sources[1].copy(id=DocumentPageId(1),rotation=DocumentPageRotation.DEGREES_90))),null){true}
        try {
            val p=rotated.deck.pages.single();check(p.visual.width==720&&p.visual.height==540&&p.rotation==90)
            check(rotated.deck.copy(mode=PptxMode.EDITABLE).problem()!=null)
            val out=File(evidence,"rotated-image.pptx");out.delete();PptxWriter.write(rotated.deck,out)
        }finally{rotated.source.frozen.directory.deleteRecursively()}
        // The original M34 PDF remains unchanged. Its grammar decision is recorded, not broadened to force admission.
        val pdfFixture=File(root,"m34-native.pdf").apply{writeBytes(inputs.assets.open("m34/native.pdf").use{it.readBytes()})}
        val pdfPages=(0..1).map{i->val image=File(root,"native-$i.png").apply{writeBytes(inputs.assets.open("m34/native-$i.png").use{it.readBytes()})};val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(image.path,b);DocumentPage(DocumentPageId(i+1L),DocumentResource(Uri.fromFile(image).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.RENDERED_PDF_PAGE,"image/png",DocumentImageMetadata(image.length(),b.outWidth,b.outHeight))}
        val pdfSession=DocumentSession(pdfPages,DocumentResource(Uri.fromFile(pdfFixture).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),directPdfPageIds=pdfPages.map{it.id})
        val originalPdfDigest=PptxWriter.digest(pdfFixture)
        val originalNative=capturePptx(app,root,pdfSession,null){true}
        try {
            File(evidence,"original-native-decisions.json").writeText(org.json.JSONArray(originalNative.deck.pages.map{p->org.json.JSONObject().put("origin",p.source.origin).put("geometry",p.geometry).put("raw",p.source.sourceText).put("automaticFit",runCatching{PptxRender.fit(PptxDeck(listOf(p.copy(coverage=true)),PptxMode.EDITABLE))}.isSuccess)}).toString())
            check(PptxWriter.digest(pdfFixture)==originalPdfDigest)
        }finally{originalNative.source.frozen.directory.deleteRecursively()}
        pptxSmokeStage="compatible-native-and-optical-acquisition"
        val nativePdf=File(root,"native-control.pdf").apply{writeBytes(inputs.assets.open("m32/native.pdf").use{it.readBytes()})}
        val nativeImage=File(root,"native-control.png").apply{writeBytes(inputs.assets.open("m32/native-0.png").use{it.readBytes()})}
        val nativeSession=DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(nativeImage).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.RENDERED_PDF_PAGE,"image/png",DocumentImageMetadata(nativeImage.length(),1224,1584))),DocumentResource(Uri.fromFile(nativePdf).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),directPdfPageIds=listOf(DocumentPageId(1)))
        val native=capturePptx(app,root,nativeSession,null){true}
        try {
            val p=native.deck.pages.single();check(p.source.origin==DocxOrigin.NATIVE_PDF&&p.geometry)
            val repeat=p.items.filter{it.text=="Repeat reference"};check(repeat.size==2&&repeat[0].box!=repeat[1].box&&repeat[0].sourceIds!=repeat[1].sourceIds)
            val automatic=native.deck.copy(mode=PptxMode.EDITABLE,pages=listOf(p.copy(coverage=true)))
            val automaticFits=runCatching{PptxRender.fit(automatic)}.isSuccess
            File(evidence,"native-before-correction.json").writeText(org.json.JSONObject().put("origin",p.source.origin).put("automaticFit",automaticFits).put("repeatedOccurrencesDistinct",true).put("manualCorrectionFollows",true).toString())
            val reviewed=p.copy(coverage=true,items=p.items.mapIndexed{i,b->b.copy(box=DocxBox(.08f,.1f+i*.16f,.92f,.24f+i*.16f),font=12f,corrected=true)})
            val d=native.deck.copy(mode=PptxMode.EDITABLE,pages=listOf(reviewed));PptxRender.fit(d);val out=File(evidence,"captured-native.pptx");out.delete();PptxWriter.write(d,out)
        }finally{native.source.frozen.directory.deleteRecursively()}
        val optical=MlKitOcrEngine().recognize(listOf(OcrPage{inputs.assets.open("m34/landscape.png")})).pages.single();check(optical.error==null)
        val proposed=DocxReconstructor.page(optical);check(proposed.blocks.none{it.corrected})
        File(evidence,"optical-before-correction.json").writeText(org.json.JSONObject().put("raw",optical.text).put("lines",org.json.JSONArray(optical.layout!!.lines.map{line->org.json.JSONObject().put("text",line.text).put("bounds",line.bounds?.let{org.json.JSONArray(listOf(it.left,it.top,it.right,it.bottom))})})).put("proposals",org.json.JSONArray(PptxGeometry.proposals(proposed,true).map{b->org.json.JSONObject().put("text",b.text).put("sourceIds",org.json.JSONArray(b.sourceIds)).put("corrected",b.corrected)})).toString())
        File(evidence,"generation.json").writeText(org.json.JSONObject().put("outputs",rows).put("observedHeapGrowthBytes",observed-initial).put("truePeakMeasured",false).put("tenPages",true).put("fitRejected",true).put("cancellationCleanup",true).toString())
    }finally{root.deleteRecursively()}
}
internal fun Instrumentation.pptxSaveChecks() {
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context;val ui=WorkflowSystemUi(this)
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[PptxViewModel::class.java];val session=ViewModelProvider(activity)[PageHarborSessionViewModel::class.java];val queue=ViewModelProvider(activity)[WorkflowViewModel::class.java]
    val nonce=UUID.randomUUID().toString().take(8);val root=File(app.cacheDir,"shared-pdfs/m34-$nonce").apply{mkdirs()};val source=File(root,"source.png").apply{writeBytes(inputs.assets.open("m34/landscape.png").use{it.readBytes()})};val original=source.readBytes()
    val evidence=File(app.getExternalFilesDir(null),"m34-evidence").apply{mkdirs()}
    fun main(block:()->Unit)=runOnMainSync(block)
    fun idle(){ui.until{!vm.busy}}
    fun reviewGenerate() {main{vm.rename("M34-$nonce.pptx");vm.compare(false)};idle();ui.until{vm.previewReady};main{vm.review(true);vm.generate()};ui.until{vm.prepared!=null&&!vm.busy}}
    fun open(){main{vm.foreground(true);vm.open(session.documentSession,session.documentRevision,session.documentOwnerToken,{true},{},{null})};ui.until{vm.capture!=null&&vm.preview!=null&&!vm.busy};reviewGenerate()}
    try {
        pptxSmokeStage="install-appearance"
        main {
            val ink=HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.15f,.75f),SignaturePoint(.5f,.5f),SignaturePoint(.85f,.25f)))),strokeWidthFraction=.04f)
            val layer=AnnotationLayer.of(listOf(AnnotationMark.create("m34-ink",AnnotationTool.SIGNATURE,AnnotationBounds(.55f,.24f,.65f,.34f),AnnotationStyle(rgb=0),signature=ink),AnnotationMark.create("m34-mark",AnnotationTool.HIGHLIGHT,AnnotationBounds(.2f,.42f,.45f,.49f),AnnotationStyle(rgb=0xFFFF00,opacity=.4f))))
            activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(FileProvider.getUriForFile(app,app.packageName+".fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),960,540),filter=DocumentFilter.GRAYSCALE,adjustments=ManualImageAdjustments(-10,15),annotations=layer))))
        }
        pptxSmokeStage="actual-navigation";ui.click("More");ui.click("PPTX export");ui.until{vm.capture!=null&&vm.preview!=null&&!vm.busy};check(vm.deck!!.mode==PptxMode.IMAGE)
        main{vm.settings(PptxMode.EDITABLE,PptxSize.WIDE)};idle();check(vm.deck!!.problem()!!.contains("annotation"));main{vm.settings(PptxMode.IMAGE,PptxSize.WIDE)};idle();reviewGenerate()
        val prepared=vm.prepared!!;val bytes=prepared.file.readBytes()
        ZipFile(prepared.file).use{z->check(workflowDigest(z.getInputStream(z.getEntry("ppt/media/image1.jpg"))).first==vm.capture!!.deck.pages.single().visual.digest)}
        prepared.preview.copyTo(File(evidence,"appearance-plan.pdf"),true)
        main{check(session.rotatePageClockwise(1))};check(vm.prepared===prepared&&prepared.file.readBytes().contentEquals(bytes))
        pptxSmokeStage="actual-picker-cancel";ui.click("Save separate PPTX");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};var backs=0
        ui.until{val p=uiAutomation.rootInActiveWindow?.packageName?.toString();if(!vm.picker&&p==app.packageName)true else{if(vm.picker&&p?.contains("documentsui")==true&&backs++<8){ui.back();SystemClock.sleep(400)};false}};check(vm.prepared===prepared)
        pptxSmokeStage="actual-picker-save";ui.click("Save separate PPTX");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        pptxDownloads(ui);ui.click("Save");ui.until{!vm.busy&&!vm.picker}
        check(vm.message?.startsWith("Separate PPTX saved")==true);val saved=vm.savedDestination!!;check(app.contentResolver.openInputStream(saved)!!.use{it.readBytes()}.contentEquals(bytes));File(evidence,"actual-save.pptx").writeBytes(bytes);check(android.provider.DocumentsContract.deleteDocument(app.contentResolver,saved))
        pptxSmokeStage="provider-faults"
        fun destination():Uri{val u=WorkflowFaultProvider.reset(app,vm.name);WorkflowFaultProvider.mime=PPTX_MIME;return u}
        var target=destination();main{check(vm.beginSave()!=null);vm.destination(null)};check(WorkflowFaultProvider.opened==0);main{vm.destination(target)};check(WorkflowFaultProvider.opened==0)
        target=destination();WorkflowFaultProvider.redirect=source;source.writeBytes(byteArrayOf());main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(source.length()==0L&&vm.message?.contains("failed")==true);source.writeBytes(original)
        target=destination();WorkflowFaultProvider.failOpen=true;main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(vm.message?.contains("failed")==true)
        target=destination();WorkflowFaultProvider.readOnlyWrite=true;main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(WorkflowFaultProvider.bytes(app).isEmpty())
        target=destination();WorkflowFaultProvider.entered=java.util.concurrent.CountDownLatch(1);WorkflowFaultProvider.release=java.util.concurrent.CountDownLatch(1)
        main{check(vm.beginSave()!=null);vm.destination(target)};check(WorkflowFaultProvider.entered!!.await(10,java.util.concurrent.TimeUnit.SECONDS));main{vm.close()};WorkflowFaultProvider.release!!.countDown();ui.until{!vm.visible};SystemClock.sleep(250);check(WorkflowFaultProvider.bytes(app).isEmpty())
        pptxSmokeStage="share-frozen";open();var shared:Intent?=null;main{vm.share{shared=it}};ui.until{shared!=null&&!vm.busy};val sent=shared!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!;check(sent.type==PPTX_MIME);val uri=sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!;check(app.contentResolver.openInputStream(uri)!!.use{it.readBytes()}.contentEquals(vm.prepared!!.file.readBytes()))
        pptxSmokeStage="stale-callback";target=destination();main{check(vm.beginSave()!=null);vm.background(true)};open();val next=vm.prepared;main{check(vm.beginSave()==null);vm.destination(target)};idle();check(vm.prepared===next&&WorkflowFaultProvider.opened==0)
        main{check(vm.beginSave()!=null);vm.destination(null)};check(source.readBytes().contentEquals(original))
        pptxSmokeStage="manual-native-review";main{vm.close();activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),960,540)))))};open()
        main{vm.settings(PptxMode.EDITABLE,PptxSize.WIDE)};idle();main{vm.add(PptxKind.TEXT,DocxBox(.1f,.1f,.8f,.5f))};val text=vm.deck!!.pages.single().items.last();main{vm.item(text.copy(text="Reviewed native text",font=24f));vm.add(PptxKind.RECT,DocxBox(.1f,.7f,.3f,.8f))};reviewGenerate();check(vm.prepared!!.deck.pages.single().items.all{it.corrected});vm.prepared!!.file.copyTo(File(evidence,"manual-native-save.pptx"),true)
        pptxSmokeStage="cancel-during-render";main{vm.compare(false);vm.close()};ui.until{!vm.visible&&!vm.busy}
        File(evidence,"save-checks.json").writeText("{\"actualNavigation\":true,\"sourceSnapshotFrozen\":true,\"actualSaveAndCancel\":true,\"sourceAlias\":true,\"providerFailures\":true,\"lockBeforeFirstChunk\":true,\"staleCallback\":true,\"recreationDiscard\":true,\"shareReadGrant\":true,\"originalBytesExact\":true,\"manualNativeReview\":true,\"markedEditableDeclined\":true,\"appearanceEditsPresentForInspection\":true}")
        pptxSmokeStage="complete"
    }catch(t:Throwable){File(evidence,"failure-stage.txt").writeText(pptxSmokeStage+"\n"+ui.lastClick+"\n"+vm.message);val bitmap=uiAutomation.takeScreenshot();try{File(evidence,"failure.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()};throw t}
    finally{repeat(6){if(uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true){ui.back();SystemClock.sleep(200)}};WorkflowFaultProvider.release?.countDown();WorkflowFaultProvider.reset(app,"synthetic.pdf");main{vm.close();session.clearScan();activity.finish()};root.deleteRecursively()}
    waitForIdleSync();ui.until{queue.queue.store.closed}
}
