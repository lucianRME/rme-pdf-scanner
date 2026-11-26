package org.synapseworks.pageharbor.workflow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document as D
import java.io.File
import java.util.concurrent.CountDownLatch

/** Owned synthetic fault injection, isolated validation variants only. */
class WorkflowFaultProvider:ContentProvider() {
    override fun onCreate()=true
    private val file get()=redirect ?: File(requireNotNull(context).cacheDir,"m30-fault-target")
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):android.database.Cursor {
        val columns=projection?:arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_MIME_TYPE)
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){D.COLUMN_DISPLAY_NAME->name;D.COLUMN_SIZE->file.length();D.COLUMN_MIME_TYPE->mime;else->null}}.toTypedArray())}
    }
    override fun getType(uri:Uri)=mime
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r") { opened++;entered?.countDown();release?.let{check(it.await(20,java.util.concurrent.TimeUnit.SECONDS))};if(failOpen)throw java.io.IOException("Synthetic destination unavailable") }
        return ParcelFileDescriptor.open(file,if(readOnlyWrite && mode!="r")ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.parseMode(mode))
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    companion object {
        @Volatile var redirect:File?=null
        @Volatile var mime="application/pdf"
        @Volatile var name="synthetic.pdf"
        @Volatile var opened=0
        @Volatile var failOpen=false
        @Volatile var readOnlyWrite=false
        @Volatile var entered:CountDownLatch?=null
        @Volatile var release:CountDownLatch?=null
        fun reset(context:android.content.Context,displayName:String):Uri {
            release?.countDown();redirect=null;release=null;entered=null;opened=0;failOpen=false;readOnlyWrite=false;mime="application/pdf";name=displayName
            File(context.cacheDir,"m30-fault-target").writeBytes(byteArrayOf())
            return Uri.parse("content://${context.packageName}.m30fault/target")
        }
        fun bytes(context:android.content.Context)=File(context.cacheDir,"m30-fault-target").readBytes()
    }
}
