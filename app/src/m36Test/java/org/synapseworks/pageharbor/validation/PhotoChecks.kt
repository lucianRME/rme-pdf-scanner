package org.synapseworks.pageharbor.validation
import android.app.*
import kotlinx.coroutines.async
import android.os.*
import android.net.Uri
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import org.json.*
import org.synapseworks.pageharbor.photo.*
import org.synapseworks.pageharbor.workflow.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.document.session.*

/** Synthetic-only isolated package controls. Test-provider callbacks supplement, never replace genuine SAF UI. */
class PhotoChecks:Instrumentation(){
 override fun onCreate(args:Bundle?){super.onCreate(args);start()}
 override fun onStart(){val report=Bundle();val root=File(targetContext.cacheDir,"m39-check-"+UUID.randomUUID());var vm:PhotoViewModel?=null
 try{check(targetContext.packageName.endsWith(".m36packsmoke")||targetContext.packageName.endsWith(".m36packr8"));check(root.mkdir());val evidence=File(targetContext.cacheDir,"m39-evidence").apply{mkdirs()}
  val corpus=JSONObject(targetContext.assets.open("m39-corpus.json").bufferedReader().use{it.readText()}).getJSONArray("fixtures");val metrics=JSONArray()
  for(i in 0 until corpus.length()){val row=corpus.getJSONObject(i);val name=row.getString("name");val w=row.getInt("width");val h=row.getInt("height")
   fun bytes(s:String)=targetContext.assets.open("m39/$name-$s").use{it.readBytes()}
   fun pixels(s:String):IntArray{val b=ByteBuffer.wrap(bytes("$s.argb"));return IntArray(b.remaining()/4){b.int}}
   val src=pixels("input");val ref=pixels("reference");val mask=bytes("mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=row.getJSONObject("settings");val started=SystemClock.elapsedRealtimeNanos();val m=JSONObject().put("name",name)
   try{val out=PhotoEngine.process(w,h,src,PhotoRecipe(r.getInt("noise"),r.getInt("contrast"),r.getInt("red"),r.getInt("green"),r.getInt("blue")),mask,ScratchDirection.valueOf(row.getString("scratchOrientation")));check(row.getString("expected")=="READY")
    fun mae(a:IntArray,b:IntArray,select:(Int)->Boolean={true}):Double{var sum=0L;var n=0L;for(p in a.indices)if(select(p))for(s in intArrayOf(16,8,0)){sum+=kotlin.math.abs(((a[p] ushr s)and 255)-((b[p] ushr s)and 255));n++};return sum.toDouble()/n};m.put("beforeMae",mae(src,ref)).put("afterMae",mae(out,ref)).put("outsideMaskChanged",out.indices.count{!mask[it]&&out[it]!=src[it]});if(mask.any{it})m.put("maskedMae",mae(out,ref){mask[it]});val png=PhotoProcessor(targetContext).png(w,h,out){};File(evidence,"$name.png").writeBytes(png);m.put("status","READY")
   }catch(e:IllegalArgumentException){check(row.getString("expected")=="REFUSED");m.put("status","REFUSED")};m.put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-started)/1e6);metrics.put(m)
  };File(evidence,"quality.json").writeText(metrics.toString(2));report.putInt("frozenCases",corpus.length())
  val pssBefore=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}.totalPss;val large=IntArray(1024*1024){i->0xff000000.toInt() or((40+i%160) shl 16)or(90 shl 8)or 150};val started=SystemClock.elapsedRealtime();PhotoEngine.process(1024,1024,large,PhotoRecipe(noise=2));val time=SystemClock.elapsedRealtime()-started;check(time<8000);report.putLong("oneMegapixelNoise2Ms",time);val pssAfter=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}.totalPss;report.putInt("sampledPssBeforeKiB",pssBefore);report.putInt("sampledPssAfterKiB",pssAfter);report.putString("memoryScope","Two process PSS samples; not a peak or heap reclamation proof; includes test corpus and VM overhead")
  var checks=0;val cancelStart=SystemClock.elapsedRealtimeNanos();try{PhotoEngine.process(1024,1024,large,PhotoRecipe(noise=2)){if(++checks==10)throw CancellationException()};error("NOT_CANCELLED")}catch(_:CancellationException){};report.putDouble("tenRowCooperativeCancellationMs",(SystemClock.elapsedRealtimeNanos()-cancelStart)/1e6)
  val source=File(root,"source.png").apply{targetContext.assets.open("m39/neutral-input.png").use{input->outputStream().use{input.copyTo(it)}}};val original=source.readBytes();val page=DocumentPage(DocumentPageId(99),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,contentType="image/png");var current=true;var releases=0
  fun waitIdle(){repeat(500){if(vm!!.busy)Thread.sleep(20)};check(!vm!!.busy)}
  fun open(){runOnMainSync{vm=PhotoViewModel(targetContext.applicationContext as Application);vm!!.open(page,listOf(page.source),{current},{releases++})};waitIdle();check(vm!!.capture!=null)}
  if (Build.VERSION.SDK_INT < 34) {
   runOnMainSync { vm=PhotoViewModel(targetContext.applicationContext as Application);vm!!.open(page,listOf(page.source),{current},{releases++}) }
   waitIdle();check(vm!!.capture==null && vm!!.message.contains("Android 14+"));check(source.readBytes().contentEquals(original))
   runOnMainSync { vm!!.close() };check(releases==1)
   report.putString("status","PASS");report.putString("scope","14 frozen engine fixtures and visible unsupported-platform refusal. The restoration consumer requires Android 14+.")
   return
  }
  open();runOnMainSync{vm!!.preview()};waitIdle();check(vm!!.ready&&!vm!!.before);val c=vm!!.capture!!;check(vm!!.result!!.contentEquals(c.pixels));runOnMainSync{vm!!.settings(PhotoRecipe(contrast=5));check(vm!!.before&&!vm!!.ready&&!vm!!.reviewed&&vm!!.result==null);vm!!.reset();vm!!.preview();check(vm!!.before)};waitIdle();check(vm!!.ready&&!vm!!.before);runOnMainSync{vm!!.reviewed=true}
  val uri=Uri.parse("content://"+targetContext.packageName+".m39-control/owned-control");val target=File(root,"Restored-photo.png").apply{createNewFile()};PhotoControlProvider.file=target
  runOnMainSync{check(vm!!.beginSave()!=null);val old=vm!!.saveTicket;vm!!.destination(old,null);check(vm!!.beginSave()!=null);vm!!.destination(old,uri);check(vm!!.picker&&target.length()==0L);val before=vm!!.recipe;vm!!.settings(PhotoRecipe(noise=2));check(vm!!.recipe==before);vm!!.destination(vm!!.saveTicket,uri)};waitIdle();check(vm!!.message.startsWith("Separate reviewed PNG saved"));check(target.readBytes().take(4)==listOf(137.toByte(),80.toByte(),78.toByte(),71.toByte()));target.copyTo(File(evidence,"neutral-reviewed.png"),true)
  // Non-empty destination and same-file alias cannot be opened for writing.
  PhotoControlProvider.file=source;runOnMainSync{check(vm!!.beginSave()!=null);vm!!.destination(vm!!.saveTicket,uri)};waitIdle();check(vm!!.message.startsWith("Save refused")&&source.readBytes().contentEquals(original))
  PhotoControlProvider.file=target;target.writeBytes(byteArrayOf());val oldToken=vm!!.interactionToken;runOnMainSync{check(vm!!.beginSave()!=null);val ticket=vm!!.saveTicket;vm!!.close();vm!!.destination(ticket,uri);vm!!.interact(oldToken){vm!!.preview()}};check(target.length()==0L&&releases==1)
  open();val work=vm!!.capture!!.workspace;runOnMainSync{vm!!.preview();vm!!.close()};Thread.sleep(100);check(releases==2&&!work.exists())
  open();runOnMainSync{current=false;vm!!.changed()};check(!vm!!.visible&&releases==3)
  current=true;open();runOnMainSync{vm!!.preview()};waitIdle();source.writeBytes(original+byteArrayOf(0));target.writeBytes(byteArrayOf());PhotoControlProvider.file=target;runOnMainSync{vm!!.reviewed=true;check(vm!!.beginSave()!=null);vm!!.destination(vm!!.saveTicket,uri)};waitIdle();check(vm!!.message.startsWith("Save refused")&&target.length()==0L);source.writeBytes(original);runOnMainSync{vm!!.close()};check(releases==4)
  // Lock/background invalidates late UI actions and releases retained sources.
  open();runOnMainSync{vm!!.background(false);vm!!.interact(vm!!.interactionToken-1){vm!!.preview()}};check(!vm!!.visible&&releases==5&&source.readBytes().contentEquals(original))
  val guard=workflowSourceGuard(targetContext,Uri.fromFile(source));android.os.ParcelFileDescriptor.open(source,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use{fd->check(!workflowDifferentSources(targetContext,fd,Uri.fromFile(source),listOf(guard)));check(!workflowDifferentSources(targetContext,fd,uri,listOf(guard)))}
  // A blocked destination result retains the lease until IO returns; Close prevents any bytes.
  open();runOnMainSync{vm!!.preview()};waitIdle();target.writeBytes(byteArrayOf());PhotoControlProvider.file=target;PhotoControlProvider.entered=CountDownLatch(1);PhotoControlProvider.release=CountDownLatch(1)
  runOnMainSync{vm!!.reviewed=true;check(vm!!.beginSave()!=null);vm!!.destination(vm!!.saveTicket,uri)};check(PhotoControlProvider.entered!!.await(2,java.util.concurrent.TimeUnit.SECONDS));runOnMainSync{vm!!.close()};check(releases==5&&target.length()==0L);val closeStart=SystemClock.elapsedRealtime();PhotoControlProvider.release!!.countDown();repeat(100){if(releases<6)Thread.sleep(20)};check(releases==6&&target.length()==0L);report.putLong("blockedWriteLeaseReleaseAfterIoMs",SystemClock.elapsedRealtime()-closeStart);PhotoControlProvider.entered=null;PhotoControlProvider.release=null
  // Independent admission controls: malformed, alpha, oversize and wide-gamut sources.
  val processor=PhotoProcessor(targetContext)
  fun reject(file:File){var accepted:PhotoCapture?=null;try{kotlinx.coroutines.runBlocking{accepted=processor.capture(page.copy(source=DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL))){}};error("UNEXPECTED_ADMISSION")}catch(_:IllegalArgumentException){}finally{accepted?.let(processor::finish)}}
  reject(File(root,"malformed.png").apply{writeBytes(byteArrayOf(1,2,3))})
  fun png(name:String,w:Int,h:Int,wide:Boolean=false,alpha:Boolean=false):File{val b=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888,alpha,android.graphics.ColorSpace.get(if(wide)android.graphics.ColorSpace.Named.DISPLAY_P3 else android.graphics.ColorSpace.Named.SRGB));try{b.eraseColor(if(alpha)0x00445566 else 0xff445566.toInt());return File(root,name).apply{outputStream().use{check(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}}finally{b.recycle()}}
  reject(png("alpha.png",8,8,alpha=true));reject(png("oversize.png",5000,2));reject(png("wide.png",8,8,wide=true));report.putBoolean("malformedAlphaOversizeWideGamutRefused",true)
  reject(File(root,"high.png").apply{targetContext.assets.open("m39-16bit-control.png").use{input->outputStream().use{input.copyTo(it)}}});report.putBoolean("highPrecisionPngRefused",true)
  // Independent constant-background expectation catches duplicate rotation/adjustments or filtering after overlays.
  val largeSource=android.graphics.Bitmap.createBitmap(2048,1024,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(0xff505050.toInt())};val largeFile=File(root,"large.png");try{largeFile.outputStream().use{check(largeSource.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}finally{largeSource.recycle()}
  val layer=AnnotationLayer.of(listOf(AnnotationMark.create("rectangle",AnnotationTool.RECTANGLE,AnnotationBounds(.1f,.1f,.4f,.4f),AnnotationStyle(rgb=0xff0000)),AnnotationMark.create("handwriting",AnnotationTool.SIGNATURE,AnnotationBounds(.6f,.6f,.9f,.9f),AnnotationStyle(rgb=0x0000ff),signature=HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.1f,.2f),SignaturePoint(.8f,.8f))))))))
  val sourceBefore=largeFile.readBytes();val capturePss=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}.totalPss;val captureStart=SystemClock.elapsedRealtime();val composed=kotlinx.coroutines.runBlocking{processor.capture(page.copy(source=DocumentResource(Uri.fromFile(largeFile).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),rotation=DocumentPageRotation.DEGREES_90,filter=DocumentFilter.GRAYSCALE,adjustments=ManualImageAdjustments(brightness=10),annotations=layer)){}}
  try{check(composed.sourceWidth==2048&&composed.sourceHeight==1024&&composed.width==512&&composed.height==1024);check(composed.pixels[0]==0xff6a6a6a.toInt());check(composed.pixels.any{(it ushr 16 and 255)>200&&(it ushr 8 and 255)<80&&(it and 255)<80});check(composed.pixels.any{(it and 255)>200&&(it ushr 16 and 255)<80&&(it ushr 8 and 255)<80});val neutral=PhotoEngine.process(composed.width,composed.height,composed.pixels,PhotoRecipe());check(neutral.contentEquals(composed.pixels));File(evidence,"appearance.png").writeBytes(processor.png(composed.width,composed.height,neutral){});report.putLong("twoMegapixelSourceCaptureMs",SystemClock.elapsedRealtime()-captureStart);report.putInt("capturePssBeforeKiB",capturePss);report.putInt("capturePssAfterKiB",Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}.totalPss);check(largeFile.readBytes().contentEquals(sourceBefore));report.putBoolean("downsampleRotationAdjustmentsAnnotationSignatureOnce",true)}finally{processor.finish(composed)}
  kotlinx.coroutines.runBlocking{
   val first=processor.capture(page){};val entered=kotlinx.coroutines.CompletableDeferred<Unit>();val pending=async(kotlinx.coroutines.Dispatchers.IO){entered.complete(Unit);processor.capture(page){}}
   entered.await();kotlinx.coroutines.delay(50);check(!pending.isCompleted);processor.finish(first);val second=kotlinx.coroutines.withTimeout(2000){pending.await()};processor.finish(first)
   val nextEntered=kotlinx.coroutines.CompletableDeferred<Unit>();val next=async(kotlinx.coroutines.Dispatchers.IO){nextEntered.complete(Unit);processor.capture(page){}};nextEntered.await();kotlinx.coroutines.delay(50);check(!next.isCompleted);next.cancel();next.join();processor.finish(second)
   val after=processor.capture(page){};processor.finish(after)
  };report.putBoolean("workspaceSerialCancellationAndDuplicateCleanup",true)
  report.putString("status","PASS");report.putString("scope","14 frozen decisions; neutral pixel identity; bounded1MP processing/cancellation; immutable export; cancelled/stale tickets; source replacement/nonempty alias refusal; stale source/background; lease/workspace cleanup")
 }catch(t:Throwable){report.putString("status","FAIL");report.putString("failureClass",t.javaClass.simpleName);report.putString("failureLine",t.stackTrace.firstOrNull{it.className.endsWith("PhotoChecks")}?.lineNumber?.toString())}finally{runOnMainSync{vm?.close()};PhotoControlProvider.release?.countDown();PhotoControlProvider.file=null;PhotoControlProvider.entered=null;PhotoControlProvider.release=null;root.deleteRecursively();finish(Activity.RESULT_OK,report)}
 }
}
