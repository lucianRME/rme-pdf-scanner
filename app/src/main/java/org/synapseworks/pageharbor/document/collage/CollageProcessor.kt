package org.synapseworks.pageharbor.document.collage

import android.content.Context
import android.graphics.*
import androidx.core.content.FileProvider
import java.io.File
import java.io.FilterOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap

internal data class CollageOutput(val pages:List<BookDerivedPage>,val paper:DocumentPaperSize)
internal class CollageWorkspace(val directory:File) {
    internal data class Appearance(val file:File,val size:CollageSize)
    internal val appearances=mutableMapOf<Long,Appearance>()
    // Retain the first observed digest even after a tile is removed/re-added.
    internal val sources=mutableMapOf<Long,Pair<String,Long>>()
}
/** One serial worker, one output bitmap and one sampled tile at a time. No preview is an input
 * to final output. Full-resolution appearance includes EXIF/recipe/marks exactly once. */
internal class CollageProcessor(private val context:Context,private val maximumBytes:Long=256L*1024*1024,
    private val minimumFree:Long=96L*1024*1024) {
    private val store=BookSplitProcessor(context,maximumBytes,"document-collages")
    suspend fun begin()=CollageWorkspace(store.begin())
    suspend fun finish(work:CollageWorkspace,keep:Set<String> = emptySet())=store.finish(work.directory,keep)
    private fun capacity(work:CollageWorkspace):Long {
        check(work.directory.usableSpace>=minimumFree)
        return maximumBytes-work.directory.listFiles().orEmpty().sumOf{if(it.isFile)it.length()else 0L}
    }
    internal suspend fun verify(work:CollageWorkspace,pages:List<DocumentPage>)=withContext(Dispatchers.IO){
        for(page in pages.distinctBy{it.id}) {
            coroutineContext.ensureActive();capacity(work)
            val pair=store.snapshot(work.directory,page)
            try{check(work.sources[page.id.value]?.first==pair.second)}finally{pair.first.delete()}
        }
    }
    private suspend fun appearances(work:CollageWorkspace,draft:CollageDraft,session:DocumentSession) {
        check(draft.tiles.isNotEmpty());val selected=draft.tiles.map{it.pageId}.toSet()
        check(selected.size<=MAX_COLLAGE_TILES)
        work.appearances.keys.filter{it !in selected}.forEach{work.appearances.remove(it)?.file?.delete()}
        val pages=session.pages.associateBy{it.id.value}
        for(id in selected){
            coroutineContext.ensureActive();val page=requireNotNull(pages[id])
            val existing=work.appearances[id]
            if(existing!=null&&existing.file.isFile&&existing.file.length()>0)continue
            work.appearances.remove(id);capacity(work)
            val snapshot=store.snapshot(work.directory,page)
            var appearance:File?=null;var complete=false
            try{
                val previous=work.sources[id]
                check(previous==null||previous.first==snapshot.second)
                val bytes=snapshot.first.length()
                val selectedBytes=selected.sumOf{if(it==id)bytes else work.sources[it]?.second?:0L}
                check(selectedBytes<=192L*1024*1024)
                work.sources[id]=snapshot.second to bytes
                val limit=min(32L*1024*1024,capacity(work));check(limit>0)
                val file=File(work.directory,"appearance-${UUID.randomUUID()}.jpg");appearance=file
                val operation=coroutineContext
                file.outputStream().use{stream->
                    val bounded=object:FilterOutputStream(stream){var count=0L
                        override fun write(b:Int){operation.ensureActive();count++;check(count<=limit);out.write(b)}
                        override fun write(b:ByteArray,off:Int,len:Int){operation.ensureActive();count+=len;check(count<=limit);out.write(b,off,len)}
                    }
                    check(writeSizedJpegToDestination(openSource={snapshot.first.inputStream()},destination=bounded,
                        filter=page.filter,rotation=page.rotation,imageMetadata=page.imageMetadata,
                        encoding=SizedJpegEncoding(6000,6000,100),adjustments=page.adjustments,annotations=page.annotations,
                        isCancellationRequested={!operation.isActive},pixelSanitizer={bitmap->
                            Canvas(bitmap).drawColor(Color.WHITE,PorterDuff.Mode.DST_OVER);true
                        })==PageExportResult.Success)
                }
                val size=requireNotNull(readImageBounds{file.inputStream()})
                work.appearances[id]=CollageWorkspace.Appearance(file,CollageSize(size.first,size.second));complete=true
            }finally{snapshot.first.delete();if(!complete)appearance?.delete()}
        }
        check(selected.sumOf{requireNotNull(work.sources[it]).second}<=192L*1024*1024)
    }
    suspend fun preview(work:CollageWorkspace,draft:CollageDraft,session:DocumentSession):List<BookPreviewImage> = withContext(Dispatchers.IO){
        clear(work,"preview-");appearances(work,draft,session)
        val sheets=CollageGeometry.plan(draft,work.appearances.mapValues{it.value.size});var complete=false
        try{sheets.map{sheet->render(work,sheet,true).let{bitmap->
            try{capacity(work);store.writeBitmap(work.directory,"preview-${UUID.randomUUID()}.jpg",bitmap)}finally{bitmap.recycle()}
        }}.also{complete=true}}finally{if(!complete)clear(work,"preview-")}
    }
    suspend fun prepare(work:CollageWorkspace,draft:CollageDraft,session:DocumentSession):CollageOutput=withContext(Dispatchers.IO){
        clear(work,"page-");clear(work,"preview-");appearances(work,draft,session)
        val pages=draft.tiles.map{tile->requireNotNull(session.pages.firstOrNull{it.id.value==tile.pageId})}.distinctBy{it.id}
        verify(work,pages)
        val sheets=CollageGeometry.plan(draft,work.appearances.mapValues{it.value.size});check(session.pages.size+sheets.size<=500)
        var complete=false
        try{
            val outputs=sheets.map{sheet->
                val bitmap=render(work,sheet,false)
                val image=try{capacity(work);store.writeBitmap(work.directory,"page-${UUID.randomUUID()}.jpg",bitmap)}finally{bitmap.recycle()}
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",image.file)
                BookDerivedPage(DocumentResource(uri.toString(),DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                    OwnedTemporaryFile(image.file.path,work.directory.path)),image.metadata)
            }
            verify(work,pages);coroutineContext.ensureActive()
            val retained=(session.pages.map{it.source}+session.originalPdfSources+listOfNotNull(session.directPdfSource)+outputs.map{it.source})
                .mapNotNull{it.ownedTemporaryFile?.canonicalIdentityOrNull()?.file}.distinctBy{it.path}
            check(retained.sumOf{check(it.isFile);it.length()}<=256L*1024*1024)
            outputs.forEach{val file=requireNotNull(it.source.ownedTemporaryFile?.canonicalIdentityOrNull()).file;check(file.isFile&&file.length()==it.metadata.sourceByteCount)}
            CollageOutput(outputs,draft.settings.size).also{complete=true}
        }finally{if(!complete)clear(work,"page-")}
    }
    internal suspend fun render(work:CollageWorkspace,sheet:CollageSheet,preview:Boolean):Bitmap {
        val full=sheet.pixels();val factor=if(preview)min(1f,1000f/maxOf(full.first,full.second))else 1f
        val width=(full.first*factor).toInt().coerceAtLeast(1);val height=(full.second*factor).toInt().coerceAtLeast(1)
        check(width.toLong()*height<=3_200_000)
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);var complete=false
        try{
            val canvas=Canvas(output);canvas.drawColor(Color.WHITE)
            val sx=width/sheet.size.widthPoints;val sy=height/sheet.size.heightPoints
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for(p in sheet.placements){
                coroutineContext.ensureActive();val appearance=requireNotNull(work.appearances[p.tile.pageId])
                // Decode only the source resolution needed by this placement (never upscale a preview).
                val targetW=ceil(p.image.width*sx).toInt().coerceIn(1,6000);val targetH=ceil(p.image.height*sy).toInt().coerceIn(1,6000)
                val odd=p.tile.turns%2!=0
                val wantedW=if(odd)targetH else targetW;val wantedH=if(odd)targetW else targetH
                val raw=if(preview)requireNotNull(decodeDisplayBitmap({appearance.file.inputStream()},BitmapDisplaySize(wantedW,wantedH)))
                    else decodeFinalTile(appearance,wantedW,wantedH)
                var tile:Bitmap?=null
                try{
                    tile=if(p.tile.turns==0)raw else Bitmap.createBitmap(raw,0,0,raw.width,raw.height,Matrix().apply{postRotate(p.tile.turns*90f)},true)
                    canvas.save();canvas.clipRect(p.cell.left*sx,p.cell.top*sy,p.cell.right*sx,p.cell.bottom*sy)
                    canvas.drawBitmap(tile,null,RectF(p.image.left*sx,p.image.top*sy,p.image.right*sx,p.image.bottom*sy),paint);canvas.restore()
                }finally{if(tile!==raw)tile?.recycle();raw.recycle()}
            }
            coroutineContext.ensureActive();complete=true;return output
        }finally{if(!complete)output.recycle()}
    }
    /** Export sampling rounds toward more source detail. The display policy deliberately rounds
     * toward smaller buffers and must never determine accepted document resolution. */
    @Suppress("DEPRECATION")
    private fun decodeFinalTile(source:CollageWorkspace.Appearance,targetWidth:Int,targetHeight:Int):Bitmap {
        val decoder=requireNotNull(BitmapRegionDecoder.newInstance(source.file.path,false))
        try{
            check(decoder.width==source.size.width&&decoder.height==source.size.height)
            var sample=1
            while(sample<4096&&decoder.width/(sample*2)>=targetWidth&&decoder.height/(sample*2)>=targetHeight)sample*=2
            return requireNotNull(decoder.decodeRegion(Rect(0,0,decoder.width,decoder.height),BitmapFactory.Options().apply{
                inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888;inScaled=false
            }))
        }finally{decoder.recycle()}
    }
    private fun clear(work:CollageWorkspace,prefix:String){work.directory.listFiles()?.filter{it.isFile&&it.name.startsWith(prefix)}?.forEach{it.delete()}}
}
