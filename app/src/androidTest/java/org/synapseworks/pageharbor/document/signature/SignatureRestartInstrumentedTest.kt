package org.synapseworks.pageharbor.document.signature

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.annotation.*

/** Run the two methods in distinct instrumentation processes, with force-stop between them. */
class SignatureRestartInstrumentedTest {
 private val context=InstrumentationRegistry.getInstrumentation().targetContext
 private fun root()=File(context.noBackupFilesDir,"m15-synthetic-restart")
 @Test fun writeInFirstProcess()=runBlocking(Dispatchers.IO){
  check(context.packageName.endsWith(".phase2test"));val root=root();root.deleteRecursively()
  val saved=SignatureTemplateStore(root).save("Synthetic restart",SignatureFixtures.ink()).single()
  File(root,"pid").writeText(Process.myPid().toString());File(root,"id").writeText(saved.id)
  assertTrue(File(root,"templates.rmesignatures").isFile)
 }
 @Test fun reuseAfterProcessRestartAndDelete()=runBlocking(Dispatchers.IO){
  check(context.packageName.endsWith(".phase2test"));val root=root()
  try{
   assertNotEquals(File(root,"pid").readText().toInt(),Process.myPid())
   val store=SignatureTemplateStore(root);val saved=store.list().single()
   assertEquals(File(root,"id").readText(),saved.id);assertEquals(SignatureFixtures.ink(),saved.ink)
   val mark=AnnotationMark.create("placed",AnnotationTool.SIGNATURE,AnnotationBounds(.1f,.2f,.7f,.6f),signature=saved.ink)
   val captured=AnnotationLayer.of(listOf(mark));val bytes=captured.encode()
   store.delete(saved.id);assertTrue(store.list().isEmpty());assertEquals(saved.ink,AnnotationLayer.decode(bytes).marks.single().signature)
   assertTrue(root.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath+File.separator))
   assertEquals(0,context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
  }finally{root.deleteRecursively()}
 }
}
