package org.synapseworks.pageharbor.xlsx

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document as D
import java.io.File
import org.synapseworks.pageharbor.workflow.*

/** M32's non-truncating Save contract, with XLSX MIME/size bounds and frozen source guards. */
internal fun writeXlsx(context:Context,file:File,target:Uri,name:String,digest:Pair<String,Long>,guards:List<WorkflowSourceGuard>,check:()->Unit) {
    require(target.scheme=="content"&&name.endsWith(".xlsx"));check()
    require(workflowDigest(file.inputStream(),XLSX_MAX,check)==digest)
    val resolver=context.contentResolver
    resolver.query(target,arrayOf(D.COLUMN_DISPLAY_NAME,D.COLUMN_SIZE,D.COLUMN_MIME_TYPE),null,null,null)?.use{c->
        require(c.moveToFirst()&&c.getString(0)==name&&!c.isNull(1)&&c.getLong(1)==0L&&c.getString(2)==XLSX_MIME)
    }?:error("Destination unavailable")
    check();val fd=requireNotNull(resolver.openFileDescriptor(target,"rw"))
    try{require(fd.statSize==0L&&workflowDifferentSources(context,fd,target,guards));check()}catch(e:Throwable){fd.close();throw e}
    ParcelFileDescriptor.AutoCloseOutputStream(fd).use{out->file.inputStream().use{input->val b=ByteArray(65536)
        while(true){check();val n=input.read(b);if(n<0)break;out.write(b,0,n)};check();out.flush()
    }}
    check();require(workflowDigest(requireNotNull(resolver.openInputStream(target)),XLSX_MAX,check)==digest)
}
