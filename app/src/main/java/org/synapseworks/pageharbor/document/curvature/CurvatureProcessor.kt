package org.synapseworks.pageharbor.document.curvature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.crop.ImportCropDetector
import org.synapseworks.pageharbor.document.crop.PageCrop
import org.synapseworks.pageharbor.document.crop.rectifyCrop
import org.synapseworks.pageharbor.document.session.*

internal data class CurvatureAnalysis(
    val digest: String?,
    val estimate: CurvatureEstimate,
    val canProcess: Boolean,
    val perspective: PageCrop? = null,
    val straightenedEstimate: CurvatureEstimate? = null,
)

internal data class CurvatureReview(
    val page: DocumentPage,
    val initial: CurvatureAnalysis,
    val field: CurvatureField? = initial.estimate.field,
    val enabled: Boolean = initial.estimate.status == CurvatureStatus.PROPOSED,
    val manual: Boolean = false,
    val straighten: Boolean = false,
    val perspective: PageCrop? = initial.perspective,
    val rectifiedEstimate: CurvatureEstimate? = initial.straightenedEstimate,
) {
    val hasEdit: Boolean get() = (straighten && perspective != null) || (enabled && this.field?.isIdentity() == false)
}
internal data class CurvaturePreview(val before:BookPreviewImage,val after:BookPreviewImage)
internal data class CurvatureReplacement(val original:DocumentPage,val derived:BookDerivedPage)

/** Reuses M11's bounded snapshot/EXIF/decode/JPEG/staging implementation, with a separate store.
 * Drafts always sample the captured original; preview JPEGs are never input to Apply.
 */
internal class CurvatureProcessor(private val context:Context,maximumStagingBytes:Long=192L*1024*1024) {
    private val store=BookSplitProcessor(context,maximumStagingBytes,"page-curvature",24L*1024*1024)
    suspend fun begin()=store.begin()
    suspend fun finish(work:File,keep:Set<String> = emptySet())=store.finish(work,keep)
    suspend fun analyze(work:File,page:DocumentPage):CurvatureAnalysis=withContext(Dispatchers.IO){
        var file:File?=null
        try {
            val pair=store.snapshot(work,page);file=pair.first
            val bitmap=store.decode(pair.first,page,0,true)
            try {
                val job = coroutineContext
                val original = analysisImage(bitmap)
                val estimate = CurvatureEstimator.estimate(original) { job.ensureActive() }
                val perspective = ImportCropDetector.detect(original.width, original.height, original.pixels) { job.ensureActive() }
                    ?.takeUnless(PageCrop::boundsCoverFrame)
                val straightened = perspective?.let { crop -> estimateRectified(pair.first, page, crop) }
                CurvatureAnalysis(pair.second, estimate, true, perspective, straightened)
            } finally {
                bitmap.recycle()
            }
        }catch(e:CancellationException){throw e}
        catch(_:OutOfMemoryError){CurvatureAnalysis(null,CurvatureEstimate(CurvatureStatus.UNSUPPORTED),false)}
        catch(_:Exception){CurvatureAnalysis(null,CurvatureEstimate(CurvatureStatus.UNSUPPORTED),false)}
        finally{file?.delete()}
    }
    suspend fun preview(work:File,review:CurvatureReview):CurvaturePreview=withContext(Dispatchers.IO){
        clear(work,"preview-");val pair=store.snapshot(work,review.page);var completed=false
        try{
            check(pair.second==review.initial.digest);val image=store.decode(pair.first,review.page,0,true)
            try{
                val beforeBitmap=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
                val before=try{Canvas(beforeBitmap).apply{drawColor(Color.WHITE);drawBitmap(image,0f,0f,null)};store.writeBitmap(work,"preview-${UUID.randomUUID()}.jpg",beforeBitmap)}finally{beforeBitmap.recycle()}
                val after=if(review.hasEdit){val rendered=renderReview(image,review)
                    try{store.writeBitmap(work,"preview-${UUID.randomUUID()}.jpg",rendered)}finally{rendered.recycle()}}
                    else before
                CurvaturePreview(before,after).also{completed=true}
            }finally{image.recycle()}
        }finally{pair.first.delete();if(!completed)clear(work,"preview-")}
    }
    suspend fun prepare(work:File,review:CurvatureReview,progress:suspend(Int,Int)->Unit={_,_->}):CurvatureReplacement=withContext(Dispatchers.IO){
        check(review.hasEdit);val pair=store.snapshot(work,review.page);var completed=false
        try{
            check(pair.second==review.initial.digest);val image=store.decode(pair.first,review.page,0,false)
            try{
                val corrected=renderReview(image,review,progress)
                try{
                    val output=store.writeBitmap(work,"page-${UUID.randomUUID()}.jpg",corrected)
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",output.file)
                    check(output.file.isFile&&output.file.length()==output.metadata.sourceByteCount)
                    CurvatureReplacement(review.page,BookDerivedPage(DocumentResource(uri.toString(),DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                        OwnedTemporaryFile(output.file.path,work.path)),output.metadata)).also{completed=true}
                }finally{corrected.recycle()}
            }finally{image.recycle()}
        }finally{pair.first.delete();if(!completed)clear(work,"page-")}
    }
    suspend fun estimateReviewedCorners(work: File, review: CurvatureReview, crop: PageCrop): CurvatureEstimate =
        withContext(Dispatchers.IO) {
            val (source, digest) = store.snapshot(work, review.page)
            try {
                check(digest == review.initial.digest)
                estimateRectified(source, review.page, crop)
            } finally {
                source.delete()
            }
        }

