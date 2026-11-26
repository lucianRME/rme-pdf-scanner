package org.synapseworks.pageharbor.validation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only isolated test-package owned files; never accepts a caller path or document content. */
internal class TranslationIoGate(val file:File,val destination:Boolean,val pdf:Boolean=false,val baseName:String="Translation"){
 val entered=CountDownLatch(1);val release=CountDownLatch(1)
 fun awaitRelease(){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
}
class TranslationControlProvider:ContentProvider(){
 companion object {@Volatile internal var active:TranslationIoGate?=null}
 override fun onCreate()=true
 private fun gate(uri:Uri):TranslationIoGate{check(context!!.packageName.endsWith(".m36packsmoke")||context!!.packageName.endsWith(".m36packr8"));check(uri.path=="/owned-control");return checkNotNull(active)}
 override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{val g=gate(uri);check(mode=="r"||g.destination&&mode=="rw");if(!g.destination||mode=="rw")g.awaitRelease();return ParcelFileDescriptor.open(g.file,if(g.destination)ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY)}
 override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor{val g=gate(uri);check(g.destination);val columns=checkNotNull(projection);return MatrixCursor(columns).apply{addRow(columns.map{when(it){Document.COLUMN_DISPLAY_NAME->g.baseName+if(g.pdf)".pdf" else ".txt";Document.COLUMN_SIZE->g.file.length();Document.COLUMN_MIME_TYPE->if(g.pdf)"application/pdf" else "text/plain";else->null}})}}
 override fun getType(uri:Uri)="text/plain"
 override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
 override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
 override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
}
