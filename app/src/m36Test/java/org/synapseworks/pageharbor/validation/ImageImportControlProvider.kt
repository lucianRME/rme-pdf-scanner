package org.synapseworks.pageharbor.validation
import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
/** Only isolated public fixtures. Never exported or registered in production. */
class ImageImportControlProvider:ContentProvider(){
 companion object{@Volatile var file:File?=null;@Volatile var size:Long?=null;@Volatile var denied=false;@Volatile var mime:String?=null;@Volatile var opens=0;@Volatile var gateOpen=0;@Volatile var entered:java.util.concurrent.CountDownLatch?=null;@Volatile var release:java.util.concurrent.CountDownLatch?=null}
 override fun onCreate()=true
 override fun getType(uri:Uri)=mime
 override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor{val cols=p?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE);return MatrixCursor(cols).apply{addRow(cols.map{if(it==OpenableColumns.SIZE)size else if(it==OpenableColumns.DISPLAY_NAME)"RG006 public control"else null})}}
 override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{check(context!!.packageName.endsWith(".m36packsmoke")||context!!.packageName.endsWith(".m36packr8"));require(uri.path=="/owned-input"&&mode=="r");if(denied)throw SecurityException();synchronized(this){opens++};if(gateOpen>0&&opens==gateOpen){entered?.countDown();check(release?.await(5,java.util.concurrent.TimeUnit.SECONDS)==true)};return ParcelFileDescriptor.open(requireNotNull(file),ParcelFileDescriptor.MODE_READ_ONLY)}
 override fun insert(u:Uri,v:ContentValues?):Uri?=throw UnsupportedOperationException()
 override fun delete(u:Uri,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
 override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
}
