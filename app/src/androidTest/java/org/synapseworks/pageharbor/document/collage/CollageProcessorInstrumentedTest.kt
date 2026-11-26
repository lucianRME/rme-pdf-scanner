package org.synapseworks.pageharbor.document.collage

import android.app.Application
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*

class CollageProcessorInstrumentedTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
 private lateinit var root:File
 @Before fun setup(){check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/collage-${UUID.randomUUID()}").apply{mkdirs()}}
 @After fun cleanup(){root.deleteRecursively()}
 private fun source(id:Long,w:Int,h:Int,color:Int,png:Boolean=false):DocumentPage{
  val file=File(root,"$id.${if(png)"png" else "jpg"}");val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
  try{b.eraseColor(color);file.outputStream().use{assertTrue(b.compress(if(png)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,100,it))}}finally{b.recycle()}
  return DocumentPage(DocumentPageId(id),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,
   if(png)"image/png" else "image/jpeg",DocumentImageMetadata(file.length(),w,h))
 }
 private fun pixel(file:File,x:Int,y:Int):Int{val b=BitmapFactory.decodeFile(file.path);try{return b.getPixel(x,y)}finally{b.recycle()}}
 private fun red(c:Int){assertTrue("red=$c",Color.red(c)>235&&Color.green(c)<20&&Color.blue(c)<20)}
 @Test fun independentSixGridOddTailRepeatedImagesFitCropAndWhiteMargins()=runBlocking {
  val a=source(1,400,100,Color.RED);val b=source(2,100,400,Color.GREEN);val original=File(Uri.parse(a.source.reference).path!!).readBytes()
  val processor=CollageProcessor(app);val work=processor.begin()
  try{
   val settings=CollageSettings(6,CardCopyPageSize.LETTER,marginMm=12.7f,gapMm=6.35f)
   val draft=CollageDraft(List(7){CollageTile("tile-$it",if(it%2==0)1 else 2,if(it==1)CollageFit.CROP else CollageFit.FIT)},settings)
   val preview=processor.preview(work,draft,DocumentSession(listOf(a,b)));assertEquals(2,preview.size);assertTrue(preview.all{maxOf(it.metadata.width!!,it.metadata.height!!)<=1000})
   val output=processor.prepare(work,draft,DocumentSession(listOf(a,b)));assertEquals(2,output.pages.size)
   val files=output.pages.map{File(it.source.ownedTemporaryFile!!.path)}
   assertEquals(1530,output.pages.first().metadata.width);assertEquals(1980,output.pages.first().metadata.height)
   // First cell90..742.5 x90..660. Red fit652.5x163.125 centered y293.4..456.6.
   red(pixel(files[0],400,375));assertTrue(Color.red(pixel(files[0],400,150))>245&&Color.green(pixel(files[0],400,150))>245)
   assertTrue(Color.green(pixel(files[0],800,110))>235&&Color.red(pixel(files[0],800,110))<20)
   assertEquals(Color.WHITE,pixel(files[0],760,375));red(pixel(files[1],400,375));assertEquals(Color.WHITE,pixel(files[1],1000,1000))
   assertArrayEquals(original,File(Uri.parse(a.source.reference).path!!).readBytes())
  }finally{processor.finish(work)}
  assertFalse(work.directory.exists())
 }
 @Test fun fullAppearanceAppliesManualOnceAndKeepsSignatureSpacingAndTransparentWhite()=runBlocking {
  val gray=source(1,800,600,Color.rgb(64,64,64)).copy(adjustments=ManualImageAdjustments(20),filter=DocumentFilter.ORIGINAL,
   annotations=AnnotationLayer.of(listOf(SignatureFixtures.mark())))
  val transparent=source(2,800,600,Color.TRANSPARENT,true)
  val processor=CollageProcessor(app);val work=processor.begin()
  try{
   val draft=CollageDraft(listOf(CollageTile("a",1),CollageTile("b",2)),CollageSettings(2,CardCopyPageSize.LETTER,marginMm=0f,gapMm=0f))
   processor.preview(work,draft,DocumentSession(listOf(gray,transparent)))
   val appearance=work.appearances[1]!!;assertEquals(800,appearance.size.width);assertEquals(600,appearance.size.height)
   assertEquals(115f,Color.red(pixel(appearance.file,400,80)).toFloat(),3f)
   val output=processor.prepare(work,draft,DocumentSession(listOf(gray,transparent)));val file=File(output.pages.single().source.ownedTemporaryFile!!.path)
   // dot488,168 maps to x105+805.2,y277.2; L x128,y240 ->316.2,396. White gap320,168 ->633,277.
   assertTrue(Color.red(pixel(file,910,277))<35);assertTrue(Color.red(pixel(file,316,396))<35)
   assertEquals(115f,Color.red(pixel(file,633,277)).toFloat(),5f)
   assertEquals(Color.WHITE,pixel(file,800,1500));assertEquals(Color.WHITE,pixel(file,20,200))
   assertTrue(collageSession(DocumentSession(listOf(gray,transparent)),output)!!.pages.last().annotations.isEmpty)
  }finally{processor.finish(work)}
 }
 @Test fun exifSessionAndTileQuarterTurnsComposeInOrderWithoutStretch()=runBlocking {
  val page=source(1,400,200,Color.WHITE);val file=File(Uri.parse(page.source.reference).path!!)
  val bitmap=BitmapFactory.decodeFile(file.path).copy(Bitmap.Config.ARGB_8888,true)
  Canvas(bitmap).drawRect(0f,0f,200f,200f,Paint().apply{color=Color.RED})
  file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
  ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
  val captured=page.copy(rotation=DocumentPageRotation.DEGREES_90,imageMetadata=page.imageMetadata.copy(sourceByteCount=file.length()))
  val processor=CollageProcessor(app);val work=processor.begin()
  try{
   val draft=CollageDraft(listOf(CollageTile("a",1,turns=1)),CollageSettings(4,CardCopyPageSize.LETTER,marginMm=0f,gapMm=0f))
   val out=processor.prepare(work,draft,DocumentSession(listOf(captured)))
   assertEquals(CollageSize(400,200),work.appearances[1]!!.size)
   val f=File(out.pages.single().source.ownedTemporaryFile!!.path)
   // Total270°: original left red half becomes bottom of a198x396pt image, x54..252.
   red(pixel(f,380,800));assertTrue(Color.green(pixel(f,380,150))>240)
   assertEquals(Color.WHITE,pixel(f,40,800))
  }finally{processor.finish(work)}
 }
 @Test fun highFrequencyFinalSamplesFullAppearanceRatherThanUpscalingThePreview()=runBlocking {
  val page=source(9,1800,900,Color.WHITE,true);val file=File(Uri.parse(page.source.reference).path!!)
  val bitmap=Bitmap.createBitmap(1800,900,Bitmap.Config.ARGB_8888);val pixels=IntArray(1800*900){if(it%1800%2==0)Color.BLACK else Color.WHITE};bitmap.setPixels(pixels,0,1800,0,0,1800,900)
  file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
  val captured=page.copy(imageMetadata=page.imageMetadata.copy(sourceByteCount=file.length()))
  val processor=CollageProcessor(app);val work=processor.begin()
  fun deviation(file:File,y:Int):Double {val b=BitmapFactory.decodeFile(file.path);try{val values=(200..500).map{Color.red(b.getPixel(it,y)).toDouble()};val mean=values.average();return kotlin.math.sqrt(values.map{(it-mean)*(it-mean)}.average())}finally{b.recycle()}}
  try{
   val draft=CollageDraft(listOf(CollageTile("a",9)),CollageSettings(2,CardCopyPageSize.LETTER,marginMm=0f,gapMm=0f));val session=DocumentSession(listOf(captured))
   val preview=processor.preview(work,draft,session).single();val low=deviation(preview.file,150)
   val output=processor.prepare(work,draft,session);val high=deviation(File(output.pages.single().source.ownedTemporaryFile!!.path),300)
   assertTrue("Final contrast $high",high>25.0);assertTrue("Sampled preview contrast $low",low<15.0)
  }finally{processor.finish(work)}
 }
 @Test fun changedMissingEvictedSourceAndStorageAdmissionRejectWholeAndClean()=runBlocking {
  val p=source(1,400,200,Color.RED);val file=File(Uri.parse(p.source.reference).path!!);val session=DocumentSession(listOf(p));val draft=CollageDraft(listOf(CollageTile("a",1)))
  val processor=CollageProcessor(app);val work=processor.begin()
  try{
   processor.preview(work,draft,session);work.appearances[1]!!.file.delete() // appearance cache eviction must rebuild from verified original.
   processor.prepare(work,draft,session);assertTrue(work.appearances[1]!!.file.isFile)
   file.appendBytes(byteArrayOf(1));try{processor.prepare(work,draft,session);fail("Changed input accepted")}catch(_:IllegalStateException){}
   assertTrue(work.directory.listFiles().orEmpty().none{it.name.startsWith("page-")})
   file.delete();try{processor.prepare(work,draft,session);fail()}catch(_:Exception){}
  }finally{processor.finish(work)}
  val valid=source(3,400,200,Color.RED);val validSession=DocumentSession(listOf(valid));val validDraft=CollageDraft(listOf(CollageTile("v",3)))
  val tiny=CollageProcessor(app,maximumBytes=10_000);val w=tiny.begin()
  try{try{tiny.prepare(w,validDraft,validSession);fail()}catch(_:Exception){};assertTrue(w.appearances.isNotEmpty());assertTrue(w.directory.listFiles().orEmpty().none{it.name.startsWith("page-")})}finally{tiny.finish(w)}
  val noSpace=CollageProcessor(app,minimumFree=Long.MAX_VALUE);val w2=noSpace.begin()
  try{try{noSpace.preview(w2,validDraft,validSession);fail()}catch(_:Exception){}}finally{noSpace.finish(w2)}
  assertFalse(w2.directory.exists())
 }
 @Test fun cancellationDeletesPartialWritesAndInvalidSecondTileCannotYieldPartialDocument()=runBlocking {
  val a=source(1,800,600,Color.RED);val b=source(2,800,600,Color.BLUE);File(Uri.parse(b.source.reference).path!!).writeBytes(byteArrayOf(0))
  val processor=CollageProcessor(app);val work=processor.begin()
  try{
   try{processor.prepare(work,CollageDraft(listOf(CollageTile("a",1),CollageTile("b",2))),DocumentSession(listOf(a,b)));fail()}catch(_:Exception){}
   assertTrue(work.directory.listFiles().orEmpty().none{it.name.startsWith("page-")})
   val job=launch{processor.prepare(work,CollageDraft(List(24){CollageTile("t-$it",1)}),DocumentSession(listOf(a)))};yield();job.cancelAndJoin()
  }finally{processor.finish(work)}
  assertFalse(work.directory.exists())
 }
}
