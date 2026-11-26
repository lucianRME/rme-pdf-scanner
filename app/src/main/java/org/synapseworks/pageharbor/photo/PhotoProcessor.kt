package org.synapseworks.pageharbor.photo
import android.content.Context
import android.graphics.*
import android.os.Build
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.ui.home.applyPreviewRotationAndFilter

internal class PhotoCapture(val workspace:File,val snapshot:File,val digest:ByteArray,val pixels:IntArray,val width:Int,val height:Int,val sourceWidth:Int,val sourceHeight:Int){internal val finished=java.util.concurrent.atomic.AtomicBoolean(false)}
internal class PhotoProcessor(private val context:Context){
 companion object{private val workspacePermit=kotlinx.coroutines.sync.Semaphore(1);private val mutex=Any();private val initialized=mutableSetOf<String>();const val SOURCE_LIMIT=32L*1024*1024;const val PNG_LIMIT=8*1024*1024}
 private val root=File(context.cacheDir,"photo-restoration")
 private fun start():File=synchronized(mutex){check(root.mkdirs()||root.isDirectory);check(!java.nio.file.Files.isSymbolicLink(root.toPath()));if(initialized.add(root.canonicalPath))root.listFiles().orEmpty().filter{runCatching{UUID.fromString(it.name)}.isSuccess&&!java.nio.file.Files.isSymbolicLink(it.toPath())}.forEach{it.deleteRecursively()};check(root.usableSpace>=80L*1024*1024);File(root,UUID.randomUUID().toString()).apply{check(mkdir())}}
 suspend fun capture(page:DocumentPage,active:()->Unit):PhotoCapture{
  var produced:PhotoCapture?=null;var handedOff=false
  try{val value=withContext(Dispatchers.IO){
  require(Build.VERSION.SDK_INT>=34&&page.contentType in setOf("image/jpeg","image/png"));workspacePermit.acquire();var work:File?=null;var ok=false
  try{active();val owned=start();work=owned;val snapshot=File(owned,"source.bin");val digest=copy(page,snapshot,active);active()
   val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(snapshot.path,options)
   require(options.outMimeType in setOf("image/jpeg","image/png")&&options.outWidth in 2..4096&&options.outHeight in 2..4096&&options.outWidth.toLong()*options.outHeight<=12_000_000&&options.outColorSpace?.isSrgb==true&&options.outConfig!=Bitmap.Config.RGBA_F16)
   if(options.outMimeType=="image/png"){val header=ByteArray(29);DataInputStream(snapshot.inputStream()).use{it.readFully(header)};require(photoPng8Bit(header))}
   var sample=1;while((options.outWidth+sample-1)/sample>1024||(options.outHeight+sample-1)/sample>1024)sample*=2
   val decoded=requireNotNull(BitmapFactory.decodeFile(snapshot.path,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}))
   var upright:Bitmap?=null;var appearance:Bitmap?=null
   try{require((if(Build.VERSION.SDK_INT>=34)!decoded.hasGainmap()else false)&&decoded.colorSpace?.isSrgb==true);upright=transform(decoded,readOrientation{snapshot.inputStream()},DocumentPageRotation.DEGREES_0)
    appearance=applyPreviewRotationAndFilter(upright,page.rotation,page.filter,page.adjustments,page.annotations,active)
    require(appearance.width<=1024&&appearance.height<=1024&&appearance.width*appearance.height<=PhotoEngine.MAX_PIXELS)
    val pixels=IntArray(appearance.width*appearance.height);appearance.getPixels(pixels,0,appearance.width,0,0,appearance.width,appearance.height);require(pixels.all{it ushr 24==255});active()
    PhotoCapture(owned,snapshot,digest,pixels,appearance.width,appearance.height,options.outWidth,options.outHeight).also{produced=it;ok=true}
   }finally{listOfNotNull(appearance,upright,decoded).distinct().forEach{it.recycle()}}
  }finally{if(!ok)try{work?.deleteRecursively()}finally{workspacePermit.release()}}
  };handedOff=true;return value
  }finally{if(!handedOff)withContext(NonCancellable+Dispatchers.IO){produced?.let(::finish)}}
 }
 private suspend fun copy(page:DocumentPage,file:File?,active:()->Unit):ByteArray{val digest=MessageDigest.getInstance("SHA-256");var count=0L
  context.contentResolver.openInputStream(page.source.toAndroidUri())!!.use{input->val output=file?.outputStream();try{val b=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();active();val n=input.read(b);if(n<0)break;require(n>0);count+=n;require(count<=SOURCE_LIMIT);digest.update(b,0,n);output?.write(b,0,n)};require(count>0)}finally{output?.close()}};return digest.digest()}
 suspend fun verify(page:DocumentPage,c:PhotoCapture,active:()->Unit){require(copy(page,null,active).contentEquals(c.digest));active()}
 fun png(w:Int,h:Int,pixels:IntArray,active:()->Unit):ByteArray{active();val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);try{val bytes=ByteArrayOutputStream();val limited=object:FilterOutputStream(bytes){var count=0;override fun write(v:Int){active();require(++count<=PNG_LIMIT);out.write(v)};override fun write(b:ByteArray,o:Int,n:Int){active();count+=n;require(count<=PNG_LIMIT);out.write(b,o,n)}};check(bitmap.compress(Bitmap.CompressFormat.PNG,100,limited));active();return bytes.toByteArray()}finally{bitmap.recycle()}}
 fun finish(c:PhotoCapture){if(c.finished.compareAndSet(false,true))try{c.workspace.deleteRecursively()}finally{workspacePermit.release()}}
}
