package org.synapseworks.pageharbor.pptx

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.UUID
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.synapseworks.pageharbor.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.workflow.*

internal fun Instrumentation.pptxReaderChecks() {
    val root=File(targetContext.noBackupFilesDir,"m34-reader-${UUID.randomUUID()}").apply{mkdirs()}
    try {
        File(root,"queue.json").writeText("{\"version\":4,\"recipes\":[],\"jobs\":[],\"grants\":[]}")
        var store=WorkflowStore(root);check(store.healthy)
        // Existing opaque PDF/DOCX/XLSX job bytes and typed metadata must survive 4 -> 5 unchanged.
        val presets=listOf(WorkflowPreset.NORMAL,WorkflowPreset.DOCX,WorkflowPreset.XLSX)
        val recipes=presets.map{WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic retained job",preset=it,nextCounter=2)}
        val retained=recipes.map{r->val id=UUID.randomUUID().toString();val file=store.pdf(id).apply{writeBytes(("synthetic opaque "+r.preset.name).toByteArray())};val digest=workflowDigest(file.inputStream());WorkflowJob(id,r.id,1,"Retained."+r.preset.extension,r.preset,null,1,"synthetic-owner",id,"1970-01-01T00:00:00Z","UTC",1,digest.first,digest.second,sourceGuards=listOf(workflowSourceGuard(targetContext,Uri.fromFile(file))))}
        store.commit(WorkflowData(recipes,retained));check(org.json.JSONObject(File(root,"queue.json").readText()).getInt("version")==4)
        val expected=retained.associate{it.id to store.pdf(it.id).readBytes()}
        val queue=WorkflowQueue(targetContext,store);queue.active=true
        val recipe=WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic slides",preset=WorkflowPreset.PPTX);queue.saveRecipe(recipe)
        check(org.json.JSONObject(File(root,"queue.json").readText()).getInt("version")==5);runBlocking{queue.closeOwner()}
        val sentinel=File(root,"capture-owned").apply{mkdir()};File(sentinel,"keep").writeText("synthetic");val original=File(root,"queue.json").readBytes()
        val legacy=LegacyM33WorkflowStore(root);try{check(!legacy.healthy&&sentinel.isDirectory&&File(root,"queue.json").readBytes().contentEquals(original))}finally{legacy.close()}
        store=WorkflowStore(root);check(store.healthy&&store.data.recipes.last().preset==WorkflowPreset.PPTX&&store.data.jobs==retained);check(expected.all{(id,bytes)->store.pdf(id).readBytes().contentEquals(bytes)});store.commit(WorkflowData());check(org.json.JSONObject(File(root,"queue.json").readText()).getInt("version")==5);store.close()
        File(File(targetContext.getExternalFilesDir(null),"m34-evidence").apply{mkdirs()},"reader.json").writeText("{\"readsFormat4\":true,\"preservesPdfDocxXlsxJobBytesAndTypes\":true,\"writesSticky5ForPptx\":true,\"literalFormat4ReaderRefusesBeforeCleanup\":true,\"newReaderReopens5\":true}")
    }finally{root.deleteRecursively()}
}
internal fun Instrumentation.pptxRecipeChecks() {
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context;val ui=WorkflowSystemUi(this)
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[PptxViewModel::class.java];val session=ViewModelProvider(activity)[PageHarborSessionViewModel::class.java];val workflows=ViewModelProvider(activity)[WorkflowViewModel::class.java]
    val evidence=File(app.getExternalFilesDir(null),"m34-evidence").apply{mkdirs()};val nonce=UUID.randomUUID().toString().take(8);val folder="RME-M34-$nonce";ui.shell("mkdir -p /sdcard/Download/$folder")
    val source=File(app.cacheDir,"shared-pdfs/m34-recipe-$nonce.png").apply{parentFile!!.mkdirs();writeBytes(inputs.assets.open("m34/landscape.png").use{it.readBytes()})};val original=source.readBytes()
    fun main(block:()->Unit)=runOnMainSync(block)
    fun clean(){workflows.queue.data().jobs.forEach{workflows.queue.keepExternalAndCancel(it.id);workflows.queue.remove(it.id)};workflows.queue.data().recipes.forEach{workflows.queue.revoke(it.id);workflows.queue.deleteRecipe(it.id)}}
    fun grant(){
        ui.click("Choose approved local folder");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ui.until{ui.node{it.text?.toString()==folder}!=null||ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}!=null}
        if(ui.node{it.text?.toString()==folder}==null){ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);pptxDownloads(ui)}
        ui.click(folder);ui.click("Use this folder");ui.click("Allow");ui.until{workflows.draft?.tree!=null}
    }
    fun capture():Pair<WorkflowJob,PptxPrepared>{
        ui.click("Capture current document for review");ui.until{vm.capture!=null&&vm.preview!=null&&!vm.busy};check(vm.deck!!.mode==PptxMode.IMAGE)
        main{vm.compare(false)};ui.until{!vm.busy&&vm.previewReady};main{vm.review(true)};ui.click("Generate reviewed PPTX");ui.until{vm.prepared!=null&&!vm.busy};val p=vm.prepared!!;val bytes=p.file.readBytes()
        ui.click("Review frozen PPTX recipe");ui.until{workflows.review!=null&&workflows.preview!=null&&!workflows.busy};check(workflows.review!!.captured.output.readBytes().contentEquals(bytes))
        ui.click("Enqueue reviewed copy");ui.until{workflows.review==null&&workflows.data.jobs.isNotEmpty()};return workflows.data.jobs.last()to p
    }
    try{
        pptxSmokeStage="recipe-create";main{activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),960,540)))));vm.close();workflows.open();clean()}
        ui.click("Create recipe");ui.click("PPTX · reviewed image or native slides");grant();check(workflows.queue.destination.localTree(workflows.draft!!.tree!!));ui.click("Save recipe paused");ui.click("Use / edit")
        val(first,p)=capture();val bytes=workflows.queue.store.pdf(first.id).readBytes();check(first.name.endsWith(".pptx")&&org.json.JSONObject(File(workflows.queue.store.root,"queue.json").readText()).getInt("version")==5)
        main{check(session.rotatePageClockwise(1));workflows.enable(first.recipeId,true)};ui.click("Run / retry frozen copy");ui.until{workflows.data.jobs.last().status in setOf(WorkflowStatus.SAVED,WorkflowStatus.FAILED,WorkflowStatus.UNCERTAIN)};val saved=workflows.data.jobs.last();check(saved.status==WorkflowStatus.SAVED);check(app.contentResolver.openInputStream(Uri.parse(saved.destination))!!.use{it.readBytes()}.contentEquals(bytes));File(evidence,"recipe-local.pptx").writeBytes(bytes)
        val counter=workflows.data.recipes.single().nextCounter;main{workflows.select(workflows.data.recipes.single());workflows.acceptPptx(p,{true},{})};check(!workflows.busy&&workflows.data.jobs.size==1&&workflows.queue.data().recipes.single().nextCounter==counter)
        pptxSmokeStage="recipe-revoke";main{workflows.select(workflows.data.recipes.single())};val(second,_)=capture();main{workflows.enable(second.recipeId,true);workflows.revoke(second.recipeId)};runBlocking{workflows.queue.run(second.id)};check(workflows.queue.data().jobs.last().status==WorkflowStatus.CANCELLED)
        pptxSmokeStage="recipe-manual-save"
        main{workflows.select(workflows.data.recipes.single());workflows.change(workflows.draft!!.copy(tree=null,treeIdentity=null,custom="M34-$nonce"));workflows.saveRecipe();workflows.select(workflows.queue.data().recipes.single())}
        val(manual,_)=capture();val manualBytes=workflows.queue.store.pdf(manual.id).readBytes();check(manual.tree==null);main{workflows.enable(manual.recipeId,true)}
        ui.click("Choose manual Save (may be cloud)");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};var backs=0
        ui.until{val picker=uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true;if(!picker)true else{if(backs++<8){ui.back();android.os.SystemClock.sleep(300)};false}}
        check(workflows.queue.data().jobs.last().status==WorkflowStatus.PAUSED)
        ui.click("Choose manual Save (may be cloud)");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        pptxDownloads(ui);ui.click("Save")
        ui.until{workflows.data.jobs.last().status in setOf(WorkflowStatus.SAVED,WorkflowStatus.FAILED,WorkflowStatus.UNCERTAIN)}
        val manuallySaved=workflows.data.jobs.last();check(manuallySaved.status==WorkflowStatus.SAVED)
        val manualDestination=Uri.parse(manuallySaved.destination);check(app.contentResolver.openInputStream(manualDestination)!!.use{it.readBytes()}.contentEquals(manualBytes));File(evidence,"recipe-manual.pptx").writeBytes(manualBytes);check(android.provider.DocumentsContract.deleteDocument(app.contentResolver,manualDestination))
        pptxSmokeStage="recipe-recovery";main{workflows.select(workflows.data.recipes.single())};grant();ui.click("Save recipe paused");ui.click("Use / edit");val(pending,_)=capture();val pendingBytes=workflows.queue.store.pdf(pending.id).readBytes();val target=workflows.queue.destination.create(pending.tree!!,pending.name,pending.preset.mime);app.contentResolver.openOutputStream(Uri.parse(target),"w")!!.use{it.write(pendingBytes)}
        main{workflows.queue.status(pending.id,WorkflowStatus.WRITING,target);workflows.close();activity.finish()};ui.until{workflows.queue.store.closed}
        val store=WorkflowStore(workflows.queue.store.root);val queue=WorkflowQueue(app,store)
        try{check(store.healthy&&store.data.recipes.all{!it.enabled});check(store.data.jobs.last().status==WorkflowStatus.UNCERTAIN);queue.active=true;runBlocking{queue.reconcile(pending.id)};check(queue.data().jobs.last().status==WorkflowStatus.SAVED);store.pdf(pending.id).writeBytes(pendingBytes);queue.status(pending.id,WorkflowStatus.WRITING,target);File(evidence,"first-pid.txt").writeText(android.os.Process.myPid().toString())}finally{runBlocking{queue.closeOwner()}}
        check(source.readBytes().contentEquals(original));File(evidence,"recipe-checks.json").writeText("{\"actualRecipeLocalTreeSave\":true,\"actualRecipeManualSaveAndCancel\":true,\"frozenModeSizeAndBytes\":true,\"sourceEditsDoNotRetarget\":true,\"duplicateCounterStable\":true,\"revocationStopsWrites\":true,\"format5\":true,\"recoveryPaused\":true,\"exactInterruptedWriteReconciled\":true}")
    }catch(t:Throwable){File(evidence,"recipe-failure-stage.txt").writeText(pptxSmokeStage+"\n"+ui.lastClick+"\n"+workflows.message);throw t}
    finally{repeat(6){if(uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true){ui.back();android.os.SystemClock.sleep(200)}};main{vm.close();workflows.close();activity.finish()};source.delete()}
}
internal fun Instrumentation.pptxRestartChecks() {
    val evidence=File(targetContext.getExternalFilesDir(null),"m34-evidence");val before=File(evidence,"first-pid.txt").readText().trim().toInt();check(before!=android.os.Process.myPid())
    val store=WorkflowStore(File(targetContext.noBackupFilesDir,"workflow-v1"));val queue=WorkflowQueue(targetContext,store)
    try{check(store.healthy&&store.data.recipes.all{!it.enabled});val job=store.data.jobs.last();check(job.preset==WorkflowPreset.PPTX&&job.status==WorkflowStatus.UNCERTAIN);queue.active=true;runBlocking{queue.reconcile(job.id)};check(queue.data().jobs.last().status==WorkflowStatus.SAVED);File(evidence,"restart.json").writeText("{\"differentProcess\":true,\"uncertainRestored\":true,\"digestReconciled\":true,\"noAutomaticRetry\":true}") }finally{runBlocking{queue.closeOwner()}}
}
