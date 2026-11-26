package org.synapseworks.pageharbor.validation
import android.content.*
import androidx.core.content.FileProvider
import java.io.File
/** Test-only public fixture publisher. No URI/path input, no production registration. */
class ImageImportShareReceiver:BroadcastReceiver(){
 override fun onReceive(context:Context,intent:Intent){
  require(context.packageName.endsWith(".m36packsmoke")||context.packageName.endsWith(".m36packr8"));require(intent.action==context.packageName+".RG006_PUBLIC_SHARE")
  val dir=File(context.cacheDir,"shared-pdfs/rg006-inbound-control").apply{mkdirs()};val file=File(dir,"public-exif.jpg")
  context.assets.open("rg006/exif90.jpg").use{input->file.outputStream().use{input.copyTo(it)}}
  val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
  context.startActivity(Intent(Intent.ACTION_SEND).setClassName(context.packageName,"org.synapseworks.pageharbor.MainActivity").setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,uri).apply{clipData=ClipData.newRawUri("Public test image",uri);addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)})
 }
}
