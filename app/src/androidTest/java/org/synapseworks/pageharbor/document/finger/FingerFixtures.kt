package org.synapseworks.pageharbor.document.finger

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.document.objectremoval.*
import org.synapseworks.pageharbor.document.session.*

internal object FingerFixtures {
 fun source(root:File,id:Long=1,profile:String="medium-left"):DocumentPage {
  val file=File(root,"m29-${UUID.randomUUID()}.png");file.writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("m29/$profile-512-source.png").use{it.readBytes()})
  return DocumentPage(DocumentPageId(id),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,"image/png",DocumentImageMetadata(file.length(),512,384))
 }
 suspend fun prepare(context:Context,session:DocumentSession,pageIndex:Int=0):ObjectRemovalFixtures.Prepared {
  val processor=ObjectRemovalProcessor(context);val work=processor.begin();var complete=false
  try{val page=session.pages[pageIndex];val analysis=processor.open(work,page);val detection=processor.detect(work,page,analysis);check(detection.decision==FingerDecision.PROPOSED)
   val proposed=processor.preview(work,page,analysis,detection.mask,true);check(proposed.status==RemovalStatus.READY);val result=requireNotNull(proposed.prepared);processor.verify(work,analysis,result)
   val publication=requireNotNull(removalPublication(session,result));return ObjectRemovalFixtures.Prepared(processor,work,analysis,result,session,publication.after).also{complete=true}
  }finally{if(!complete)processor.finish(work)}
 }
}
