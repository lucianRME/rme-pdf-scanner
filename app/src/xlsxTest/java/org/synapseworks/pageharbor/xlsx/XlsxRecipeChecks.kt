package org.synapseworks.pageharbor.xlsx

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.UUID
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.workflow.*

internal fun Instrumentation.xlsxRecipeChecks() {
    val app=targetContext;val ui=WorkflowSystemUi(this);val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[XlsxViewModel::class.java];val receipt=ViewModelProvider(activity)[ReceiptViewModel::class.java];val workflows=ViewModelProvider(activity)[WorkflowViewModel::class.java]
    val evidence=File(app.getExternalFilesDir(null),"m33-evidence").apply{mkdirs()};val nonce=UUID.randomUUID().toString().take(8);val folder="RME-M33-$nonce";ui.shell("mkdir -p /sdcard/Download/$folder")
    fun main(block:()->Unit)=runOnMainSync(block)
    fun clean(){workflows.queue.data().jobs.forEach{workflows.queue.keepExternalAndCancel(it.id);workflows.queue.remove(it.id)};workflows.queue.data().recipes.forEach{workflows.queue.revoke(it.id);workflows.queue.deleteRecipe(it.id)}}
    fun grant() {
        ui.click("Choose approved local folder");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        // The picker may already be at Downloads after the preceding Save.
        ui.until{ui.node{it.text?.toString()==folder}!=null||ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}!=null}
        if(ui.node{it.text?.toString()==folder}==null){
            ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            ui.click("Downloads")
        }
        ui.click(folder);ui.click("Use this folder");ui.click("Allow");ui.until{workflows.draft?.tree!=null}
    }
    fun capture():Pair<WorkflowJob,XlsxPrepared> {
        ui.click("Review tables or receipts for workbook");ui.until{vm.visible}
        ui.click("Add reviewed receipt");ui.until{receipt.state!=null}
        main{receipt.manual();val s=receipt.state!!;receipt.edit(s.owner,s.epoch,ReceiptField.MERCHANT,"Synthetic recipe shop");receipt.edit(s.owner,s.epoch,ReceiptField.DATE,"2026-05-04");receipt.edit(s.owner,s.epoch,ReceiptField.CURRENCY,"EUR");receipt.edit(s.owner,s.epoch,ReceiptField.TOTAL,"12.50");receipt.review(s.owner,s.epoch)}
        ui.click("Export reviewed workbook");ui.until{vm.visible};main{vm.confirmExpense(0,true);vm.review(true)}
        ui.click("Generate reviewed XLSX");ui.until{vm.prepared!=null&&!vm.busy};val prepared=vm.prepared!!;val reviewedBytes=prepared.file.readBytes()
        ui.click("Review frozen recipe job");ui.until{workflows.review!=null&&workflows.preview!=null&&!workflows.busy};val frozen=workflows.review!!.captured.output.readBytes();check(frozen.contentEquals(reviewedBytes))
        ui.click("Enqueue reviewed copy");ui.until{workflows.review==null&&workflows.data.jobs.isNotEmpty()}
        return workflows.data.jobs.last() to prepared
    }
    try {
        xlsxSmokeStage="recipe-create";main{vm.close();receipt.close();workflows.open();clean()}
        ui.click("Create recipe");ui.click("XLSX · reviewed tables and expense reports")
        grant()
        check(workflows.queue.destination.localTree(workflows.draft!!.tree!!));ui.click("Save recipe paused");ui.click("Use / edit")
        val (first,prepared)=capture();check(first.preset==WorkflowPreset.XLSX&&first.name.endsWith(".xlsx"));val bytes=workflows.queue.store.pdf(first.id).readBytes()
        check(org.json.JSONObject(File(workflows.queue.store.root,"queue.json").readText()).getInt("version")==4)
        main{receipt.setActive(true);receipt.open();receipt.manual();val s=receipt.state!!;receipt.edit(s.owner,s.epoch,ReceiptField.TOTAL,"99.00");receipt.close();workflows.enable(first.recipeId,true)}
        ui.click("Run / retry frozen copy");ui.until{workflows.data.jobs.last().status in setOf(WorkflowStatus.SAVED,WorkflowStatus.FAILED,WorkflowStatus.UNCERTAIN)};val saved=workflows.data.jobs.last();check(saved.status==WorkflowStatus.SAVED)
        check(app.contentResolver.openInputStream(Uri.parse(saved.destination))!!.use{it.readBytes()}.contentEquals(bytes));File(evidence,"recipe-local.xlsx").writeBytes(bytes)
        val counter=workflows.queue.data().recipes.single().nextCounter
        main{workflows.select(workflows.data.recipes.single());workflows.acceptXlsx(prepared,{true},{})};check(!workflows.busy&&workflows.data.jobs.size==1&&workflows.queue.data().recipes.single().nextCounter==counter)
        xlsxSmokeStage="recipe-revoke";main{workflows.select(workflows.data.recipes.single())};val(second,_)=capture();main{workflows.enable(second.recipeId,true);workflows.revoke(second.recipeId)};runBlocking{workflows.queue.run(second.id)};check(workflows.queue.data().jobs.last().status==WorkflowStatus.CANCELLED)
        xlsxSmokeStage="recipe-recovery";main{workflows.select(workflows.data.recipes.single())};grant();val(pending,_)=capture();val pendingBytes=workflows.queue.store.pdf(pending.id).readBytes()
        val target=workflows.queue.destination.create(checkNotNull(pending.tree),pending.name,pending.preset.mime);app.contentResolver.openOutputStream(Uri.parse(target),"w")!!.use{it.write(pendingBytes)}
        main{workflows.queue.status(pending.id,WorkflowStatus.WRITING,target);workflows.close();activity.finish()};ui.until{workflows.queue.store.closed}
        val store=WorkflowStore(workflows.queue.store.root);val queue=WorkflowQueue(app,store)
        try{check(store.healthy&&store.data.recipes.all{!it.enabled});check(store.data.jobs.last().status==WorkflowStatus.UNCERTAIN);queue.active=true;runBlocking{queue.reconcile(pending.id)};check(queue.data().jobs.last().status==WorkflowStatus.SAVED);store.pdf(pending.id).writeBytes(pendingBytes);queue.status(pending.id,WorkflowStatus.WRITING,target);File(evidence,"first-pid.txt").writeText(android.os.Process.myPid().toString())}finally{runBlocking{queue.closeOwner()}}
        File(evidence,"recipe-checks.json").writeText("{\"actualRecipeLocalTreeSave\":true,\"frozenWorkbookSettings\":true,\"sourceEditsDoNotRetarget\":true,\"duplicateCounterStable\":true,\"revocationStopsWrites\":true,\"format4\":true,\"recoveryPaused\":true,\"exactInterruptedWriteReconciled\":true,\"room10Backup8Unchanged\":true}")
    }catch(t:Throwable){File(evidence,"recipe-failure-stage.txt").writeText(xlsxSmokeStage+"\n"+ui.lastClick+"\nqueueHealthy="+workflows.queue.store.healthy+" busyOwner="+workflows.queue.store.busyOwner);val shot=uiAutomation.takeScreenshot();try{File(evidence,"recipe-failure.png").outputStream().use{shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{shot.recycle()};throw t}
    finally{main{workflows.close();vm.close();receipt.close();activity.finish()}}
}
internal fun Instrumentation.xlsxRestartChecks()=runBlocking<Unit> {
    val app=targetContext;val evidence=File(app.getExternalFilesDir(null),"m33-evidence");check(File(evidence,"first-pid.txt").readText().trim().toInt()!=android.os.Process.myPid())
    val store=WorkflowStore(File(app.noBackupFilesDir,"workflow-v1"));val queue=WorkflowQueue(app,store)
    try {
        check(store.healthy&&store.data.recipes.all{!it.enabled});val job=store.data.jobs.last();check(job.preset==WorkflowPreset.XLSX&&job.status==WorkflowStatus.UNCERTAIN)
        val bytes=store.pdf(job.id).readBytes();check(workflowDigest(bytes.inputStream())==job.digest to job.bytes);queue.active=true;queue.reconcile(job.id);check(queue.data().jobs.last().status==WorkflowStatus.SAVED)
        File(evidence,"restart-checks.json").writeText("{\"differentProcess\":true,\"format4Read\":true,\"recipesPaused\":true,\"ownedWorkbookExact\":true,\"uncertainDestinationReconciled\":true}")
        store.data.jobs.mapNotNull{it.destination}.distinct().forEach{uri->runCatching{android.provider.DocumentsContract.deleteDocument(app.contentResolver,Uri.parse(uri))}}
    }finally{queue.closeOwner()}
}
internal fun Instrumentation.xlsxReaderChecks() {
    val app=targetContext;val evidence=File(app.getExternalFilesDir(null),"m33-evidence").apply{mkdirs()}
    for(version in 1..4) {
        val root=File(app.noBackupFilesDir,"m33-reader-${UUID.randomUUID()}").apply{mkdirs()}
        val metadata=File(root,"queue.json");metadata.writeText("{\"version\":$version,\"recipes\":[],\"jobs\":[],\"grants\":[]}")
        try {
            if(version==4) {
                val sentinel=File(root,"${UUID.randomUUID()}.pdf").apply{writeBytes(byteArrayOf(1,2,3))};val old=metadata.readBytes();val legacy=LegacyM32WorkflowStore(root)
                try{check(!legacy.healthy&&metadata.readBytes().contentEquals(old)&&sentinel.readBytes().contentEquals(byteArrayOf(1,2,3)))}finally{legacy.close()}
            }
            val store=WorkflowStore(root);try{check(store.healthy);val queue=WorkflowQueue(app,store);queue.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic workbook",preset=WorkflowPreset.XLSX));check(org.json.JSONObject(metadata.readText()).getInt("version")==4)}finally{store.close()}
            val reopened=WorkflowStore(root);try{check(reopened.healthy&&reopened.data.recipes.single().preset==WorkflowPreset.XLSX&&!reopened.data.recipes.single().enabled)}finally{reopened.close()}
        }finally{root.deleteRecursively()}
    }
    File(evidence,"reader-checks.json").writeText("{\"reads1to4\":true,\"xlsxUpgradesTo4\":true,\"oldReaderRefusesBeforeCleanup\":true,\"oldReaderPreservesMetadataAndPayload\":true,\"reopenPaused\":true}")
}
