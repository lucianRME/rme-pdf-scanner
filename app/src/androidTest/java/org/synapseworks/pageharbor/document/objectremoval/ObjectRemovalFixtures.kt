package org.synapseworks.pageharbor.document.objectremoval

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.synapseworks.pageharbor.document.session.*

internal object ObjectRemovalFixtures {
 fun source(root:File,id:Long=1,profile:String="dark-spot"):DocumentPage {
  val file=File(root,"m28-${UUID.randomUUID()}.png");file.writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("m28/$profile-512-source.png").use{it.readBytes()})
  return DocumentPage(DocumentPageId(id),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,"image/png",DocumentImageMetadata(file.length(),512,384))
 }
 fun mask(profile:String="dark-spot"):InpaintMask {
  val bitmap=InstrumentationRegistry.getInstrumentation().context.assets.open("m28/$profile-512-mask.png").use{BitmapFactory.decodeStream(it)}!!
  try{val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);return InpaintMask(bitmap.width,bitmap.height,BooleanArray(pixels.size){pixels[it]and 255>0})}finally{bitmap.recycle()}
 }
 class Prepared(val processor:ObjectRemovalProcessor,val work:File,val analysis:RemovalAnalysis,val output:RemovalPrepared,val before:DocumentSession,val after:DocumentSession):AutoCloseable{
  override fun close(){runBlocking{processor.finish(work)}}
 }
 suspend fun prepare(context:Context,session:DocumentSession,pageIndex:Int=0,mask:InpaintMask=mask()):Prepared {
  val processor=ObjectRemovalProcessor(context);val work=processor.begin();var complete=false
  try{val page=session.pages[pageIndex];val analysis=processor.open(work,page);val proposed=processor.preview(work,page,analysis,mask);check(proposed.status==RemovalStatus.READY)
   val result=requireNotNull(proposed.prepared);processor.verify(work,analysis,result);val publication=requireNotNull(removalPublication(session,result));return Prepared(processor,work,analysis,result,session,publication.after).also{complete=true}
  }finally{if(!complete)processor.finish(work)}
 }
}
