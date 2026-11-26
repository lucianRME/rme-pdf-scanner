package org.synapseworks.pageharbor.document.objectremoval

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.session.*

internal data class RemovalAnalysis(val snapshot:BookPreviewImage,val digest:String,val width:Int,val height:Int)
internal data class RemovalPrepared(val original:DocumentPage,val snapshot:DocumentResource,val result:BookDerivedPage,val sourceDigest:String,val resultDigest:String,val sourceBytes:Long=original.imageMetadata.sourceByteCount?:0)
internal data class RemovalProposal(val status:RemovalStatus,val prepared:RemovalPrepared?=null)
internal data class RemovalPublication(val before:DocumentSession,val after:DocumentSession)

internal fun removalPublication(session:DocumentSession,output:RemovalPrepared):RemovalPublication? {
    val page=output.original
    if(session.pages.firstOrNull{it.id==page.id}!==page)return null
    val sources=listOf(output.snapshot,output.result.source)
    if(sources.any{it.ownership!=DocumentResourceOwnership.RME_OWNED_TEMPORARY||!safeRemovalPath(it.ownedTemporaryFile)})return null
    if(sources.map{it.reference}.distinct().size!=2||sources.map{it.ownedTemporaryFile!!.path}.distinct().size!=2||session.pages.any{it.source in sources})return null
    if(listOf(output.sourceDigest,output.resultDigest).any{it.length!=64||it.any{c->c !in '0'..'9'&&c !in 'a'..'f'}}||output.sourceBytes !in 1..(64L*1024*1024))return null
    val metadata=output.result.metadata;val w=metadata.width?:return null;val h=metadata.height?:return null
    if(w !in 2..6000||h !in 2..6000||w.toLong()*h>12_000_000||(metadata.sourceByteCount?:0) !in 1..(32L*1024*1024))return null
    val highest=session.pages.maxOfOrNull{it.id.value}?:return null;if(highest==Long.MAX_VALUE)return null
    val previous=page.copy(source=output.snapshot)
    val next=page.copy(id=DocumentPageId(highest+1),source=output.result.source,contentType="image/png",imageMetadata=output.result.metadata,persistentId=null)
    return RemovalPublication(session.copy(pages=session.pages.map{if(it===page)previous else it}),session.copy(pages=session.pages.map{if(it===page)next else it},directPdfSource=null,directPdfPageIds=emptyList()))
}
private fun safeRemovalPath(owned:OwnedTemporaryFile?):Boolean {
    if(owned==null)return false
    return runCatching{val p=java.nio.file.Paths.get(owned.path);val r=java.nio.file.Paths.get(owned.rootPath);p.isAbsolute&&r.isAbsolute&&p==p.normalize()&&r==r.normalize()&&p!=r&&p.startsWith(r)}.getOrDefault(false)
}