    private suspend fun estimateRectified(source: File, page: DocumentPage, crop: PageCrop): CurvatureEstimate {
        val job = coroutineContext
        // Use the sharper bounded image because rectification can blur thin text in a thumbnail.
        val detailed = store.decode(source, page, 0, analysis = true, allowSampledSource = true)
        try {
            val upright = rectifyCrop(detailed, crop)
            try {
                return CurvatureEstimator.estimate(analysisImage(upright)) { job.ensureActive() }
            } finally {
                upright.recycle()
            }
        } finally {
            detailed.recycle()
        }
    }

    private fun analysisImage(bitmap: Bitmap): CurvatureImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return CurvatureImage(bitmap.width, bitmap.height, pixels)
    }

    private suspend fun renderReview(
        source: Bitmap,
        review: CurvatureReview,
        progress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Bitmap {
        val crop = review.perspective?.takeIf { review.straighten }
        val field = review.field?.takeIf { review.enabled && !it.isIdentity() }
        if (crop == null) return render(source, requireNotNull(field), progress)
        coroutineContext.ensureActive()
        val straightened = rectifyCrop(source, crop)
        if (field == null) return straightened
        try {
            return render(straightened, field, progress)
        } finally {
            straightened.recycle()
        }
    }

    /** Two column buffers support bilinear horizontal stretch without full-resolution pixel arrays. */
    internal suspend fun render(source:Bitmap,field:CurvatureField,progress:suspend(Int,Int)->Unit={_,_->}):Bitmap=withContext(Dispatchers.IO){
        val size=field.dimensions(source.width,source.height);val output=Bitmap.createBitmap(size.first,size.second,Bitmap.Config.ARGB_8888);var complete=false
        try{
            val column=IntArray(source.height)
            val adjacent=IntArray(source.height)
            val rendered=IntArray(size.second)
            val bottom=source.height-1
            val right=source.width-1
            val start=field.minimumY*bottom
            for(x in 0 until source.width){
                coroutineContext.ensureActive()
                val xn=field.sourceX(x.toFloat()/right)
                val sourceX=if(field.horizontalStretch==0f)x.toFloat() else xn*right
                val left=floor(sourceX).toInt()
                val weight=sourceX-left
                source.getPixels(column,0,1,left,0,1,source.height)
                val other=if(weight>0f&&left<right){
                    source.getPixels(adjacent,0,1,left+1,0,1,source.height)
                    adjacent
                }else column
                val a=field.offset(xn)*bottom
                val b=field.scale(xn)
                for(y in rendered.indices){
                    if(y%1024==0)coroutineContext.ensureActive();val sy=a+b*(start+y)
                    rendered[y]=if(sy<0f||sy>bottom)Color.WHITE else{
                        val top=floor(sy).toInt();val next=(top+1).coerceAtMost(bottom)
                        CurvatureSampling.bilinear(column[top],other[top],column[next],other[next],weight,sy-top)
                    }
                }
                output.setPixels(rendered,0,1,x,0,1,size.second)
                if(x%32==0||x==source.width-1)progress(x+1,source.width)
            }
            coroutineContext.ensureActive();complete=true;output
        }finally{if(!complete)output.recycle()}
    }
    private fun clear(work:File,prefix:String){work.listFiles()?.filter{it.isFile&&it.name.startsWith(prefix)}?.forEach{it.delete()}}
}
