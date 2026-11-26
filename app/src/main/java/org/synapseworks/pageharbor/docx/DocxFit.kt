package org.synapseworks.pageharbor.docx

import android.graphics.Paint
import android.graphics.Typeface

/** Conservative device-side fit guard, not an office renderer or font-embedding claim. */
internal fun checkDocxLayoutFit(document:DocxDocument) {
    if(document.mode!=DocxMode.LAYOUT)return
    require(document.paper==DocxPaper.LETTER)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    for(page in document.pages)for(block in page.blocks) {
        val width=if(page.columns==2&&block.column>=0)258f else 540f
        paint.typeface=Typeface.create("sans-serif",if(block.kind==DocxKind.HEADING)Typeface.BOLD else Typeface.NORMAL)
        paint.textSize=if(block.kind==DocxKind.HEADING)18f else 12f
        val lines=if(block.kind==DocxKind.TABLE)block.table!!.values().flatten()else if(block.kind==DocxKind.IMAGE)emptyList()else DocxText.content(block).lines()
        val available=if(block.kind==DocxKind.TABLE)minOf(width,block.box!!.width*612f)/block.table!!.columns-8f else width-if(block.kind in setOf(DocxKind.BULLET,DocxKind.NUMBER))18f else 0f
        require(lines.all{paint.measureText(it)<=available-3f})
    }
}