internal class ObjectRemovalProcessor(private val context:Context,maximumStagingBytes:Long=192L*1024*1024,maximumOutputBytes:Long=32L*1024*1024) {
    private val store=BookSplitProcessor(context,maximumStagingBytes,"object-removal",maximumOutputBytes)
    suspend fun begin()=store.begin()
    suspend fun finish(work:File,keep:Set<String> = emptySet())=store.finish(work,keep)
    suspend fun open(work:File,page:DocumentPage):RemovalAnalysis=withContext(Dispatchers.IO){
        val snapshot=store.snapshot(work,page);var ok=false
        try{val bounds=requireNotNull(readImageBounds{snapshot.first.inputStream()});val orientation=readOrientation{snapshot.first.inputStream()}
            val swap=orientation in setOf(5,6,7,8);val width=if(swap)bounds.second else bounds.first;val height=if(swap)bounds.first else bounds.second
            RemovalAnalysis(BookPreviewImage(snapshot.first,page.imageMetadata.copy(sourceByteCount=snapshot.first.length())),snapshot.second,width,height).also{ok=true}
        }finally{if(!ok)snapshot.first.delete()}
    }
    suspend fun detect(work:File,page:DocumentPage,analysis:RemovalAnalysis)=withContext(Dispatchers.IO){
        verifySource(work,page,analysis.digest);check(digest(analysis.snapshot.file)==analysis.digest)
        val image=store.decode(analysis.snapshot.file,page.copy(rotation=DocumentPageRotation.DEGREES_0),0,false)
        try{check(image.width==analysis.width&&image.height==analysis.height);val job=coroutineContext
            org.synapseworks.pageharbor.document.finger.EdgeFingerDetector.detect(image.width,image.height,{y,row->image.getPixels(row,0,image.width,0,y,image.width,1)},active={job.ensureActive()})
        }finally{image.recycle()}
    }
    suspend fun discard(work:File){withContext(Dispatchers.IO){work.listFiles()?.filter{it.name.startsWith("result-")}?.forEach{it.delete()}}}
    suspend fun preview(work:File,page:DocumentPage,analysis:RemovalAnalysis,mask:InpaintMask,finger:Boolean=false):RemovalProposal=withContext(Dispatchers.IO){
        discard(work);verifySource(work,page,analysis.digest)
        check(digest(analysis.snapshot.file)==analysis.digest)
        val image=store.decode(analysis.snapshot.file,page.copy(rotation=DocumentPageRotation.DEGREES_0),0,false)
        try{
            check(image.width==analysis.width&&image.height==analysis.height)
            val output=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
            try{val job=coroutineContext;val read:(Int,IntArray)->Unit={y,row->image.getPixels(row,0,image.width,0,y,image.width,1)};val write:(Int,IntArray)->Unit={y,row->output.setPixels(row,0,image.width,0,y,image.width,1)}
                val status=if(finger)org.synapseworks.pageharbor.document.finger.FingerPaperRepair.process(image.width,image.height,mask,read,write,{job.ensureActive()})else PaperPlaneInpaint.process(image.width,image.height,mask,read,write,{job.ensureActive()})
                if(status!=RemovalStatus.READY)return@withContext RemovalProposal(status)
                val file=store.writeBitmap(work,"result-${UUID.randomUUID()}.png",output,Bitmap.CompressFormat.PNG)
                val source=resource(analysis.snapshot.file,work);val result=BookDerivedPage(resource(file.file,work),file.metadata)
                RemovalProposal(status,RemovalPrepared(page,source,result,analysis.digest,digest(file.file),analysis.snapshot.file.length()))
            }finally{output.recycle()}
        }finally{image.recycle()}
    }
    suspend fun verify(work:File,analysis:RemovalAnalysis,prepared:RemovalPrepared)=withContext(Dispatchers.IO){
        verifySource(work,prepared.original,prepared.sourceDigest)
        check(digest(analysis.snapshot.file)==prepared.sourceDigest)
        val result=requireNotNull(prepared.result.source.ownedTemporaryFile?.canonicalIdentityOrNull()).file
        check(result.length()==prepared.result.metadata.sourceByteCount&&digest(result)==prepared.resultDigest)
        coroutineContext.ensureActive()
    }
    private suspend fun verifySource(work:File,page:DocumentPage,expected:String){val pair=store.snapshot(work,page);try{check(pair.second==expected)}finally{pair.first.delete()}}
    private fun resource(file:File,work:File)=DocumentResource(FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file).toString(),DocumentResourceOwnership.RME_OWNED_TEMPORARY,OwnedTemporaryFile(file.path,work.path))
    private suspend fun digest(file:File):String {val d=MessageDigest.getInstance("SHA-256");val row=ByteArray(64*1024);var count=0L;file.inputStream().use{input->while(true){coroutineContext.ensureActive();val n=input.read(row);if(n<0)break;count+=n;check(count<=64L*1024*1024);d.update(row,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
}
