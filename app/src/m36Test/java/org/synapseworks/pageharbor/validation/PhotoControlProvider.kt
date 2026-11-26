package org.synapseworks.pageharbor.validation
import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document as D
import java.io.File
/** Test-only owned synthetic destination. No path arguments, production registration or exports. */
class PhotoControlProvider:ContentProvider(){
 companion object{@Volatile var file:File?=null;@Volatile var entered:java.util.concurrent.CountDownLatch?=null;@Volatile var release:java.util.concurrent.CountDownLatch?=null}
 override fun onCreate()=true
 private fun source(uri:Uri):File{check(context!!.packageName.endsWith(".m36packsmoke")||context!!.packageName.endsWith(".m36packr8"));check(uri.path=="/owned-control");return requireNotNull(file)}
 override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{require(mode in setOf("r","rw"));if(mode=="rw"){entered?.countDown();release?.let{check(it.await(5,java.util.concurrent.TimeUnit.SECONDS))}};return ParcelFileDescriptor.open(source(uri),if(mode=="r")ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_READ_WRITE)}
 override fun query(uri:Uri,projection:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor{val f=source(uri);val columns=requireNotNull(projection);return MatrixCursor(columns).apply{addRow(columns.map{when(it){D.COLUMN_DISPLAY_NAME->"Restored-photo.png";D.COLUMN_SIZE->f.length();D.COLUMN_MIME_TYPE->"image/png";else->null}})}}
 override fun getType(uri:Uri)="image/png"
 override fun insert(u:Uri,v:ContentValues?):Uri?=throw UnsupportedOperationException()
 override fun delete(u:Uri,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
 override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
}
