package org.synapseworks.pageharbor.document.curvature

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.session.*

internal object CurvatureFixtures {
    fun cases()=CurvatureCorpus.scenes(InstrumentationRegistry.getInstrumentation().context.assets.open("m12/corpus.csv"))
    fun source(root:File,index:Int=1,id:Long=10):DocumentPage {
        root.mkdirs();val scene=cases()[index];val upright=CurvatureCorpus.distorted(scene);val turns=scene.turns
        val w=if(turns%2==0)upright.width else upright.height;val h=if(turns%2==0)upright.height else upright.width;val raw=IntArray(w*h)
        for(y in 0 until upright.height)for(x in 0 until upright.width){val p=when(turns){0->x to y;1->upright.height-1-y to x;2->upright.width-1-x to upright.height-1-y;else->y to upright.width-1-x};raw[p.second*w+p.first]=upright.pixels[y*upright.width+x]}
        val bitmap=Bitmap.createBitmap(raw,w,h,Bitmap.Config.ARGB_8888);val file=File(root,"curvature-$id-$index.jpg")
        try{file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}}finally{bitmap.recycle()}
        val orientation=when(turns){1->ExifInterface.ORIENTATION_ROTATE_270;2->ExifInterface.ORIENTATION_ROTATE_180;3->ExifInterface.ORIENTATION_ROTATE_90;else->ExifInterface.ORIENTATION_NORMAL}
        ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
        return DocumentPage(DocumentPageId(id),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,
            imageMetadata=DocumentImageMetadata(file.length(),w,h))
    }
    data class Prepared(val processor:CurvatureProcessor,val work:File,val before:DocumentSession,val replacement:CurvatureReplacement,val after:DocumentSession) {
        suspend fun close(){processor.finish(work)}
    }
    suspend fun prepare(context:Context,session:DocumentSession,pageIndex:Int=0,manual:CurvatureField?=null):Prepared {
        val processor=CurvatureProcessor(context);val work=processor.begin()
        try{val page=session.pages[pageIndex];val analysis=processor.analyze(work,page);check(analysis.canProcess)
            val review=CurvatureReview(page,analysis,field=manual?:analysis.estimate.field,enabled=true,manual=manual!=null)
            check(processor.preview(work,review).after.file.isFile)
            val replacement=processor.prepare(work,review)
            return Prepared(processor,work,session,replacement,requireNotNull(curvatureSession(session,replacement)))
        }catch(e:Throwable){processor.finish(work);throw e}
    }
}
