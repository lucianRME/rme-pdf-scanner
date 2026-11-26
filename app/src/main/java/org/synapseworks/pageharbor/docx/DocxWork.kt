package org.synapseworks.pageharbor.docx

import android.content.Context
import java.io.File
import kotlinx.coroutines.sync.Mutex

/** Across activity owners: serialize heavy buffers and reserve worst-case transient disk growth. */
internal object DocxWork {
    val mutex=Mutex()
    fun space(context:Context,reserve:Long) {
        val root=File(context.noBackupFilesDir,"docx-v1")
        val used=root.walkTopDown().filter{it.isFile}.sumOf{it.length()}
        require(used+reserve<=256L*1024*1024&&root.usableSpace>=320L*1024*1024)
    }
}
