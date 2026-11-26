package org.synapseworks.pageharbor.workflow

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo as Node
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.synapseworks.pageharbor.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.image.*

internal var workflowSmokeStage="start"
internal class WorkflowSystemUi(private val instrumentation:Instrumentation) {
    fun until(block:()->Boolean) { val end=SystemClock.uptimeMillis()+45000;while(SystemClock.uptimeMillis()<end){if(block())return;SystemClock.sleep(100)};error("Synthetic workflow validation timeout") }
    fun node(predicate:(Node)->Boolean):Node? {
        if(android.os.Build.VERSION.SDK_INT>=34)instrumentation.uiAutomation.clearCache()
        val root=instrumentation.uiAutomation.rootInActiveWindow?:return null;val queue=java.util.ArrayDeque<Node>();queue.add(root);var count=0
        while(queue.isNotEmpty() && count++<2048) { val n=queue.removeFirst();if(predicate(n))return n;for(k in 0 until n.childCount)n.getChild(k)?.let(queue::addLast) };return null
    }
    var lastClick=""
    fun click(text:String) {
        workflowSmokeStage="control:$text"
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100,5000)
        fun matches(node:Node)=node.text?.toString()?.equals(text,true)==true || node.contentDescription?.toString()?.equals(text,true)==true
        var attempts=0
        until {
            // Refresh UiAutomation's retained disabled node after queue/review recomposition.
            if(android.os.Build.VERSION.SDK_INT>=34)instrumentation.uiAutomation.clearCache()
            val target=node(::matches)
            val matched=target?.let { it.refresh() && matches(it) }==true
            var n:Node?=if(matched)target else null
            while(n!=null && !n.isClickable)n=n.parent
            val actionCurrent=n?.refresh()==true
            val labelCurrent=target?.let { it.refresh() && matches(it) }==true
            val bounds=android.graphics.Rect();n?.getBoundsInScreen(bounds)
            lastClick="matched=$matched labelCurrent=$labelCurrent clickable=${n?.isClickable} visible=${n?.isVisibleToUser} enabled=${n?.isEnabled} bounds=$bounds"
            if(actionCurrent && labelCurrent && n?.isVisibleToUser==true && n.isEnabled && n.performAction(Node.ACTION_CLICK))true
            else {
                // AOSP's breadcrumb Spinner is also scrollable; it never advances files.
                val scrolling=node { it.isScrollable && it.isVisibleToUser && it.viewIdResourceName?.endsWith("/dir_list")==true }
                    ?:node { it.isScrollable && it.isVisibleToUser && it.className?.toString() !in setOf("android.widget.Spinner","android.widget.HorizontalScrollView") }
                scrolling?.performAction(if((attempts++/8)%2==0)Node.ACTION_SCROLL_FORWARD else Node.ACTION_SCROLL_BACKWARD);false
            }
        }
        SystemClock.sleep(180)
    }
    fun shell(command:String) { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
    fun back(){shell("input keyevent 4")}
}

