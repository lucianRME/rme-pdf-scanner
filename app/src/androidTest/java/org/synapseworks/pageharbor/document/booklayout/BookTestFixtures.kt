package org.synapseworks.pageharbor.document.booklayout

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.synapseworks.pageharbor.document.session.*

internal object BookTestFixtures {
    fun cases()=BookSpreadCorpus.read(InstrumentationRegistry.getInstrumentation().context.assets.open("corpus.csv").reader())
    fun source(root:File,index:Int=0,id:Long=10):DocumentPage {
        root.mkdirs();val c=cases()[index];val upright=BookSpreadCorpus.render(c);val raw=BookSpreadCorpus.rotate(upright,c.rawTurns)
        val bitmap=Bitmap.createBitmap(raw.pixels,raw.width,raw.height,Bitmap.Config.ARGB_8888);val file=File(root,"scene-$id-$index.jpg")
        try{file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}}finally{bitmap.recycle()}
        val exif=when(c.rawTurns){1->ExifInterface.ORIENTATION_ROTATE_270;2->ExifInterface.ORIENTATION_ROTATE_180;3->ExifInterface.ORIENTATION_ROTATE_90;else->ExifInterface.ORIENTATION_NORMAL}
        ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,exif.toString());saveAttributes()}
        return DocumentPage(DocumentPageId(id),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,
            imageMetadata=DocumentImageMetadata(file.length(),raw.width,raw.height))
    }
    fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    data class Prepared(val processor:BookSplitProcessor,val work:File,val before:DocumentSession,val replacement:BookPageReplacement,val after:DocumentSession) {
        suspend fun close(){processor.finish(work)}
    }
    suspend fun prepare(context:Context,session:DocumentSession,pageIndex:Int=0,manual:BookSplitLine?=null,order:BookReadingOrder=BookReadingOrder.LEFT_TO_RIGHT):Prepared {
        val processor=BookSplitProcessor(context);val work=processor.begin()
        try {
            val page=session.pages[pageIndex];val a=processor.analyze(work,page)
            check(a.canSplit)
            val review=BookReviewedPage(page,a,line=manual?:a.detection.line,split=true,manual=manual!=null)
            check(processor.preview(work,review,order).halves.size==2)
            val replacement=processor.prepare(work,listOf(review),order).single()
            return Prepared(processor,work,session,replacement,requireNotNull(bookSplitSession(session,listOf(replacement))))
        }catch(e:Throwable){processor.finish(work);throw e}
    }
}
