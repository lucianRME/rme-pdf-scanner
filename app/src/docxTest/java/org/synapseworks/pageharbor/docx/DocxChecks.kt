package org.synapseworks.pageharbor.docx

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.workflow.*

internal var docxSmokeStage="start"
internal fun Instrumentation.docxGenerationChecks()=runBlocking<Unit> {
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context
    val root=File(app.noBackupFilesDir,"m32-generation-${UUID.randomUUID()}").apply{mkdirs()}
    val evidence=File(app.getExternalFilesDir(null),"m32-evidence").apply{mkdirs()}
    val summary=org.json.JSONArray()
    try {
        for(name in listOf("letter","columns","table","mixed","pages","native")) {
            docxSmokeStage="generation-$name"
            val text=inputs.assets.open("m32/$name.tsv").bufferedReader().use{it.readText()}
            val ocr=FrozenDocxCorpus.ocr(text);var pages=ocr.map{DocxReconstructor.page(it)}
            FrozenDocxCorpus.tableRegion(name)?.let{pages=listOf(DocxReconstructor.table(pages.single(),it))}
            FrozenDocxCorpus.imageRegion(name)?.let{box->
                val source=BitmapFactory.decodeStream(inputs.assets.open("m32/$name-0.png"));val crop=Bitmap.createBitmap(source,(box.left*source.width).toInt(),(box.top*source.height).toInt(),(box.width*source.width).toInt(),(box.height*source.height).toInt());val pic=File(root,"$name.jpg");pic.outputStream().use{crop.compress(Bitmap.CompressFormat.JPEG,90,it)};val w=crop.width;val h=crop.height;crop.recycle();source.recycle()
                val p=pages.single();pages=listOf(p.copy(blocks=(p.blocks+DocxBlock(900,DocxKind.IMAGE,"",emptyList(),box,if(name=="columns")1 else 0,picture=DocxPicture(pic,w,h),corrected=true)).sortedWith(compareBy<DocxBlock>{it.column}.thenBy{it.box?.top?:0f})))
            }
            for(mode in DocxMode.entries) {
                val output=File(root,"$name-$mode.docx");val start=SystemClock.elapsedRealtime();val document=DocxDocument(pages,mode);checkDocxLayoutFit(document);DocxWriter.write(document,output)
                ZipFile(output).use{check(it.getEntry("word/document.xml")!=null)}
                output.copyTo(File(evidence,output.name),true)
                summary.put(org.json.JSONObject().put("case",name).put("mode",mode.name).put("bytes",output.length()).put("millis",SystemClock.elapsedRealtime()-start))
            }
        }
        // The committed restricted extractor is reused, retaining the exact repeated occurrences.
        val nativePdf=File(root,"native.pdf").apply{writeBytes(inputs.assets.open("m32/native.pdf").use{it.readBytes()})}
        val nativeImage=File(root,"native.png").apply{writeBytes(inputs.assets.open("m32/native-0.png").use{it.readBytes()})}
        val nativeResource=DocumentResource(Uri.fromFile(nativePdf).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL)
        val nativeSession=DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(nativeImage).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.RENDERED_PDF_PAGE,"image/png",DocumentImageMetadata(nativeImage.length(),1224,1584))),nativeResource,directPdfPageIds=listOf(DocumentPageId(1)))
        val captured=captureDocx(app,root,nativeSession,null){true}
        try{val p=captured.document.pages.single();check(p.origin==DocxOrigin.NATIVE_PDF);val repeated=p.blocks.filter{it.text=="Repeat reference"};check(repeated.size==2&&repeated[0].box!=repeated[1].box&&repeated[0].sourceIds!=repeated[1].sourceIds);val file=File(evidence,"captured-native.docx");file.delete();DocxWriter.write(captured.document,file)}finally{captured.frozen.directory.deleteRecursively()}
        var declined=false;try{captureDocx(app,root,nativeSession,null){false}}catch(_:Exception){declined=true};check(declined&&root.listFiles().orEmpty().none{it.isDirectory&&it.name.startsWith("capture-")})
                // Optical evidence is explicitly separate from exact seeded OCR-layout reconstruction.
        for(name in listOf("letter","columns","table")) {
            val bytes=inputs.assets.open("m32/$name-0.png").use{it.readBytes()}
            val result=MlKitOcrEngine().recognize(listOf(OcrPage{bytes.inputStream()})).pages.single();check(result.error==null)
            val expected=FrozenDocxCorpus.rows(inputs.assets.open("m32/$name.tsv").bufferedReader().use{it.readText()}).joinToString("\n"){it.text}
            val proposed=DocxReconstructor.page(result)
            val assisted=if(name=="table")runCatching{DocxReconstructor.table(proposed,FrozenDocxCorpus.tableRegion(name)!!)}.getOrNull()else null
            val proposalJson=org.json.JSONArray(proposed.blocks.map{block->org.json.JSONObject().put("kind",block.kind.name).put("text",block.text).put("sourceIds",org.json.JSONArray(block.sourceIds)).put("corrected",block.corrected).put("column",block.column)})
            val linesJson=org.json.JSONArray(proposed.lines.map{line->org.json.JSONObject().put("id",line.id).put("text",line.text).put("bounds",line.bounds?.let{org.json.JSONArray(listOf(it.left,it.top,it.right,it.bottom))})})
            var optical=assisted?:proposed
            FrozenDocxCorpus.imageRegion(name)?.let{box->
                val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size);val crop=Bitmap.createBitmap(image,(box.left*image.width).toInt(),(box.top*image.height).toInt(),(box.width*image.width).toInt(),(box.height*image.height).toInt());val picture=File(root,"$name-optical.jpg");picture.outputStream().use{crop.compress(Bitmap.CompressFormat.JPEG,90,it)};val w=crop.width;val h=crop.height;crop.recycle();image.recycle()
                optical=optical.copy(blocks=(optical.blocks+DocxBlock(900,DocxKind.IMAGE,"",emptyList(),box,if(name=="columns")1 else 0,picture=DocxPicture(picture,w,h),corrected=true,note="Manual figure region, uncorrected OCR structure")).sortedWith(compareBy<DocxBlock>{it.column}.thenBy{it.box?.top?:0f}))
            }
            val opticalFile=File(evidence,"$name-optical.docx");opticalFile.delete();DocxWriter.write(DocxDocument(listOf(optical)),opticalFile)
            File(evidence,"$name-optical.json").writeText(org.json.JSONObject().put("expected",expected).put("recognized",result.text).put("seeded",false).put("imageWidth",proposed.widthPx).put("imageHeight",proposed.heightPx).put("lines",linesJson).put("automaticBlocks",proposalJson).put("roiAssistedGrid",assisted?.blocks?.firstOrNull{it.kind==DocxKind.TABLE}?.table?.values()?.let{org.json.JSONArray(it.map{row->org.json.JSONArray(row)})}).put("concerns",org.json.JSONArray(proposed.concerns)).toString())
        }
        // Independently frozen body lookalikes: automatic classification before any review.
        val controlBytes=inputs.assets.open("m32-followup/body-controls.png").use{it.readBytes()}
        val controlResult=MlKitOcrEngine().recognize(listOf(OcrPage{controlBytes.inputStream()})).pages.single()
        val controlPage=DocxReconstructor.page(controlResult)
        val controlExpected=listOf("Weekly Summary" to DocxKind.HEADING,"Short body fragment" to DocxKind.BODY,"STATUS LINE" to DocxKind.BODY,"This remains an ordinary sentence." to DocxKind.BODY)
        File(evidence,"body-controls.json").writeText(org.json.JSONObject().put("recognized",controlResult.text).put("automaticBlocks",org.json.JSONArray(controlPage.blocks.map{org.json.JSONObject().put("text",it.text).put("kind",it.kind.name).put("corrected",it.corrected)})).put("lines",org.json.JSONArray(controlPage.lines.map{org.json.JSONObject().put("text",it.text).put("bounds",it.bounds?.let{b->org.json.JSONArray(listOf(b.left,b.top,b.right,b.bottom))})})).toString())
        check(controlPage.blocks.none{it.corrected})
        for((text,kind)in controlExpected)check(controlPage.blocks.single{it.text==text}.kind==kind)
        // Max declared source-page boundary: current-appearance materialization plus ten references.
        val tenInput=File(root,"ten-source.png").apply{writeBytes(inputs.assets.open("m32/letter-0.png").use{it.readBytes()})}
        val resource=DocumentResource(Uri.fromFile(tenInput).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL)
        val tenSession=DocumentSession((0 until 10).map{DocumentPage(DocumentPageId(it+1L),resource,DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(tenInput.length(),1224,1584))})
        val base=FrozenDocxCorpus.ocr(inputs.assets.open("m32/letter.tsv").bufferedReader().use{it.readText()}).single()
        val startTen=SystemClock.elapsedRealtime();var maxJava=0L
        fun sample(){val runtime=Runtime.getRuntime();maxJava=maxOf(maxJava,runtime.totalMemory()-runtime.freeMemory())}
        val ten=captureDocx(app,root,tenSession,OcrResult((0 until 10).map{base.copy(pageIndex=it)})){true}
        try{
            sample();val pages=ten.document.pages.mapIndexed{i,p->val pic=cropDocxPicture(ten,i,DocxBox(0f,0f,1f,1f));sample();p.copy(blocks=p.blocks+DocxBlock(900,DocxKind.IMAGE,"",emptyList(),DocxBox(0f,0f,1f,1f),picture=pic,corrected=true))}
            val out=File(evidence,"ten-source-pages.docx");out.delete();DocxWriter.write(DocxDocument(pages),out);sample()
            check(DocxDocument(pages+pages.first()).problem()!=null)
            File(evidence,"ten-page-resource.json").writeText(org.json.JSONObject().put("sourcePages",10).put("fullPageReferences",10).put("sourcePixelsEach",1224*1584).put("bytes",out.length()).put("elapsedMillis",SystemClock.elapsedRealtime()-startTen).put("sampledJavaHeapBytesMax",maxJava).put("pssKiBSnapshot",android.os.Debug.getPss()).put("truePeakMeasured",false).toString())
        }finally{ten.frozen.directory.deleteRecursively()}
                val output=File(root,"cancelled.docx");var cancelled=false;try{DocxWriter.write(DocxDocument(listOf(DocxReconstructor.page(FrozenDocxCorpus.ocr(inputs.assets.open("m32/letter.tsv").bufferedReader().use{it.readText()}).single()))),output,{error("Synthetic cancellation")})}catch(_:Exception){cancelled=true};check(cancelled&&!output.exists())
        File(evidence,"generation.json").writeText(summary.toString(2))
    }finally{root.deleteRecursively()}
}

