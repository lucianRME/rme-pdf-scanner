package org.synapseworks.pageharbor.document.capture

import android.app.Application
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter

/** Retained metadata and bounded staging; no camera/Activity/bitmap is retained across recreation. */
internal class ContinuousCaptureViewModel(app:Application):AndroidViewModel(app) {
    private val controller=ContinuousCaptureController()
    private val store=ContinuousCaptureStore(File(app.cacheDir,"continuous-scans"))
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val files=Mutex()
    init {io.launch{files.withLock{runCatching{store.cleanupOrphans()}}}}
    private var cleared=false
    var state:ContinuousCaptureState? by mutableStateOf(null);private set
    var request:DocumentAcquisitionToken?=null;private set
    private fun publish(){if(!cleared)state=controller.state}
    val canOpen:Boolean get()=controller.canOpen
    fun open(token:DocumentAcquisitionToken,revision:Long, singlePage: Boolean = false):Boolean {
        if(!controller.open(revision, singlePage))return false
        request=token;publish();return true
    }
    fun selectMode(mode: CaptureMode) { controller.selectMode(mode); publish() }
    fun pause(problem:CaptureProblem?=null){controller.pause(problem);publish()}
    fun resume(){controller.resume();publish()}
    fun finish(){controller.finish();publish()}
    fun retake(){controller.retake();publish()}
    fun replace(index: Int) { controller.replace(index); publish() }
    fun move(index: Int, offset: Int): Boolean {
        if (!controller.move(index, offset)) return false
        publish()
        return true
    }
    fun moveTo(index: Int, destination: Int): Boolean {
        if (!controller.moveTo(index, destination)) return false
        publish()
        return true
    }

    fun capture(takePicture:(File,(Boolean)->Unit)->Unit) {
        val ticket=controller.request()?:return;publish()
        io.launch {
            val file=runCatching{files.withLock{store.create(ticket)}}.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if(file==null){controller.complete(ticket,null);publish();return@withContext}
                val once=AtomicBoolean()
                val complete:(Boolean)->Unit={saved->if(once.compareAndSet(false,true))completeCapture(ticket,file,saved)}
                if(cleared||controller.state?.owner!=ticket.owner||controller.state?.paused==true){complete(false)}
                else try{takePicture(file,complete)}catch(_:Exception){complete(false)}
            }
        }
    }
    private fun completeCapture(ticket:CaptureTicket,file:File,saved:Boolean) {
        io.launch {
            val page=if(saved)runCatching{files.withLock{prepare(file, ticket.singlePage)}}.getOrNull()else null
            withContext(Dispatchers.Main.immediate) {
                val replacing = controller.state?.replacing
                val result = controller.complete(ticket,page)
                publish()
                val discarded = when {
                    result == CaptureDisposition.DISCARD -> page?.file ?: file
                    result == CaptureDisposition.ACCEPT -> replacing?.file
                    page == null -> file
                    else -> null
                }
                if (discarded != null) {
                    io.launch { files.withLock { runCatching { store.delete(discarded) } } }
                }
                // Never delete a successful batch's earlier pages when just one capture fails.
            }
        }
    }
    private fun prepare(file:File, singlePage: Boolean):CapturedPage {
        check(file.length() in 1..ContinuousCaptureStore.MAX_BYTES)
        file.inputStream().use{check(it.read()==0xff&&it.read()==0xd8&&it.read()==0xff)}
        val bounds=readImageBounds{file.inputStream()}?:error("Image unavailable")
        val limits = DocumentInputLimits(
            maxSourceBytes = ContinuousCaptureStore.MAX_BYTES,
            maxDecodedPixelCount = if (singlePage) 12_500_000L else 3_000_000L,
            maxImageDimension = if (singlePage) 4096 else 2000,
        )
        check(bounds.first in 1..limits.maxImageDimension && bounds.second in 1..limits.maxImageDimension &&
            bounds.first.toLong() * bounds.second <= limits.maxDecodedPixelCount)
        // Existing preview/OCR contracts consume physically upright accepted sources. Normalize only
        // a nonidentity EXIF capture, reusing the committed bounded writer before source adoption.
        val orientation=readOrientation{file.inputStream()}
        if(!exifOrientationIsIdentity(orientation)) {
            val normalized=File(file.parentFile,file.name+".normalized")
            try {
                check(writeFilteredJpegToDestination({file.inputStream()},normalized.outputStream(),DocumentFilter.ORIGINAL,
                    imageMetadata=DocumentImageMetadata(file.length(),bounds.first,bounds.second),
                    limits=limits)==PageExportResult.Success)
                check(file.delete()&&normalized.renameTo(file))
            }finally{normalized.delete()}
        }
        val dimensions=readImageBounds{file.inputStream()}?:error("Image unavailable")
        val metadata=DocumentImageMetadata(file.length(),dimensions.first,dimensions.second)
        return CapturedPage(store.ready(file),metadata)
    }
    fun input(ensureActive:()->Unit={}):DocumentAcquisitionInput? {
        val s=state?.takeIf{it.phase==CapturePhase.REVIEW&&!it.busy&&it.pages.isNotEmpty()}?:return null
        val app=getApplication<Application>()
        return DocumentAcquisitionInput(s.pages.map{page->
            ensureActive();check(page.file.isFile&&page.file.length()==page.metadata.sourceByteCount){"Captured page unavailable"}
            AcquiredDocumentPage(
            AcquiredResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",page.file).toString(),
                DocumentResourceOwnership.RME_OWNED_TEMPORARY,OwnedTemporaryFile(page.file.path,page.file.parentFile!!.path)),
            DocumentSourceCategory.SCAN,"image/jpeg",page.metadata)})
    }
    /** Detach capture ownership before the synchronous session publication can notify observers. */
    fun transfer(commit:()->Boolean):Boolean {
        val owner=requireNotNull(state).owner
        controller.release();request=null;publish()
        val accepted=commit()
        if(!accepted)io.launch{files.withLock{store.discard(owner)}}
        return accepted
    }
    fun transferred(){transfer{true}}
    fun cancel() {
        val owner=controller.state?.owner;controller.cancel();request=null;publish()
        if(owner!=null)io.launch{files.withLock{store.discard(owner)}}
    }
    override fun onCleared(){cleared=true;cancel();state=null;super.onCleared()}
}
