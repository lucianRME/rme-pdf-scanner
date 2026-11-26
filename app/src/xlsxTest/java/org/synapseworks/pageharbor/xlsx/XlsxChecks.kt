package org.synapseworks.pageharbor.xlsx

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.table.*
import org.synapseworks.pageharbor.workflow.*

internal var xlsxSmokeStage="start"
internal fun Instrumentation.xlsxGenerationChecks() {
    val app=targetContext;val evidence=File(app.getExternalFilesDir(null),"m33-evidence").apply{mkdirs()}
    val root=File(app.noBackupFilesDir,"m33-generation-${UUID.randomUUID()}").apply{mkdirs()}
    try {
        val records=org.json.JSONArray()
        for((name,book)in FrozenXlsxCorpus.books()) {
            xlsxSmokeStage="generation-$name";val file=File(root,"$name.xlsx");XlsxWriter.write(book,file);file.copyTo(File(evidence,file.name),true)
            records.put(org.json.JSONObject().put("case",name).put("bytes",file.length()).put("sourceTables",book.tables.size).put("sourceReceipts",book.expenses.size))
        }
        val e=FrozenXlsxCorpus.expenses();check(e.map{it.amount}==listOf("12.50","-2.50","5.25","",""));check(e.all{it.corrected.isEmpty()})
        val cancelled=File(root,"cancel.xlsx");var stopped=false;try{XlsxWriter.write(FrozenXlsxCorpus.books().getValue("tables"),cancelled,progress={error("Synthetic cancellation")})}catch(_:IllegalStateException){stopped=true};check(stopped&&!cancelled.exists())
        File(evidence,"generation.json").writeText(org.json.JSONObject().put("outputs",records).put("m21UncorrectedAmounts",org.json.JSONArray(e.map{it.amount})).put("manualCorrections",0).put("missingRemainsBlank",true).put("cancelCleanup",true).toString())
    }finally{root.deleteRecursively()}
}
internal fun Instrumentation.xlsxSaveChecks() {
    val app=targetContext;val inputs=if(app.packageName.endsWith(".m04r8smoke"))app else context;val ui=WorkflowSystemUi(this)
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val queueOwner=ViewModelProvider(activity)[WorkflowViewModel::class.java]
    val vm=ViewModelProvider(activity)[XlsxViewModel::class.java];val receipt=ViewModelProvider(activity)[ReceiptViewModel::class.java];val table=ViewModelProvider(activity)[TableViewModel::class.java]
    xlsxSmokeStage="load-independent-optical-source"
    val nonce=UUID.randomUUID().toString().take(8);val root=File(app.cacheDir,"shared-pdfs/m33-$nonce").apply{mkdirs()};val source=File(root,"source.png").apply{writeBytes(inputs.assets.open(if(app.packageName.endsWith(".m04r8smoke"))"m33/optical-original.png"else"m22/optical-original.png").use{it.readBytes()})};val original=source.readBytes()
    val evidence=File(app.getExternalFilesDir(null),"m33-evidence").apply{mkdirs()};var target:Uri?=null
    fun main(block:()->Unit)=runOnMainSync(block)
    fun idle(){ui.until{!vm.busy}}
    fun openTable() {
        main{vm.close();receipt.close();table.close();table.setActive(true);table.open()}
        main{val s=table.state!!;check(table.beginPicker(s.owner,s.epoch));table.picked(FileProvider.getUriForFile(app,app.packageName+".fileprovider",source))}
        ui.until{table.state?.phase==TablePhase.EDIT};check(table.workbookInput.exportable&&table.workbookInput.guard!=null)
        val identity=table.workbookInput;main{table.open();table.manual()};check(table.workbookInput==identity)
        main{val s=table.state!!;table.review(s.owner,s.epoch);val r=table.state!!;table.confirm(r.owner,r.epoch,r.review!!.id,true);vm.foreground(true);vm.table(table.state!!,table.workbookInput);vm.rename("M33-$nonce.xlsx");vm.confirmTable(0,true);vm.review(true);vm.generate()}
        ui.until{vm.prepared!=null&&!vm.busy}
    }
    try {
        xlsxSmokeStage="navigation"
        main{vm.close();receipt.close();table.close()};ui.click("More");ui.click("Extract receipt");ui.until{receipt.state!=null}
        main{receipt.manual();val s=receipt.state!!;receipt.edit(s.owner,s.epoch,ReceiptField.MERCHANT,"Synthetic shop");receipt.edit(s.owner,s.epoch,ReceiptField.CURRENCY,"EUR");receipt.edit(s.owner,s.epoch,ReceiptField.TOTAL,"12.50");receipt.review(s.owner,s.epoch)}
        ui.click("Export reviewed workbook");ui.until{vm.visible&&vm.book.expenses.size==1}
        main{vm.confirmExpense(0,true);vm.review(true)};ui.click("Generate reviewed XLSX");ui.until{vm.prepared!=null&&!vm.busy};check(vm.prepared!!.book.expenses.single().amount=="12.50")
        xlsxSmokeStage="source-capture";openTable();val prepared=vm.prepared!!;val bytes=prepared.file.readBytes();val captured=prepared.book.tables.single()
        File(evidence,"m22-automatic.json").writeText(org.json.JSONObject().put("automaticGrid",org.json.JSONArray(table.state!!.extraction!!.grid.values().map{org.json.JSONArray(it)})).put("reviewedGrid",org.json.JSONArray(captured.grid.values().map{org.json.JSONArray(it)})).put("correctedCells",captured.grid.rows.flatten().count{it.corrected}).put("sourceIdentityVerified",true).toString())
        main{table.correct();val s=table.state!!;table.edit(s.owner,s.epoch,s.grid.rows.first().first().id,"Later change")};check(vm.prepared===prepared&&vm.prepared!!.file.readBytes().contentEquals(bytes))
        xlsxSmokeStage="cancel-picker"
        ui.click("Save separate XLSX");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};var backs=0
        ui.until{val p=uiAutomation.rootInActiveWindow?.packageName?.toString();if(!vm.picker&&p==app.packageName)true else{if(vm.picker&&p?.contains("documentsui")==true&&backs++<8){ui.back();SystemClock.sleep(400)};false}};check(vm.prepared===prepared)
        xlsxSmokeStage="save-picker"
        ui.click("Save separate XLSX");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        ui.until{ui.node{it.text?.toString()=="Downloads"}!=null};val b=android.graphics.Rect();ui.node{it.text?.toString()=="Downloads"}!!.getBoundsInScreen(b);ui.shell("input tap ${b.centerX()} ${b.centerY()}");ui.click("Save");ui.until{!vm.busy&&!vm.picker}
        check(vm.message?.startsWith("Separate XLSX saved")==true);val saved=checkNotNull(vm.savedDestination);check(app.contentResolver.openInputStream(saved)!!.use{it.readBytes()}.contentEquals(bytes));File(evidence,"actual-save.xlsx").writeBytes(bytes);check(android.provider.DocumentsContract.deleteDocument(app.contentResolver,saved))
        xlsxSmokeStage="faults"
        fun destination():Uri {val u=WorkflowFaultProvider.reset(app,vm.name);WorkflowFaultProvider.mime=XLSX_MIME;return u}
        target=destination();main{check(vm.beginSave()!=null);vm.destination(null)};check(WorkflowFaultProvider.opened==0)
        target=destination();main{vm.destination(target)};check(WorkflowFaultProvider.opened==0)
        target=destination();WorkflowFaultProvider.redirect=source;source.writeBytes(byteArrayOf());main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(source.length()==0L&&vm.message?.contains("failed")==true);source.writeBytes(original)
        target=destination();WorkflowFaultProvider.failOpen=true;main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(vm.message?.contains("failed")==true)
        target=destination();WorkflowFaultProvider.readOnlyWrite=true;main{check(vm.beginSave()!=null);vm.destination(target)};idle();check(WorkflowFaultProvider.bytes(app).isEmpty())
        target=destination();WorkflowFaultProvider.entered=java.util.concurrent.CountDownLatch(1);WorkflowFaultProvider.release=java.util.concurrent.CountDownLatch(1)
        main{check(vm.beginSave()!=null);vm.destination(target)};check(WorkflowFaultProvider.entered!!.await(10,java.util.concurrent.TimeUnit.SECONDS));main{vm.close()};WorkflowFaultProvider.release!!.countDown();ui.until{!vm.visible};SystemClock.sleep(200);check(WorkflowFaultProvider.bytes(app).isEmpty())
        xlsxSmokeStage="share";openTable();var shared:Intent?=null;main{vm.share{shared=it}};ui.until{shared!=null&&!vm.busy};val sent=shared!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!;check(sent.type==XLSX_MIME);val uri=sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!;check(app.contentResolver.openInputStream(uri)!!.use{it.readBytes()}.contentEquals(vm.prepared!!.file.readBytes()))
        xlsxSmokeStage="stale-recreated";target=destination();main{check(vm.beginSave()!=null);vm.background(true)};ui.until{!vm.visible};openTable();val next=vm.prepared;main{check(vm.beginSave()==null);vm.destination(target)};idle();check(vm.prepared===next&&WorkflowFaultProvider.opened==0)
        main{check(vm.beginSave()!=null);vm.destination(null)};check(original.contentEquals(source.readBytes()))
        File(evidence,"save-checks.json").writeText("{\"actualReceiptNavigation\":true,\"m22SourceIdentity\":true,\"sourceEditsIsolated\":true,\"actualSaveCancellation\":true,\"savedBytesExact\":true,\"sourceAliases\":true,\"failedWrites\":true,\"lockBeforeFirstChunk\":true,\"staleCallback\":true,\"recreationDiscards\":true,\"shareGrantBytes\":true,\"originalBytesExact\":true}")
        xlsxSmokeStage="complete"
    }catch(t:Throwable){File(evidence,"failure-stage.txt").writeText(xlsxSmokeStage+"\n"+ui.lastClick);val bitmap=uiAutomation.takeScreenshot();try{File(evidence,"failure.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()};throw t}
    finally{WorkflowFaultProvider.release?.countDown();WorkflowFaultProvider.reset(app,"synthetic.pdf");main{vm.close();receipt.close();table.close();activity.finish()};root.deleteRecursively()}
    waitForIdleSync();ui.until{queueOwner.queue.store.closed}
}
