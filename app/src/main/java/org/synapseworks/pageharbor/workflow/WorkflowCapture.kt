package org.synapseworks.pageharbor.workflow

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.searchablepdf.*
import org.synapseworks.pageharbor.document.targetsize.*
import org.synapseworks.pageharbor.document.protectedpdf.*
import org.synapseworks.pageharbor.ocr.OcrResult

internal data class WorkflowCaptureResult(val directory: File, val output: File, val preview: File, val digest: String, val bytes: Long, val previewDigest: String, val sourceGuards:List<WorkflowSourceGuard> = emptyList())

/** Materializes source bytes first, then effective visuals once. No mutable URI survives review. */
internal suspend fun captureWorkflow(context: Context, root: File, document: DocumentSession, preset: WorkflowPreset, ocr: OcrResult?, password: CharArray, allowed: () -> Boolean): WorkflowCaptureResult {
    require(preset.available && preset !in setOf(WorkflowPreset.DOCX,WorkflowPreset.XLSX,WorkflowPreset.PPTX,WorkflowPreset.OFFICE) && document.pages.size in 1..20)
    val dir=File(root,"capture-${UUID.randomUUID()}");check(dir.mkdirs())
    var retained=false
    try {
        require(root.usableSpace>=320L*1024*1024)
        require(root.walkTopDown().filter { it.isFile }.sumOf { it.length() }<=64L*1024*1024)
        val sourceGuards=if(preset==WorkflowPreset.ARCHIVAL) {
            val sources=(document.pages.map { it.source }+document.originalPdfSources).distinct();require(sources.size<=40)
            sources.map { workflowSourceGuard(context,it.toAndroidUri()) }
        } else emptyList()
        val capturedSources=mutableListOf<Pair<Uri,Pair<String,Long>>>()
        var total=0L
        val pages=document.pages.mapIndexed { i,page ->
            coroutineContext.ensureActive();check(allowed())
            val source=File(dir,"source-$i");val uri=page.source.toAndroidUri()
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> source.outputStream().use { out ->
                val buffer=ByteArray(64*1024);var count=0L
                while(true) { coroutineContext.ensureActive();check(allowed());val n=input.read(buffer);if(n<0)break;count+=n;total+=n;require(count<=64L*1024*1024 && total<=64L*1024*1024);out.write(buffer,0,n) }
            } }
            capturedSources+=uri to workflowDigest(source.inputStream(),64L*1024*1024)
            NormalPdfPage(page.id.value,DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.RME_OWNED_TEMPORARY),page.filter,page.rotation,page.contentType,page.imageMetadata,page.adjustments,page.annotations,page.paperSize)
        }
        val current=coroutineContext
        val provider=NormalPdfVisualStreamProvider(context,dir,flattenToSrgb=preset==WorkflowPreset.ARCHIVAL,isCancellationRequested={ !current.isActive || !allowed() })
        var visualBytes=0L
        val visuals=pages.mapIndexed { i,p ->
            val file=File(dir,"visual-$i.jpg")
            provider.open(p).use { input -> file.outputStream().use { out ->
                val b=ByteArray(64*1024)
                while(true) { coroutineContext.ensureActive();check(allowed());val n=input.read(b);if(n<0)break;visualBytes+=n;require(visualBytes<=24L*1024*1024);out.write(b,0,n) }
            } };file
        }
        if(preset==WorkflowPreset.SEARCHABLE) {
            require(document.pages.all { it.rotation==DocumentPageRotation.DEGREES_0 })
            require(ocr?.pages?.size==pages.size && ocr.pages.map { it.pageIndex }.sorted()==pages.indices.toList())
            require(ocr.pages.all { it.error==null && it.layout!=null && it.layout.rotationDegrees==0 && it.text.length<=32000 })
        }
        val normal=File(dir,"normal.pdf")
        val imagePages=visuals.mapIndexed { i,file -> SearchablePdfPage({file.inputStream()},if(preset==WorkflowPreset.SEARCHABLE)ocr?.pages?.first { it.pageIndex==i } else null,physicalPdfPageSize(pages[i].paperSize,pages[i].rotation)) }
        if(preset==WorkflowPreset.ARCHIVAL) {
            org.synapseworks.pageharbor.document.archival.PdfA1bGenerator(context).generate(imagePages,normal,dir,allowed)
        } else {
            val generated=PdfBoxSearchablePdfGenerator(context).generate(SearchablePdfRequest(imagePages,normal,dir))
            require(generated is SearchablePdfGenerationResult.Success)
        }
        require(normal.length() in 1..WORKFLOW_MAX_PDF)
        var output=normal
        if(preset==WorkflowPreset.ENCRYPTED) {
            output=File(dir,"encrypted.pdf")
            require(PasswordProtectedPdf().create(normal,output,password) is PasswordPdfResult.Success)
        }
        if(preset==WorkflowPreset.SMALL || preset==WorkflowPreset.LARGE) {
            require(pages.all { it.paperSize==null || it.rotation==DocumentPageRotation.DEGREES_0 })
            val engine=LocalTargetSizePdfEngine(context)
            val target=if(preset==WorkflowPreset.SMALL)1024L*1024 else 5L*1024*1024
            val request=TargetSizePdfRequest(visuals.mapIndexed { i,file -> TargetSizePdfPage(pages[i].pageId,Uri.fromFile(file),DocumentSourceCategory.SELECTED_IMAGE,"image/jpeg",DocumentImageMetadata(sourceByteCount=file.length()),paperSize=pages[i].paperSize) },targetBytes=target,cancellationSignal=TargetSizeCancellationSignal { !current.isActive || !allowed() })
            val result=engine.prepare(request)
            val prepared=(result as? TargetSizePdfResult.TargetReached)?.prepared
            if(prepared==null) { if(result is TargetSizePdfResult.TargetNotReachableAtAcceptableQuality)result.smallestAcceptable?.let(engine::discard);error("Target unavailable") }
            output=File(dir,"target.pdf")
            try { prepared.file.copyTo(output);require(output.length()<=target) } finally { engine.discard(prepared) }
        }
        capturedSources.forEach { (uri, expected) ->
            coroutineContext.ensureActive();check(allowed())
            require(workflowDigest(requireNotNull(context.contentResolver.openInputStream(uri)),64L*1024*1024)==expected)
        }
        coroutineContext.ensureActive();check(allowed())
        val digest=workflowDigest(output.inputStream());val preview=if(preset==WorkflowPreset.ENCRYPTED)normal else output
        val previewDigest=if(preview==output)digest.first else workflowDigest(preview.inputStream()).first
        retained=true
        return WorkflowCaptureResult(dir,output,preview,digest.first,digest.second,previewDigest,sourceGuards)
    } finally { password.fill('\u0000');if(!retained)dir.deleteRecursively() }
}
