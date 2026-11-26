package org.synapseworks.pageharbor.document.booklayout

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap

internal data class BookSourceAnalysis(val digest:String?,val detection:BookDetection,val canSplit:Boolean)
internal data class BookReviewedPage(val page:DocumentPage,val initial:BookSourceAnalysis,
    val analysis:BookSourceAnalysis=initial,val turns:Int=0,val line:BookSplitLine?=initial.detection.line,
    val split:Boolean=initial.detection.status==BookDetectionStatus.CONFIDENT,val manual:Boolean=false)
internal data class BookPreviewImage(val file:File,val metadata:DocumentImageMetadata)
internal data class BookSplitPreview(val spread:BookPreviewImage,val halves:List<BookPreviewImage>)
internal data class BookDerivedPage(val source:DocumentResource,val metadata:DocumentImageMetadata)
internal data class BookPageReplacement(val original:DocumentPage,val halves:List<BookDerivedPage>)

/** Private, serial image work only. The caller owns the workspace until transfer or cancellation.
 * Original sources are never opened for writing. Snapshot SHA guards external mutation between
 * analysis/review/Apply; all row geometry acts on physically upright, unfiltered pixels.
 */
internal class BookSplitProcessor(private val context:Context,private val maximumStagingBytes:Long=256L*1024*1024,
    rootName:String="book-splits",private val maximumOutputBytes:Long=MAX_OUTPUT_BYTES) {
    private val root=File(context.cacheDir,rootName)
    init{require(rootName in setOf("book-splits","page-curvature","page-crops","document-collages","object-removal"));require(maximumOutputBytes>0&&maximumStagingBytes>0)}
    companion object {
        private val startup=Any();private val initialized=mutableSetOf<String>()
        const val MAX_SOURCE_BYTES=64L*1024*1024
        const val MAX_OUTPUT_BYTES=16L*1024*1024
        const val MIN_FREE=64L*1024*1024
        const val MAX_PIXELS=12_000_000L
    }
    suspend fun begin():File {
        var created:File?=null;var handedOff=false
        try {
            val result=withContext(Dispatchers.IO){synchronized(startup){
            check(root.mkdirs()||root.isDirectory);check(!Files.isSymbolicLink(root.toPath()))
            if(initialized.add(root.canonicalPath))root.listFiles()?.filter{validName(it.name)}?.forEach(::deleteWorkspace)
            check(root.usableSpace>=MIN_FREE)
            File(root,UUID.randomUUID().toString()).also{check(it.mkdir());created=it}
            }}
            handedOff=true;return result
        }finally{if(!handedOff)withContext(NonCancellable+Dispatchers.IO){created?.let(::deleteWorkspace)}}
    }
    suspend fun analyze(work:File,page:DocumentPage,turns:Int=0,expectedDigest:String?=null):BookSourceAnalysis=withContext(Dispatchers.IO){
        var snapshot:File?=null
        try {
            val pair=snapshot(work,page);snapshot=pair.first
            if(expectedDigest!=null)check(pair.second==expectedDigest)
            val image=decode(snapshot,page,turns,analysis=true)
            try {
                val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                val operation=coroutineContext
                BookSourceAnalysis(pair.second,BookSpreadDetector.detect(BookAnalysisImage(image.width,image.height,pixels)){operation.ensureActive()},true)
            }finally{image.recycle()}
        }catch(e:CancellationException){throw e}
        catch(_:OutOfMemoryError){BookSourceAnalysis(null,BookDetection(BookDetectionStatus.UNSUPPORTED,reason=BookDetectionReason.GEOMETRY),false)}
        catch(_:Exception){BookSourceAnalysis(null,BookDetection(BookDetectionStatus.UNSUPPORTED,reason=BookDetectionReason.GEOMETRY),false)}
        finally{snapshot?.delete()}
    }
    suspend fun preview(work:File,review:BookReviewedPage,order:BookReadingOrder):BookSplitPreview=withContext(Dispatchers.IO){
        checkWorkspace(work);work.listFiles()?.filter{it.name.startsWith("preview-")}?.forEach{it.delete()}
        val pair=snapshot(work,review.page);var completed=false
        try {
            check(pair.second==review.analysis.digest)
            val image=decode(pair.first,review.page,review.turns,analysis=true)
            try {
                val guide=image.copy(Bitmap.Config.ARGB_8888,true)
                val spread=try {
                    android.graphics.Canvas(guide).apply{drawColor(android.graphics.Color.WHITE);drawBitmap(image,0f,0f,null)}
                    if(review.split)review.line?.let{line->android.graphics.Canvas(guide).drawLine(line.top*guide.width,0f,line.bottom*guide.width,guide.height.toFloat(),
                        android.graphics.Paint().apply{color=android.graphics.Color.BLUE;strokeWidth=3f})}
                    writeBitmap(work,"preview-${UUID.randomUUID()}.jpg",guide)
                }finally{guide.recycle()}
                val halves=if(review.split)requireNotNull(review.line).halves(image.width,image.height,order).map{half->
                    val cropped=crop(image,requireNotNull(review.line),half)
                    try{writeBitmap(work,"preview-${UUID.randomUUID()}.jpg",cropped)}finally{cropped.recycle()}
                }else emptyList()
                BookSplitPreview(spread,halves).also{completed=true}
            }finally{image.recycle()}
        }finally{pair.first.delete();if(!completed)work.listFiles()?.filter{it.name.startsWith("preview-")}?.forEach{it.delete()}}
    }
    suspend fun prepare(work:File,reviews:List<BookReviewedPage>,order:BookReadingOrder,
        progress:suspend(Int,Int)->Unit={_,_->}):List<BookPageReplacement> = withContext(Dispatchers.IO){
        checkWorkspace(work);val selected=reviews.filter{it.split};val results=mutableListOf<BookPageReplacement>()
        var completed=false
        try {
        for((index,review)in selected.withIndex()){
            coroutineContext.ensureActive();val pair=snapshot(work,review.page)
            try {
                check(pair.second==review.analysis.digest)
                val image=decode(pair.first,review.page,review.turns,analysis=false)
                try {
                    val line=requireNotNull(review.line);val halves=line.halves(image.width,image.height,order).map{half->
                        val cropped=crop(image,line,half)
                        try {
                            val output=writeBitmap(work,"page-${UUID.randomUUID()}.jpg",cropped)
                            val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",output.file)
                            BookDerivedPage(DocumentResource(uri.toString(),DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                                OwnedTemporaryFile(output.file.path,work.path)),output.metadata)
                        }finally{cropped.recycle()}
                    }
                    results+=BookPageReplacement(review.page,halves)
                }finally{image.recycle()}
            }finally{pair.first.delete()}
            progress(index+1,selected.size)
        }
        coroutineContext.ensureActive()
        results.flatMap{it.halves}.forEach{half->val file=File(requireNotNull(half.source.ownedTemporaryFile).path);check(file.isFile&&file.length()==half.metadata.sourceByteCount)}
        completed=true;results
        }finally{if(!completed)work.listFiles()?.filter{it.name.startsWith("page-")}?.forEach{it.delete()}}
    }
    suspend fun finish(work:File,keep:Set<String> = emptySet())=withContext(Dispatchers.IO){
        if(!work.exists())return@withContext
        checkWorkspace(work)
        work.listFiles()?.forEach{file->if(file.name !in keep&&file.isFile&&!Files.isSymbolicLink(file.toPath()))file.delete()}
        if(keep.isEmpty())work.delete()
    }
    internal suspend fun snapshot(work:File,page:DocumentPage,allowSampledSource:Boolean=false):Pair<File,String> {
        checkWorkspace(work);check(root.usableSpace>=MIN_FREE)
        check(page.contentType.startsWith("image/"));check((page.imageMetadata.sourceByteCount?:0)<=MAX_SOURCE_BYTES)
        val file=File(work,"source-${UUID.randomUUID()}.bin");val digest=MessageDigest.getInstance("SHA-256");var success=false
        try {
            val budget=minOf(MAX_SOURCE_BYTES,remaining(work));check(budget>0)
            context.contentResolver.openInputStream(page.source.toAndroidUri())!!.use{input->
                Files.newOutputStream(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use{output->
                    val buffer=ByteArray(64*1024);var count=0L
                    while(true){coroutineContext.ensureActive();val n=input.read(buffer);if(n<0)break;count+=n;check(count<=budget);digest.update(buffer,0,n);output.write(buffer,0,n)}
                    check(count>0)
                }
            }
            val bounds=readImageBounds{file.inputStream()}?:error("Image unavailable")
            if (allowSampledSource) {
                check(DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(
                    DocumentImageMetadata(file.length(), bounds.first, bounds.second),
                ) == null)
            } else {
                check(bounds.first in 2..6000&&bounds.second in 2..6000&&bounds.first.toLong()*bounds.second<=MAX_PIXELS)
            }
            success=true;return file to digest.digest().joinToString(""){"%02x".format(it)}
        }finally{if(!success)file.delete()}
    }
    internal fun decode(file:File,page:DocumentPage,turns:Int,analysis:Boolean,allowSampledSource:Boolean=false):Bitmap {
        val open = { file.inputStream() }
        val raw = when {
            analysis && allowSampledSource -> decodeDisplayBitmap(open, BitmapDisplaySize(1600, 1600), 6_000_000L)
            analysis -> decodeDisplayBitmap(open, BitmapDisplaySize(768, 768), 2_400_000L)
            allowSampledSource -> {
                val bounds = checkNotNull(readImageBounds(open))
                val sample = checkNotNull(documentWorkingSampleSize(bounds.first, bounds.second))
                if (sample == 1) decodeFullResolution(open) else decodeWithSample(open, sample)
            }
            else -> decodeFullResolution(open)
        }
        checkNotNull(raw)
        var result:Bitmap?=null
        try {
            result=transform(raw,readOrientation(open),DocumentPageRotation.entries[(page.rotation.ordinal+turns)%4])
            return result
        }finally{if(result!==raw)raw.recycle()}
    }
    private suspend fun crop(source:Bitmap,line:BookSplitLine,half:BookHalf):Bitmap {
        var complete=false;val out=Bitmap.createBitmap(half.width,source.height,Bitmap.Config.ARGB_8888)
        try {
            val input=IntArray(source.width);val row=IntArray(half.width)
            for(y in 0 until source.height){
                coroutineContext.ensureActive();row.fill(android.graphics.Color.WHITE);source.getPixels(input,0,source.width,0,y,source.width,1)
                val cut=line.cut(source.width,source.height,y)
                val start=if(half.left)0 else cut;val end=if(half.left)cut else source.width
                for(x in start until end)row[x-half.sourceOffset]=compositeWhite(input[x])
                out.setPixels(row,0,half.width,0,y,half.width,1)
            }
            complete=true;return out
        }finally{if(!complete)out.recycle()}
    }
    internal suspend fun writeBitmap(work:File,name:String,bitmap:Bitmap,format:Bitmap.CompressFormat=Bitmap.CompressFormat.JPEG):BookPreviewImage {
        coroutineContext.ensureActive();val file=File(work,name);var success=false
        try {
            val limit=minOf(maximumOutputBytes,remaining(work));check(limit>0);val operation=coroutineContext
            Files.newOutputStream(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use{stream->
                val bounded=object:FilterOutputStream(stream){var count=0L
                    override fun write(value:Int){operation.ensureActive();count++;check(count<=limit);out.write(value)}
                    override fun write(bytes:ByteArray,offset:Int,length:Int){operation.ensureActive();count+=length;check(count<=limit);out.write(bytes,offset,length)}
                }
                check(bitmap.compress(format,if(format==Bitmap.CompressFormat.PNG)100 else 95,bounded));bounded.flush()
            }
            coroutineContext.ensureActive();success=true;return BookPreviewImage(file,DocumentImageMetadata(file.length(),bitmap.width,bitmap.height))
        }finally{if(!success)file.delete()}
    }
    private fun remaining(work:File)=maximumStagingBytes-work.listFiles().orEmpty().sumOf{if(it.isFile)it.length()else 0L}
    private fun checkWorkspace(work:File){check(validName(work.name)&&!Files.isSymbolicLink(work.toPath())&&work.canonicalFile.parentFile==root.canonicalFile&&work.isDirectory)}
    private fun validName(name:String)=runCatching{UUID.fromString(name).toString()==name}.getOrDefault(false)
    private fun deleteWorkspace(work:File){
        if(Files.isSymbolicLink(work.toPath())||work.canonicalFile.parentFile!=root.canonicalFile)return
        work.listFiles()?.filter{it.isFile&&!Files.isSymbolicLink(it.toPath())}?.forEach{it.delete()};work.delete()
    }
    private fun compositeWhite(pixel:Int):Int {
        val a=pixel ushr 24;if(a==255)return pixel
        fun c(shift:Int)=(((pixel ushr shift)and 255)*a+255*(255-a)+127)/255
        return (255 shl 24)or(c(16)shl 16)or(c(8)shl 8)or c(0)
    }
}
