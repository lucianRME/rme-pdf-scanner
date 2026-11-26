package org.synapseworks.pageharbor.workflow

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.synapseworks.pageharbor.office.*

/** Synthetic app only. Real OpenDocument, reviewed conversion, queue and Android Save.
 * Automatic engine pixels remain covered by the separately frozen consumer corpus.
 */
internal fun Instrumentation.officeRecipeChecks(sourceName:String):JSONObject {
    val app=targetContext
    check(app.packageName.endsWith(".phase2test") || app.packageName.endsWith(".m04r8smoke"))
    check(org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED)
    val ui=WorkflowSystemUi(this);val nonce=UUID.randomUUID().toString().take(8)
    check(sourceName.matches(Regex("M35-source-[a-f0-9]{8}\\.docx"))) { "HOST_PREPARED_SYNTHETIC_SOURCE_REQUIRED" }
    val original=app.createPackageContext(app.packageName+".test",0).assets.open("m35/docx-columns.docx").use{it.readBytes()}
    val evidence=File(app.getExternalFilesDir(null),"m35-recipe-$nonce").apply{mkdirs()}
    val report=JSONObject()
    val activity=startOfficeRecipeMain(evidence,report)
    val vm=ViewModelProvider(activity)[WorkflowViewModel::class.java]
    var recipeId:String?=null;var destination:String?=null;var guard:WorkflowSourceGuard?=null
    val openObserver=object:Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
            if(intent.action==Intent.ACTION_OPEN_DOCUMENT) {
                report.put("sourcePickerActionObserved",true)
                report.put("sourcePickerDocxMime",intent.type==OFFICE_MIME.getValue("docx") || intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()==listOf(OFFICE_MIME.getValue("docx")))
                report.put("sourcePickerInitialPrimaryDownload",intent.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI)?.let { runCatching{android.provider.DocumentsContract.getDocumentId(it)=="primary:Download"}.getOrDefault(false) }==true)
            }
            return null // observe, never substitute a synthetic result for the real picker
        }
    }
    fun onMain(block:()->Unit)=runOnMainSync(block)
    try {
        ui.click("Recipes");ui.click("Create recipe")
        ui.click(WorkflowPreset.OFFICE.label)
        onMain{vm.change(checkNotNull(vm.draft).copy(custom="M35-recipe-$nonce"))}
        ui.click("Save recipe paused")
        recipeId=vm.data.recipes.last().id
        onMain { vm.select(vm.data.recipes.first{it.id==recipeId}) }
        ui.click("Open Office source for review")
        ui.until{ui.node{it.viewIdResourceName=="office-open" && it.isEnabled}!=null}
        addMonitor(openObserver)
        try {
            ui.click("Open DOCX")
            ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
            runCatching{uiAutomation.waitForIdle(300,5000)}
            fun sourceNode()=ui.node{it.viewIdResourceName?.endsWith("/title")==true && it.text?.toString() in setOf(sourceName,sourceName.removeSuffix(".docx"))}
            report.put("initialSourceVisibleAfterIdle",sourceNode()!=null)
            if(sourceNode()==null) {
                // A retained stock drawer can cover the search toolbar. Select
                // the visible local root and await the real drawer transition.
                ui.until {
                    if(ui.node{it.viewIdResourceName?.endsWith("/roots_list")==true && it.isVisibleToUser}==null)true
                    else {
                        var root=ui.node{it.text?.toString()=="Downloads" && it.isVisibleToUser && it.isEnabled}
                        while(root!=null && !root.isClickable)root=root.parent
                        root?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);false
                    }
                }
            }
            if(sourceNode()==null) {
                report.put("stockSourceSearch",true)
                ui.until {
                    if(ui.node{it.isEditable && it.isVisibleToUser}!=null)true
                    else {
                        ui.node{it.isVisibleToUser && it.viewIdResourceName?.substringAfterLast('/') in setOf("menu_search","option_menu_search")}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);false
                    }
                }
                var search:android.view.accessibility.AccessibilityNodeInfo?=null
                ui.until{search=ui.node{it.isEditable};search!=null}
                check(search!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,sourceName)}))
                ui.shell("input keyevent 66")
            }
            val method=android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("dumpsys input_method")).use { input->
                val bytes=ByteArray(65536);var used=0
                while(used<bytes.size){val n=input.read(bytes,used,bytes.size-used);if(n<0)break;used+=n}
                String(bytes,0,used,Charsets.UTF_8)
            }
            val keyboardShown=method.contains("mInputShown=true")
            report.put("keyboardShownBeforeFileSelection",keyboardShown)
            if(keyboardShown){ui.back();runCatching{uiAutomation.waitForIdle(200,3000)}}
            var selected=false
            ui.until {
                if(uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName && ui.node{it.viewIdResourceName=="office-open"}==null)true
                else {
                    val item=sourceNode();report.put("sourceFileTitleFound",item!=null)
                    if(!selected && item?.refresh()==true && item.isVisibleToUser && item.isEnabled) {
                        val rect=android.graphics.Rect();item.getBoundsInScreen(rect)
                        check(rect.left>=0 && rect.top>=0 && rect.width()>0 && rect.height()>0)
                        report.put("observedFileTitleTouch",true)
                        ui.shell("input tap ${rect.centerX()} ${rect.centerY()}")
                        selected=true
                    }
                    false
                }
            }
            // Source review controls can be below the current accessibility viewport.
        } finally {removeMonitor(openObserver)}
        // Fixed synthetic control labels, no document-dependent text matching.
        fun tag(id:String) {
            ui.until {
                var node=ui.node{it.viewIdResourceName==id}
                while(node!=null && !node.isClickable)node=node.parent
                if(node?.isVisibleToUser==true && node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))true
                else { ui.node{it.isScrollable}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);false }
            }
        }
        tag("office-accept");tag("office-convert")
        ui.until{ui.node{it.viewIdResourceName=="office-preview"}!=null}
        tag("office-reviewed");tag("office-recipe")
        ui.until{vm.review!=null && vm.preview!=null && !vm.busy}
        val review=checkNotNull(vm.review);guard=review.captured.sourceGuards.single()
        check(review.recipe.preset==WorkflowPreset.OFFICE && review.pages==1)
        check(android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(guard!!.uri))=="primary:Download/$sourceName")
        check(vm.queue.destination.sourceGranted(guard!!))
        check(app.contentResolver.openInputStream(android.net.Uri.parse(guard!!.uri))!!.use{it.readBytes()}.contentEquals(original))
        val frozen=review.captured.output.readBytes();val digest=workflowDigest(frozen.inputStream())
        File(evidence,"reviewed.pdf").writeBytes(frozen)
        ui.click("Enqueue reviewed copy")
        ui.until{vm.review==null && vm.data.jobs.any{it.recipeId==recipeId}}
        val job=vm.data.jobs.last{it.recipeId==recipeId};check(job.counter==1)
        check(vm.queue.store.pdf(job.id).readBytes().contentEquals(frozen))
        report.put("actualOpenReviewHandoff",true).put("outputSha256",digest.first).put("sourceSha256",workflowDigest(original.inputStream()).first)
        // A repeated reviewed source does not consume another counter or retarget a job.
        onMain { vm.select(vm.data.recipes.first{it.id==recipeId}) }
        var ticket:OfficeRecipeTicket?=null;onMain{ticket=vm.beginOffice()}
        val offer=OfficeSource.open(app,android.net.Uri.parse(guard!!.uri),"docx",{}).use { source->
            OfficeRecipeOffers.create(app,ticket!!.request,source,OfficeOutput(File(evidence,"reviewed.pdf"),digest.first,1,UUID.randomUUID().toString())){}
        }
        check(OfficeRecipeOffers.claim(offer.id,UUID.randomUUID().toString())==null)
        onMain{vm.acceptOffice(ticket!!,offer.id){true}}
        check(vm.data.jobs.count{it.recipeId==recipeId}==1 && vm.data.recipes.first{it.id==recipeId}.nextCounter==2)
        check(!offer.file.exists())
        report.put("duplicateBeforeCorrectionCounterOnce",true).put("foreignRequestRefused",true)
        onMain{vm.enable(recipeId!!,true)}
        ui.click("Choose manual Save (may be cloud)")
        ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        repeat(6){if(uiAutomation.rootInActiveWindow?.packageName?.toString()!=app.packageName){ui.back();SystemClock.sleep(250)}}
        ui.until{vm.data.jobs.first{it.id==job.id}.status==WorkflowStatus.PAUSED}
        check(vm.queue.data().recipes.first{it.id==recipeId}.nextCounter==2)
        ui.click("Choose manual Save (may be cloud)")
        ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ui.node{it.contentDescription?.toString() in listOf("Show roots","Show navigation drawer")}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        ui.click("Downloads");ui.click("Save")
        ui.until{vm.data.jobs.first{it.id==job.id}.status in setOf(WorkflowStatus.SAVED,WorkflowStatus.UNCERTAIN)}
        val saved=vm.data.jobs.first{it.id==job.id};check(saved.status==WorkflowStatus.SAVED)
        destination=checkNotNull(saved.destination)
        val actual=app.contentResolver.openInputStream(android.net.Uri.parse(destination))!!.use{it.readBytes()}
        check(actual.contentEquals(frozen));File(evidence,"saved.pdf").writeBytes(actual)
        check(app.contentResolver.openInputStream(android.net.Uri.parse(guard!!.uri))!!.use{it.readBytes()}.contentEquals(original))
        report.put("actualSaveCancelRetry",true).put("sourceUnchanged",true).put("savedBytesExact",true)
        // Real source inode and URI aliases remain excluded even though the queued PDF is independent.
        app.contentResolver.openFileDescriptor(android.net.Uri.parse(guard!!.uri),"r")!!.use { fd->
            check(!workflowDifferentSources(app,fd,android.net.Uri.parse(guard!!.uri),listOf(guard!!)))
        }
        report.put("sourceAliasRefused",true)
        // Restart reader pauses authority and preserves exact pending content/settings.
        val testRoot=File(app.noBackupFilesDir,"office-queue-${UUID.randomUUID()}")
        var store=WorkflowStore(testRoot)
        try {
            val r=saved.let{vm.data.recipes.first{r->r.id==it.recipeId}}
            val pending=saved.copy(status=WorkflowStatus.PAUSED,destination=null)
            store.pdf(pending.id).writeBytes(frozen);store.commit(WorkflowData(listOf(r),listOf(pending)))
            store.close();store=WorkflowStore(testRoot)
            check(store.healthy && !store.data.recipes.single().enabled && store.pdf(pending.id).readBytes().contentEquals(frozen))
            val queue=WorkflowQueue(app,store);queue.active=true;queue.enable(r.id,true)
            app.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(guard!!.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runBlocking{queue.run(pending.id)}
            check(queue.data().jobs.single().status==WorkflowStatus.FAILED && queue.data().jobs.single().destination==null)
            check(store.pdf(pending.id).readBytes().contentEquals(frozen))
            report.put("restartImmutablePaused",true).put("revokedSourcePreventsCreation",true)
        } finally {store.close();testRoot.deleteRecursively()}
        File(evidence,"checks.json").writeText(report.toString(2))
        return report
    } catch(failure:Throwable) {
        report.put("activePackage",uiAutomation.rootInActiveWindow?.packageName?.toString())
        val sourceMessage=ui.node{it.viewIdResourceName=="office-message"}?.text?.toString().orEmpty()
        report.put("sourceDeclineCode",Regex("Source declined: ([A-Z_]{1,48})").find(sourceMessage)?.groupValues?.get(1))
        report.put("expectedSourcePresent",ui.node{it.text?.toString() in setOf(sourceName,sourceName.removeSuffix(".docx"))}!=null)
        report.put("downloadFolderPresent",ui.node{it.text?.toString()=="Download"}!=null)
        report.put("stockLocations",org.json.JSONArray(listOf("Recent","Downloads","Download","Internal storage",android.os.Build.MODEL,"No items","No files").filter { label->ui.node{it.text?.toString()==label}!=null }))
        File(evidence,"failure.json").writeText(report.put("stage",workflowSmokeStage).put("queueHealthy",vm.queue.store.healthy).put("reviewPresent",vm.review!=null).put("previewPresent",vm.preview!=null).put("failureClass",failure.javaClass.simpleName).toString())
        throw failure
    } finally {
        onMain {
            vm.queue.data().jobs.filter{it.recipeId==recipeId}.forEach{vm.queue.keepExternalAndCancel(it.id);vm.queue.remove(it.id)}
            recipeId?.let{vm.queue.revoke(it);vm.queue.deleteRecipe(it)}
            vm.close();activity.finish()
        }
        destination?.let{runCatching{android.provider.DocumentsContract.deleteDocument(app.contentResolver,android.net.Uri.parse(it))}}
        guard?.let{runCatching{app.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(it.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
        // The host owns the prepared public fixture and removes it after this run.
    }
}
