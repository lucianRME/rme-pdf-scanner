package org.synapseworks.pageharbor.workflow

import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.archival.PdfA1bGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter
import java.io.File
import java.util.UUID

/** Independent frozen raster inputs; shared by ordinary and optimized generator checks. */
internal fun Instrumentation.archivalGenerationChecks() = runBlocking {
    val app=targetContext;val fixtureContext=if(app.packageName.endsWith(".m04r8smoke"))app else context
    val root=File(app.cacheDir,"m31-generation-${UUID.randomUUID()}").apply{mkdirs()}
    val evidence=File(app.getExternalFilesDir(null),"m31-evidence").apply{mkdirs()}
    val prefix=if(app.packageName.endsWith(".m04r8smoke"))"" else "m31/"
    PDFBoxResourceLoader.init(app)
    try {
        val input=File(root,"rgb.png");input.writeBytes(fixtureContext.assets.open(prefix+"rgb.png").use{it.readBytes()});val original=input.readBytes()
        data class Case(val name:String,val source:String="rgb.png",val rotation:DocumentPageRotation=DocumentPageRotation.DEGREES_0,val paper:DocumentPaperSize?=null)
        val cases=listOf(Case("rgb-natural"),Case("gray-natural","gray.png"),Case("alpha-white","alpha.png"),Case("rotation-90",rotation=DocumentPageRotation.DEGREES_90),Case("rotation-180",rotation=DocumentPageRotation.DEGREES_180),Case("rotation-270",rotation=DocumentPageRotation.DEGREES_270),Case("a4",paper=DocumentPaperSize(595.2756f,841.8898f)),Case("letter",paper=DocumentPaperSize(612f,792f)),Case("landscape-a4",rotation=DocumentPageRotation.DEGREES_90,paper=DocumentPaperSize(595.2756f,841.8898f)))
        cases.forEach { c ->
            val source=File(root,c.source);if(!source.exists())source.writeBytes(fixtureContext.assets.open(prefix+c.source).use{it.readBytes()})
            val sourceBytes=source.readBytes()
            val p=NormalPdfPage(1,DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentFilter.ORIGINAL,c.rotation,"image/png",DocumentImageMetadata(source.length(),512,384),paperSize=c.paper)
            val visual=File(root,c.name+".jpg")
            NormalPdfVisualStreamProvider(app,root,flattenToSrgb=true).open(p).use { stream -> visual.outputStream().use { stream.copyTo(it) } }
            val out=File(root,c.name+".pdf");PdfA1bGenerator(app).generate(listOf(SearchablePdfPage({visual.inputStream()},paperSize=physicalPdfPageSize(c.paper,c.rotation))),out,root)
            check(sourceBytes.contentEquals(source.readBytes()))
            PDDocument.load(out).use { pdf -> check(!pdf.isEncrypted && pdf.numberOfPages==1 && PDFTextStripper().getText(pdf).isBlank());check(pdf.documentCatalog.outputIntents.size==1);check(pdf.getPage(0).annotations.isEmpty());check(pdf.documentCatalog.acroForm==null) }
            out.copyTo(File(evidence,c.name+".pdf"),overwrite=true)
            PdfRenderer(ParcelFileDescriptor.open(out,ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf -> pdf.openPage(0).use { page ->
                val bitmap=Bitmap.createBitmap(if(c.rotation.degrees%180==0)512 else 384,if(c.rotation.degrees%180==0)384 else 512,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                try { File(evidence,c.name+"-android.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally{bitmap.recycle()}
            } }
            out.delete();visual.delete()
        }
        val jpeg=File(root,"multi.jpg");val page=NormalPdfPage(1,DocumentResource(Uri.fromFile(input).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentFilter.ORIGINAL,DocumentPageRotation.DEGREES_0,"image/png",DocumentImageMetadata(input.length(),512,384))
        NormalPdfVisualStreamProvider(app,root,flattenToSrgb=true).open(page).use { stream -> jpeg.outputStream().use { stream.copyTo(it) } }
        val multi=File(root,"multi-page.pdf");PdfA1bGenerator(app).generate(listOf(SearchablePdfPage({jpeg.inputStream()}),SearchablePdfPage({jpeg.inputStream()},paperSize=org.synapseworks.pageharbor.document.searchablepdf.PdfPageSize(612f,792f))),multi,root)
        multi.copyTo(File(evidence,"multi-page.pdf"),overwrite=true);check(input.readBytes().contentEquals(original))
        // Only metadata/resource/bounds checks execute in-app; host veraPDF is the conformance gate.
        File(evidence,"generation-checks.json").writeText("{\"sourceBytesExact\":true,\"imageOnly\":true,\"cases\":10,\"androidValidator\":false}")
    } finally { root.deleteRecursively() }
}

/** Actual queue/descriptor boundary checks shared with the optimized artifact. */
internal fun Instrumentation.archivalFaultChecks() = runBlocking<kotlin.Unit> {
    val app=targetContext;val fixtureContext=if(app.packageName.endsWith(".m04r8smoke"))app else context
    val prefix=if(app.packageName.endsWith(".m04r8smoke"))"" else "m31/"
    val root=File(app.noBackupFilesDir,"m31-faults-${UUID.randomUUID()}").apply{mkdirs()}
    val source=File(root,"source.png");val original=fixtureContext.assets.open(prefix+"rgb.png").use{it.readBytes()};source.writeBytes(original)
    val queue=WorkflowQueue(app,WorkflowStore(File(root,"queue")));queue.active=true
    queue.saveRecipe(WorkflowRecipe(UUID.randomUUID().toString(),label="Synthetic archival faults",preset=WorkflowPreset.ARCHIVAL))
    suspend fun next():WorkflowJob {
        queue.data().jobs.forEach { queue.keepExternalAndCancel(it.id);queue.remove(it.id) };queue.active=true
        val recipe=queue.data().recipes.single()
        val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),512,384))
        val captured=captureWorkflow(app,queue.store.root,DocumentSession(listOf(page)),WorkflowPreset.ARCHIVAL,null,charArrayOf()){true}
        val now=java.time.Instant.parse("2026-11-08T12:00:00Z");val name=WorkflowNaming.name(recipe.template,recipe.custom,now,java.time.ZoneId.of("UTC"),recipe.nextCounter)
        val job=queue.enqueue(recipe,captured.output,captured.digest,captured.bytes,name,1,"synthetic",UUID.randomUUID().toString(),now.toString(),"UTC",captured.sourceGuards)
        captured.directory.deleteRecursively();queue.enable(recipe.id,true);return job
    }
    fun target(job:WorkflowJob):Uri { val uri=WorkflowFaultProvider.reset(app,job.name);val token=checkNotNull(queue.manualRequest(job.id));check(queue.consumeManual(token,uri)==job.id);return uri }
    try {
        var job=next();var uri=target(job)
        // A distinct provider URI for the original after external truncation still cannot write.
        val alias=source;WorkflowFaultProvider.redirect=source;source.writeBytes(byteArrayOf())
        queue.run(job.id,uri);check(queue.data().jobs.single().status==WorkflowStatus.UNCERTAIN && source.length()==0L && alias.length()==0L)
        WorkflowFaultProvider.redirect=null;source.writeBytes(original)
        job=next();uri=WorkflowFaultProvider.reset(app,job.name);val stale=checkNotNull(queue.manualRequest(job.id));queue.invalidateManual();check(queue.consumeManual(stale,uri)==null && WorkflowFaultProvider.opened==0)
        job=next();uri=WorkflowFaultProvider.reset(app,job.name);val cancelled=checkNotNull(queue.manualRequest(job.id));check(queue.consumeManual(cancelled,null)==null && WorkflowFaultProvider.opened==0)
        job=next();uri=target(job);WorkflowFaultProvider.failOpen=true;queue.run(job.id,uri);check(queue.data().jobs.single().status==WorkflowStatus.UNCERTAIN && WorkflowFaultProvider.bytes(app).isEmpty())
        for(lock in listOf(false,true)) {
            job=next();uri=target(job);WorkflowFaultProvider.entered=java.util.concurrent.CountDownLatch(1);WorkflowFaultProvider.release=java.util.concurrent.CountDownLatch(1)
            val worker=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { queue.run(job.id,uri) }
            check(WorkflowFaultProvider.entered!!.await(10,java.util.concurrent.TimeUnit.SECONDS))
            if(lock)queue.suspendWrites()else queue.cancel(job.id);WorkflowFaultProvider.release!!.countDown();worker.await()
            check(queue.data().jobs.single().status==WorkflowStatus.UNCERTAIN && WorkflowFaultProvider.bytes(app).isEmpty())
        }
        job=next();uri=target(job);queue.store.pdf(job.id).delete();queue.run(job.id,uri);check(WorkflowFaultProvider.opened==0)
        job=next();uri=target(job);val frozen=queue.store.pdf(job.id).readBytes();source.delete();queue.run(job.id,uri);check(queue.data().jobs.single().status==WorkflowStatus.SAVED && frozen.contentEquals(WorkflowFaultProvider.bytes(app)))
        File(app.getExternalFilesDir(null),"m31-evidence/purged-original.pdf").apply { parentFile!!.mkdirs();writeBytes(WorkflowFaultProvider.bytes(app)) }
        File(app.getExternalFilesDir(null),"m31-evidence/fault-checks.json").apply { parentFile!!.mkdirs();writeText("{\"truncatedSourceAliasRefused\":true,\"staleCallback\":true,\"pickerCancel\":true,\"failedOpen\":true,\"cancelAndLockBeforeFirstChunk\":true,\"missingSnapshot\":true,\"purgedOriginalFrozenJob\":true}") }
    } finally {
        WorkflowFaultProvider.release?.countDown();WorkflowFaultProvider.reset(app,"synthetic.pdf")
        queue.closeOwner();root.deleteRecursively()
    }
}