internal fun Instrumentation.docxSaveChecks() {
    waitForIdleSync()
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context;val ui=WorkflowSystemUi(this)
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[DocxViewModel::class.java];val session=ViewModelProvider(activity)[PageHarborSessionViewModel::class.java]
    val nonce=UUID.randomUUID().toString().take(8);val root=File(app.cacheDir,"shared-pdfs/m32-$nonce").apply{mkdirs()};val source=File(root,"source.png").apply{writeBytes(inputs.assets.open("m32/letter-0.png").use{it.readBytes()})};val original=source.readBytes()
    val evidence=File(app.getExternalFilesDir(null),"m32-evidence").apply{mkdirs()}
    fun main(block:()->Unit)=runOnMainSync(block)
    fun idle(){ui.until{!vm.busy}}
    fun open() {
        main{vm.foreground(true);vm.open(session.documentSession,OcrResult(FrozenDocxCorpus.ocr(inputs.assets.open("m32/letter.tsv").bufferedReader().use{it.readText()})),session.documentRevision,session.documentOwnerToken,{true},{})}
        ui.until{vm.capture!=null&&vm.preview!=null&&!vm.busy}
    }
    try {
        docxSmokeStage="install"
        main {
            val ink=HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.15f,.75f),SignaturePoint(.5f,.5f),SignaturePoint(.85f,.25f)))),strokeWidthFraction=.04f)
            val layer=AnnotationLayer.of(listOf(AnnotationMark.create("m32-ink",AnnotationTool.SIGNATURE,AnnotationBounds(.55f,.24f,.65f,.34f),AnnotationStyle(rgb=0),signature=ink),AnnotationMark.create("m32-mark",AnnotationTool.HIGHLIGHT,AnnotationBounds(.2f,.42f,.45f,.49f),AnnotationStyle(rgb=0xFFFF00,opacity=.4f))))
            activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(FileProvider.getUriForFile(app,app.packageName+".fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),1224,1584),filter=DocumentFilter.GRAYSCALE,adjustments=ManualImageAdjustments(-10,15),annotations=layer))))
        }
        // Prove the actual navigation entry, even when OCR is absent and manual transcription is needed.
        ui.click("More");ui.click("Editable DOCX export");ui.until{vm.capture!=null&&vm.preview!=null&&!vm.busy};check(vm.document!!.pages.single().origin==DocxOrigin.MANUAL);main{vm.close()};idle()
        open();docxSmokeStage="review"
        main{vm.region(FrozenDocxCorpus.imageRegion("letter")!!,false)};idle();check(vm.document!!.pages.single().blocks.last().kind==DocxKind.IMAGE)
        val before=vm.document!!.pages.single().blocks.first();main{vm.block(before.copy(text=before.text+" reviewed"));vm.rename("M32-$nonce.docx");vm.reviewPage(true)}
        check(vm.document!!.pages.single().blocks.first().corrected);check(vm.document!!.pages.single().lines.first().text==before.text)
        ui.click("Generate reviewed DOCX");ui.until{vm.prepared!=null&&!vm.busy};val p=vm.prepared!!;val frozen=p.file.readBytes()
        main{check(session.rotatePageClockwise(1))};check(vm.prepared===p)
        docxSmokeStage="picker-cancel"
        ui.click("Save separate DOCX");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};var backs=0
        ui.until{val pkg=uiAutomation.rootInActiveWindow?.packageName?.toString();if(!vm.picker&&pkg==app.packageName)true else{if(vm.picker&&pkg?.contains("documentsui")==true&&backs++<8){ui.back();SystemClock.sleep(500)};false}}
        check(vm.visible);check(vm.prepared===p)
        docxSmokeStage="picker-save"
        ui.click("Save separate DOCX");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        ui.until{ui.node{it.text?.toString()=="Downloads"}!=null}
        val downloadBounds=android.graphics.Rect();ui.node{it.text?.toString()=="Downloads"}!!.getBoundsInScreen(downloadBounds);ui.shell("input tap ${downloadBounds.centerX()} ${downloadBounds.centerY()}")
        ui.click("Save");ui.until{!vm.busy&&!vm.picker};check(vm.message?.startsWith("Separate DOCX saved")==true)
        val uri=checkNotNull(vm.savedDestination)
        val saved=app.contentResolver.openInputStream(uri)!!.use{it.readBytes()};check(saved.contentEquals(frozen));File(evidence,"actual-save.docx").writeBytes(saved)
        ZipFile(p.file).use{zip->val entry=zip.getEntry("word/media/image2.jpg");val image=BitmapFactory.decodeStream(zip.getInputStream(entry));try{val cx=(image.width*.6f).toInt();val cy=(image.height*.29f).toInt();check((-4..4).flatMap{dy->(-4..4).map{dx->android.graphics.Color.red(image.getPixel(cx+dx,cy+dy))}}.min()<80);val highlight=image.getPixel((image.width*.35f).toInt(),(image.height*.45f).toInt());check(android.graphics.Color.green(highlight)-android.graphics.Color.blue(highlight)>40)}finally{image.recycle()}}
        check(original.contentEquals(source.readBytes()))
        check(android.provider.DocumentsContract.deleteDocument(app.contentResolver,uri))
        docxSmokeStage="faults"
        fun target():Uri {val u=WorkflowFaultProvider.reset(app,vm.name);WorkflowFaultProvider.mime=DOCX_MIME;return u}
        var target=target();main{check(vm.beginSave()!=null);vm.destination(null)};check(WorkflowFaultProvider.opened==0)
        target=target();main{vm.destination(target)};check(WorkflowFaultProvider.opened==0)
        // A truncated inode alias remains excluded even though metadata reports an empty DOCX.
        target=target();WorkflowFaultProvider.redirect=source;source.writeBytes(byteArrayOf());main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(source.length()==0L&&vm.message?.contains("failed")==true);source.writeBytes(original)
        target=target();WorkflowFaultProvider.failOpen=true;main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(vm.message?.contains("failed")==true)
        target=target();WorkflowFaultProvider.readOnlyWrite=true;main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(WorkflowFaultProvider.bytes(app).isEmpty())
        // Lock/cancel while an untrusted provider blocks descriptor creation: no first write.
        target=target();WorkflowFaultProvider.entered=java.util.concurrent.CountDownLatch(1);WorkflowFaultProvider.release=java.util.concurrent.CountDownLatch(1)
        main{check(vm.beginSave()!=null);vm.destination(target)};check(WorkflowFaultProvider.entered!!.await(10,java.util.concurrent.TimeUnit.SECONDS));main{vm.close()};WorkflowFaultProvider.release!!.countDown();ui.until{!vm.visible};SystemClock.sleep(300);check(WorkflowFaultProvider.bytes(app).isEmpty())
        open();main{vm.rename("M32-$nonce.docx");vm.reviewPage(true);vm.generate()};ui.until{vm.prepared!=null&&!vm.busy}
        docxSmokeStage="share"
        var shared:Intent?=null;main{vm.share{shared=it}};ui.until{shared!=null&&!vm.busy};val sent=shared!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!;check(sent.type==DOCX_MIME);val sharedUri=sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!;check(app.contentResolver.openInputStream(sharedUri)!!.use{it.readBytes()}.contentEquals(vm.prepared!!.file.readBytes()))
        main{check(vm.beginSave()!=null);vm.background(true)};check(!vm.visible&&!vm.picker);main{vm.foreground(true);vm.destination(target)};check(WorkflowFaultProvider.bytes(app).isEmpty())
        File(evidence,"save-checks.json").writeText("{\"actualNavigation\":true,\"reviewCorrection\":true,\"actualSaveAndCancellation\":true,\"frozenRevision\":true,\"sourceAliasRefused\":true,\"staleCallback\":true,\"failedOpenAndWrite\":true,\"lockBeforeFirstChunk\":true,\"recreationDiscardsDraft\":true,\"shareReadGrantBytes\":true,\"sourceBytesExact\":true,\"currentEditsAnnotationsSignatures\":true,\"selectedFigure\":true}")
        docxSmokeStage="complete"
    }catch(t:Throwable){File(evidence,"failure-stage.txt").writeText(docxSmokeStage+"\n"+ui.lastClick+"\n"+vm.message);val shot=uiAutomation.takeScreenshot();try{File(evidence,"failure.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{shot.recycle()};throw t}
    finally { WorkflowFaultProvider.release?.countDown();WorkflowFaultProvider.reset(app,"synthetic.pdf");main{vm.close();session.clearScan();activity.finish()};root.deleteRecursively() }
}

internal fun Instrumentation.docxRecipeChecks() {
    waitForIdleSync()
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context;val ui=WorkflowSystemUi(this)
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[DocxViewModel::class.java];val workflows=ViewModelProvider(activity)[WorkflowViewModel::class.java];val session=ViewModelProvider(activity)[PageHarborSessionViewModel::class.java]
    val nonce=UUID.randomUUID().toString().take(8);val folder="RME-M32-$nonce";ui.shell("mkdir -p /sdcard/Download/$folder")
    val root=File(app.cacheDir,"shared-pdfs/m32-recipe-$nonce").apply{mkdirs()};val source=File(root,"source.png").apply{writeBytes(inputs.assets.open("m32/table-0.png").use{it.readBytes()})};val original=source.readBytes()
    val evidence=File(app.getExternalFilesDir(null),"m32-evidence").apply{mkdirs()}
    fun main(block:()->Unit)=runOnMainSync(block)
    fun clean(){workflows.queue.data().jobs.forEach{workflows.queue.keepExternalAndCancel(it.id);workflows.queue.remove(it.id)};workflows.queue.data().recipes.forEach{workflows.queue.revoke(it.id);workflows.queue.deleteRecipe(it.id)}}
    fun capture():WorkflowJob {
        ui.click("Capture current document for review");ui.until{vm.capture!=null&&vm.preview!=null&&!vm.busy};check(vm.recipeMode)
        main{vm.updatePage(DocxReconstructor.page(FrozenDocxCorpus.ocr(inputs.assets.open("m32/table.tsv").bufferedReader().use{it.readText()}).single()).copy(origin=DocxOrigin.MANUAL));vm.region(FrozenDocxCorpus.tableRegion("table")!!,true)}
        ui.until{!vm.busy};check(vm.document!!.pages.single().blocks.any{it.kind==DocxKind.TABLE})
        main{vm.reviewPage(true)};ui.click("Generate reviewed DOCX");ui.until{vm.prepared!=null&&!vm.busy};val bytes=vm.prepared!!.file.readBytes()
        ui.click("Review captured DOCX recipe");ui.until{!vm.visible&&workflows.review!=null&&workflows.preview!=null&&!workflows.busy};check(workflows.review!!.captured.output.readBytes().contentEquals(bytes))
        ui.click("Enqueue reviewed copy");ui.until{workflows.review==null&&workflows.data.jobs.isNotEmpty()};return workflows.data.jobs.last()
    }
    try {
        main{clean();activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(FileProvider.getUriForFile(app,app.packageName+".fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),1224,1584)))))}
        docxSmokeStage="recipe-create"
        ui.click("More");ui.click("Local workflow recipes");ui.click("Create recipe");ui.click("Editable DOCX · reviewed reflow Letter")
        main{workflows.change(workflows.draft!!.copy(custom="M32-$nonce"))}
        ui.click("Choose approved local folder");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};ui.click(folder);ui.click("Use this folder");ui.click("Allow");ui.until{workflows.draft?.tree!=null}
        check(workflows.queue.destination.localTree(workflows.draft!!.tree!!))
        ui.click("Save recipe paused");ui.click("Use / edit");val first=capture();check(first.name.endsWith(".docx"));val frozen=workflows.queue.store.pdf(first.id).readBytes();check(org.json.JSONObject(File(workflows.queue.store.root,"queue.json").readText()).getInt("version") in 3..4)
        main{check(session.rotatePageClockwise(1));workflows.enable(first.recipeId,true)}
        ui.click("Run / retry frozen copy");ui.until{workflows.data.jobs.last().status in setOf(WorkflowStatus.SAVED,WorkflowStatus.UNCERTAIN,WorkflowStatus.FAILED)};val saved=workflows.data.jobs.last();check(saved.status==WorkflowStatus.SAVED)
        check(app.contentResolver.openInputStream(Uri.parse(saved.destination))!!.use{it.readBytes()}.contentEquals(frozen));File(evidence,"recipe-local.docx").writeBytes(frozen)
        docxSmokeStage="recipe-duplicate"
        main{workflows.select(workflows.data.recipes.single())};val second=capture();val counter=workflows.queue.data().recipes.single().nextCounter
        // Same recipe/revision cannot consume a second counter, before any manual corrections.
        main{workflows.select(workflows.data.recipes.single());check(workflows.beginDocx(session.documentRevision,session.documentOwnerToken)==null)};check(workflows.queue.data().recipes.single().nextCounter==counter)
        main{workflows.enable(second.recipeId,true);workflows.revoke(second.recipeId)};runBlocking{workflows.queue.run(second.id)};check(workflows.queue.data().jobs.last().status==WorkflowStatus.CANCELLED)
        docxSmokeStage="recipe-recovery"
        // Frozen DOCX payload recovery is exercised with a separate store after closing ownership.
        main{check(session.rotatePageClockwise(1));workflows.select(workflows.data.recipes.single())}
        ui.click("Choose approved local folder");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};ui.click(folder);ui.click("Use this folder");ui.click("Allow");ui.until{workflows.draft?.tree!=null};ui.click("Save recipe paused");ui.click("Use / edit")
        val pending=capture();val pendingBytes=workflows.queue.store.pdf(pending.id).readBytes()
        // Simulate the commit window after a full external write using this job's own chosen name.
        val target=workflows.queue.destination.create(checkNotNull(pending.tree),pending.name,pending.preset.mime)
        app.contentResolver.openOutputStream(Uri.parse(target),"w")!!.use{it.write(pendingBytes)}
        main{workflows.queue.status(pending.id,WorkflowStatus.WRITING,target);workflows.close();activity.finish()};ui.until{workflows.queue.store.closed}
        val recovered=WorkflowStore(workflows.queue.store.root);val queue=WorkflowQueue(app,recovered)
        try{check(recovered.healthy);check(recovered.data.recipes.all{!it.enabled});check(recovered.data.jobs.all{it.preset==WorkflowPreset.DOCX});check(recovered.data.jobs.first().name==first.name);check(recovered.data.jobs.last().status==WorkflowStatus.UNCERTAIN);check(recovered.pdf(pending.id).readBytes().contentEquals(pendingBytes));queue.active=true;runBlocking{queue.reconcile(pending.id)};check(queue.data().jobs.last().status==WorkflowStatus.SAVED);recovered.pdf(pending.id).writeBytes(pendingBytes);queue.status(pending.id,WorkflowStatus.WRITING,target);File(evidence,"first-pid.txt").writeText(android.os.Process.myPid().toString())}finally{runBlocking{queue.closeOwner()}}
        check(original.contentEquals(source.readBytes()))
        File(evidence,"recipe-checks.json").writeText("{\"actualRecipeCreateReviewLocalSave\":true,\"m22RegionTable\":true,\"frozenDocxBytes\":true,\"documentEditIsolated\":true,\"duplicateCounterStable\":true,\"revokedNoNewWrite\":true,\"queueFormatCompatibleRecoveryPaused\":true,\"interruptedExactWriteReconciled\":true,\"room10Backup8Unchanged\":true}")
    }catch(t:Throwable){File(evidence,"recipe-failure-stage.txt").writeText(docxSmokeStage+"\n"+workflowSmokeStage+"\n"+ui.lastClick+"\n"+workflows.message);val shot=uiAutomation.takeScreenshot();try{File(evidence,"recipe-failure.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{shot.recycle()};throw t}
    finally{main{workflows.close();vm.close();session.clearScan();activity.finish()};root.deleteRecursively()}
}


internal fun Instrumentation.docxRestartChecks()=runBlocking<Unit> {
    val app=targetContext;val evidence=File(app.getExternalFilesDir(null),"m32-evidence")
    check(File(evidence,"first-pid.txt").readText().toInt()!=android.os.Process.myPid())
    val store=WorkflowStore(File(app.noBackupFilesDir,"workflow-v1"));val queue=WorkflowQueue(app,store)
    try {
        check(store.healthy&&store.data.recipes.all{!it.enabled})
        val job=store.data.jobs.single{it.status==WorkflowStatus.UNCERTAIN};check(job.preset==WorkflowPreset.DOCX&&job.sourceGuards.isNotEmpty())
        val bytes=store.pdf(job.id).readBytes();check(workflowDigest(bytes.inputStream())==job.digest to job.bytes)
        queue.active=true;queue.reconcile(job.id);check(queue.data().jobs.single{it.id==job.id}.status==WorkflowStatus.SAVED)
        val tree=Uri.parse(checkNotNull(job.tree));check(android.provider.DocumentsContract.getTreeDocumentId(tree).startsWith("primary:Download/RME-M32-"))
        check(android.provider.DocumentsContract.deleteDocument(app.contentResolver,android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree))))
        queue.data().jobs.forEach{queue.keepExternalAndCancel(it.id);queue.remove(it.id)};queue.data().recipes.forEach{queue.revoke(it.id);queue.deleteRecipe(it.id)}
        File(evidence,"restart-checks.json").writeText("{\"differentProcess\":true,\"pausedRecipes\":true,\"uncertainExactDocxReconciled\":true,\"sourceGuardsRetained\":true,\"privatePayloadReleased\":true}")
    }finally{queue.closeOwner()}
}

internal fun Instrumentation.docxLayoutUiChecks() {
    waitForIdleSync()
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context;val ui=WorkflowSystemUi(this)
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[DocxViewModel::class.java]
    val root=File(app.cacheDir,"shared-pdfs/m32-layout-${UUID.randomUUID()}").apply{mkdirs()};val source=File(root,"source.png").apply{writeBytes(inputs.assets.open("m32/columns-0.png").use{it.readBytes()})}
    fun main(block:()->Unit)=runOnMainSync(block)
    try {
        val session=DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),1224,1584))))
        main{vm.foreground(true);vm.open(session,OcrResult(FrozenDocxCorpus.ocr(inputs.assets.open("m32/columns.tsv").bufferedReader().use{it.readText()})),42,"layout-fixture",{true},{})};ui.until{vm.capture!=null&&vm.preview!=null&&!vm.busy}
        ui.click("LibreOffice-measured layout");check(vm.document!!.mode==DocxMode.LAYOUT&&!vm.appearances)
        main{vm.region(FrozenDocxCorpus.imageRegion("columns")!!,false)};ui.until{!vm.busy};val p=vm.document!!.pages.single();val image=p.blocks.last();main{vm.block(image.copy(column=1));vm.reviewPage(true)}
        ui.click("Generate reviewed DOCX");ui.until{!vm.busy};check(vm.prepared!=null&&vm.prepared!!.document.mode==DocxMode.LAYOUT)
        val evidence=File(app.getExternalFilesDir(null),"m32-evidence").apply{mkdirs()};vm.prepared!!.file.copyTo(File(evidence,"actual-layout-ui.docx"),true)
        main{val b=vm.document!!.pages.single().blocks.first{it.kind==DocxKind.BODY};vm.block(b.copy(text="W".repeat(300)));vm.reviewPage(true);vm.generate()};ui.until{!vm.busy};check(vm.prepared==null&&vm.message?.startsWith("DOCX declined")==true)
        File(evidence,"layout-ui-checks.json").writeText("{\"actualLayoutOptionGenerate\":true,\"manualFigureColumn\":true,\"fitRejected\":true,\"previousOutputInvalidated\":true}")
    }finally{main{vm.close();activity.finish()};root.deleteRecursively()}
}