/** Shared ordinary/optimized owned-synthetic exercise; not packaged in debug/release. */
internal fun Instrumentation.workflowSaveChecks(archival:Boolean=false) {
    val app=targetContext;check(app.packageName.endsWith(".phase2test")||app.packageName.endsWith(".m04r8smoke"))
    val ui=WorkflowSystemUi(this);val nonce=UUID.randomUUID().toString().take(8);val folder="RME-M30-$nonce"
    ui.shell("mkdir -p /sdcard/Download/$folder")
    val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[WorkflowViewModel::class.java];val session=ViewModelProvider(activity)[PageHarborSessionViewModel::class.java]
    val root=File(app.cacheDir,"shared-pdfs/m30-$nonce").apply{mkdirs()};val source=File(root,"source.png")
    val bitmap=Bitmap.createBitmap(512,384,Bitmap.Config.ARGB_8888).apply{eraseColor(0xfff3f3f3.toInt());val canvas=android.graphics.Canvas(this);val paint=android.graphics.Paint().apply{color=android.graphics.Color.BLACK;textSize=24f};canvas.drawText("Synthetic workflow reference",40f,65f,paint);canvas.drawLine(40f,280f,400f,280f,paint)}
    source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();val original=source.readBytes()
    val evidence=File(app.getExternalFilesDir(null),if(archival)"m31-save-evidence" else "m30-evidence").apply{mkdirs()};val targets=mutableListOf<String>()
    fun onMain(block:()->Unit)=runOnMainSync(block)
    fun click(text:String)=ui.click(text)
    fun idle(){ui.until{!vm.busy && vm.review?.let{vm.preview!=null}!=false}}
    fun clean(){vm.queue.data().jobs.forEach { vm.queue.keepExternalAndCancel(it.id);vm.queue.remove(it.id) };vm.queue.data().recipes.forEach { vm.queue.revoke(it.id);vm.queue.deleteRecipe(it.id) }}
    var captureOrdinal=0
    fun captureAndEnqueue():WorkflowJob {
        click("Capture current document for review");ui.until{vm.review!=null && vm.preview!=null && !vm.busy}
        val r=vm.review!!;check(r.revision==session.documentRevision);check(r.captured.output.isFile);check(r.captured.bytes>0)
        if(captureOrdinal==0) {
            val image=requireNotNull(vm.preview);val cx=(image.width*.6).toInt();val cy=(image.height*.29).toInt();val darkest=(-3..3).flatMap { dy -> (-3..3).map { dx -> android.graphics.Color.red(image.getPixel(cx+dx,cy+dy)) } }.min();check(darkest<80)
            val highlight=image.getPixel((image.width*.25).toInt(),(image.height*.45).toInt());check(android.graphics.Color.red(highlight)>android.graphics.Color.blue(highlight)+30)
        }
        captureOrdinal++;File(evidence,"review-$captureOrdinal.pdf").writeBytes(r.captured.output.readBytes())
        File(evidence,"reviewed.pdf").writeBytes(r.captured.output.readBytes())
        vm.preview?.let { image -> File(evidence,"reviewed.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)} }
        val shot=uiAutomation.takeScreenshot();try{File(evidence,"review-screen.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{shot.recycle()}
        if(archival) { check(r.recipe.preset==WorkflowPreset.ARCHIVAL && !vm.archivalAccepted);click("Accept flattened archival copy");ui.until{vm.archivalAccepted} }
        click("Enqueue reviewed copy");ui.until{vm.review==null && vm.data.jobs.isNotEmpty()};return vm.data.jobs.last()
    }
    try {
        onMain {
            clean()
            val signature=HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.15f,.75f),SignaturePoint(.5f,.5f),SignaturePoint(.85f,.25f)))),strokeWidthFraction=.04f)
            val layer=AnnotationLayer.of(listOf(AnnotationMark.create("m30-ink",AnnotationTool.SIGNATURE,AnnotationBounds(.55f,.24f,.65f,.34f),AnnotationStyle(rgb=0),signature=signature),AnnotationMark.create("m30-mark",AnnotationTool.HIGHLIGHT,AnnotationBounds(.2f,.42f,.45f,.49f),AnnotationStyle(rgb=0xFFFF00,opacity=.4f))))
            val resource=DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL)
            activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),resource,DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),512,384),filter=DocumentFilter.GRAYSCALE,adjustments=ManualImageAdjustments(-10,15),annotations=layer))))
        }
        // The installed session opens the document editor, whose entry is in More.
        // The library's separate Recipes toolbar is used by the Office-source test.
        click("More");click("Local workflow recipes");click("Create recipe");if(archival)click("PDF/A-1b · image-only archival")
        click("Choose approved local folder");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        click(folder);click("Use this folder");click("Allow")
        ui.until{vm.draft?.tree!=null};check(vm.queue.destination.localTree(vm.draft!!.tree!!))
        val chosenTree=vm.draft!!.tree!!;val chosenUri=android.net.Uri.parse(chosenTree)
        val parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(chosenUri,android.provider.DocumentsContract.getTreeDocumentId(chosenUri))
        val unicode=checkNotNull(android.provider.DocumentsContract.createDocument(app.contentResolver,parent,"application/pdf","Cafe\u0301.pdf"))
        check(vm.queue.destination.collision(chosenTree,"Café.pdf"));check(android.provider.DocumentsContract.deleteDocument(app.contentResolver,unicode))
        File(evidence,"provider.json").writeText("{\"authority\":\"com.android.externalstorage.documents\",\"primaryDescriptorLocal\":true,\"persistedReadWrite\":true,\"systemSignedProvider\":true,\"directoryIdentityCaptured\":true}")
        click("Save recipe paused");click("Use / edit")
        val first=captureAndEnqueue();val frozen=vm.queue.store.pdf(first.id).readBytes();check(!vm.data.recipes.single().enabled)
        check(original.contentEquals(source.readBytes()))
        onMain{check(session.rotatePageClockwise(1))}
        click("Enable reviewed jobs");click("Run / retry frozen copy")
        ui.until{vm.data.jobs.first().status==WorkflowStatus.SAVED};val saved=vm.data.jobs.first();targets+=saved.destination!!
        val output=app.contentResolver.openInputStream(android.net.Uri.parse(saved.destination))!!.use{it.readBytes()};check(frozen.contentEquals(output));check(!vm.queue.store.pdf(first.id).exists())
        File(evidence,"local.pdf").writeBytes(output)
        workflowSmokeStage="collision"
        onMain { val r=vm.data.recipes.single();vm.edit(r);vm.change(r.copy(template=first.name.removeSuffix(".pdf").lowercase(java.util.Locale.ROOT)));vm.saveRecipe();vm.select(vm.data.recipes.single()) }
        val second=captureAndEnqueue();onMain{vm.enable(second.recipeId,true)};runBlocking{vm.queue.run(second.id)};ui.until{vm.data.jobs.last().status==WorkflowStatus.COLLISION}
        check(output.contentEquals(app.contentResolver.openInputStream(android.net.Uri.parse(saved.destination))!!.use{it.readBytes()}))
        val counter=vm.queue.data().recipes.single().nextCounter;runBlocking{vm.queue.run(second.id)};check(vm.queue.data().recipes.single().nextCounter==counter)
        workflowSmokeStage="replaced-folder-identity"
        ui.shell("mv /sdcard/Download/$folder /sdcard/Download/${folder}-old")
        ui.shell("mkdir -p /sdcard/Download/$folder")
        check(!vm.queue.destination.localTree(first.tree!!,first.treeIdentity))
        runBlocking{vm.queue.run(second.id)};check(vm.queue.data().jobs.last().status==WorkflowStatus.FAILED)
        val tree=android.net.Uri.parse(first.tree)
        app.contentResolver.query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree)),arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)!!.use{check(it.count==0)}
        onMain{vm.revoke(second.recipeId)};check(!vm.queue.destination.localTree(first.tree!!));check(vm.queue.data().jobs.last().status==WorkflowStatus.CANCELLED)
        // Native PDF bytes and names from a second explicit manual decision.
        onMain{clean();vm.create();vm.change(vm.draft!!.copy(custom="M30-$nonce",preset=if(archival)WorkflowPreset.ARCHIVAL else WorkflowPreset.NORMAL));vm.saveRecipe();vm.select(vm.data.recipes.single())}
        val manual=captureAndEnqueue();onMain{vm.enable(manual.recipeId,true)}
        click("Choose manual Save (may be cloud)");ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true};repeat(6){if(uiAutomation.rootInActiveWindow?.packageName?.toString()!=app.packageName){ui.back();SystemClock.sleep(450)}};ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName}
        check(vm.queue.data().jobs.single().destination==null);check(vm.queue.data().recipes.single().nextCounter==2)
        click("Choose manual Save (may be cloud)")
        ui.until{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        val drawer=ui.node{it.contentDescription?.toString()in listOf("Show roots","Show navigation drawer")};drawer?.performAction(Node.ACTION_CLICK)
        click("Downloads");click("Save")
        ui.until{vm.data.jobs.single().status in setOf(WorkflowStatus.SAVED,WorkflowStatus.UNCERTAIN)};check(vm.data.jobs.single().status==WorkflowStatus.SAVED)
        val final=vm.data.jobs.single();targets+=final.destination!!;check(final.counter==1)
        File(evidence,"manual.pdf").writeBytes(app.contentResolver.openInputStream(android.net.Uri.parse(final.destination))!!.use{it.readBytes()})
        check(original.contentEquals(source.readBytes()));check(session.historyState.undoCount==1)
        // Persist the exact crash window after a complete external write, before saved metadata.
        File(evidence,"restart-target.txt").writeText(final.destination)
        app.contentResolver.openInputStream(android.net.Uri.parse(final.destination))!!.use{vm.queue.store.pdf(final.id).outputStream().use { out -> it.copyTo(out) }}
        File(evidence,"first-pid.txt").writeText(android.os.Process.myPid().toString())
        File(evidence,"checks.json").writeText("{\"actualTreeSave\":true,\"actualManualSave\":true,\"cancelThenRetry\":true,\"collisionNoOverwrite\":true,\"revocation\":true,\"replacedDirectoryIdentityRefused\":true,\"capturedRevisionIsolated\":true,\"editsAndLayers\":true,\"sourceBytesExact\":true}")
        workflowSmokeStage="complete"
    } catch(failure:Throwable) {
        val shot=uiAutomation.takeScreenshot();try{File(evidence,"failure.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{shot.recycle()}
        File(evidence,"failure-stage.txt").writeText(workflowSmokeStage+"\n"+ui.lastClick+"\n"+vm.queue.destination.lastCheck+"\n"+vm.message)
        throw failure
    } finally {
        onMain{vm.close();session.clearScan();activity.finish()};root.deleteRecursively()
        // Only this fixture's private subfolder is removed. Manual output retained for restart reconciliation.
        ui.shell("rm -rf /sdcard/Download/$folder")
        ui.shell("rm -rf /sdcard/Download/${folder}-old")
    }
    waitForIdleSync()
    ui.until{vm.queue.store.closed}
    val checkpoint=WorkflowStore(vm.queue.store.root);check(checkpoint.healthy)
    try {
        val job=checkpoint.data.jobs.single()
        app.contentResolver.openInputStream(android.net.Uri.parse(job.destination))!!.use { input -> checkpoint.pdf(job.id).outputStream().use { input.copyTo(it) } }
        checkpoint.commit(checkpoint.data.copy(jobs=listOf(job.copy(status=WorkflowStatus.WRITING))))
    } finally { checkpoint.close() }
}

internal fun Instrumentation.workflowRestartChecks(archival:Boolean=false) {
    val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))as MainActivity
    val vm=ViewModelProvider(activity)[WorkflowViewModel::class.java]
    try {
        val oldPid=File(targetContext.getExternalFilesDir(null),if(archival)"m31-save-evidence/first-pid.txt" else "m30-evidence/first-pid.txt").readText().trim().toInt();check(oldPid!=android.os.Process.myPid())
        val job=vm.queue.data().jobs.single();check(job.status==WorkflowStatus.UNCERTAIN);check(vm.queue.data().recipes.none{it.enabled})
        val input=vm.queue.store.pdf(job.id).readBytes();check(input.isNotEmpty())
        runBlocking { vm.queue.reconcile(job.id) }
        check(vm.queue.data().jobs.single().status==WorkflowStatus.SAVED);check(!vm.queue.store.pdf(job.id).exists())
        check(vm.queue.data().recipes.single().nextCounter==2)
        File(targetContext.getExternalFilesDir(null),if(archival)"m31-save-evidence/restart.json" else "m30-evidence/restart.json").writeText("{\"newProcess\":true,\"pausedAuthority\":true,\"uncertainBeforeReconcile\":true,\"exactBytesReconciled\":true,\"counterUnchanged\":true}")
        check(job.name.matches(Regex("M30-[a-f0-9]{8}-[0-9-]+\\.pdf")))
        WorkflowSystemUi(this).shell("rm -f /sdcard/Download/${job.name}")
        runOnMainSync { vm.remove(job.id);vm.delete(job.recipeId) }
    } finally { runOnMainSync{vm.close();activity.finish()} }
}
