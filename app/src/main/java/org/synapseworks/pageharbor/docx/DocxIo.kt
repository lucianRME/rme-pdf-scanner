package org.synapseworks.pageharbor.docx

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document as D
import java.io.File
import org.synapseworks.pageharbor.workflow.*

/** Non-truncating, source-excluding separate Save. Never deletes a user-owned partial file. */
internal fun writeDocx(context:Context,source:File,target:Uri,name:String,expected:Pair<String,Long>,guards:List<WorkflowSourceGuard>,check:()->Unit) {
    require(name.endsWith(".docx")&&name.length<=120&&target.scheme=="content");check();require(workflowDigest(source.inputStream(),32L*1024*1024,check)==expected)
    val resolver=context.contentResolver
    resolver.query(target,arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_MIME_TYPE),null,null,null)?.use{c->
        require(c.moveToFirst()&&c.getString(0)==name&&!c.isNull(1)&&c.getLong(1)==0L&&c.getString(2)==DOCX_MIME)
    }?:error("Destination unavailable")
    check();val fd=requireNotNull(resolver.openFileDescriptor(target,"rw"))
    try {require(fd.statSize==0L&&workflowDifferentSources(context,fd,target,guards));check()}
    catch(e:Throwable){fd.close();throw e}
    ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out -> source.inputStream().use { input ->
        val b=ByteArray(64*1024)
        while(true){check();val n=input.read(b);if(n<0)break;out.write(b,0,n)};check();out.flush()
    } }
    check();require(workflowDigest(requireNotNull(resolver.openInputStream(target)),32L*1024*1024,check)==expected)
}
