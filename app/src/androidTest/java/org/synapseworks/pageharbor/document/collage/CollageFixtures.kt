package org.synapseworks.pageharbor.document.collage

import android.content.Context
import org.synapseworks.pageharbor.document.session.*
import kotlinx.coroutines.runBlocking

internal object CollageFixtures {
 class Prepared(val processor:CollageProcessor,val work:CollageWorkspace,val before:DocumentSession,val output:CollageOutput,val after:DocumentSession){fun close()=runBlocking{processor.finish(work)}}
 suspend fun prepare(context:Context,session:DocumentSession):Prepared {
  val processor=CollageProcessor(context);val work=processor.begin()
  try{
   val draft=CollageDraft(List(7){CollageTile("tile-$it",session.pages[it%session.pages.size].id.value)},CollageSettings(slots=6))
   processor.preview(work,draft,session);val output=processor.prepare(work,draft,session)
   return Prepared(processor,work,session,output,requireNotNull(collageSession(session,output)))
  }catch(e:Throwable){processor.finish(work);throw e}
 }
}
