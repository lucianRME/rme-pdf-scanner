package org.synapseworks.pageharbor.document.booklayout

import org.synapseworks.pageharbor.document.session.*

/** Pure atomic session replacement. No publication, cleanup or OCR side effects here. */
internal fun bookSplitSession(original:DocumentSession,replacements:List<BookPageReplacement>):DocumentSession? {
    if(replacements.isEmpty())return original
    if(replacements.any{!it.original.annotations.isEmpty})return null
    if(replacements.map{it.original.id}.distinct().size!=replacements.size)return null
    if(replacements.any{replacement->original.pages.firstOrNull{it.id==replacement.original.id}!==replacement.original||replacement.halves.size!=2})return null
    if(replacements.flatMap{it.halves}.any{it.source.ownership!=DocumentResourceOwnership.RME_OWNED_TEMPORARY})return null
    val references=replacements.flatMap{it.halves}.map{it.source.reference}
    if(references.distinct().size!=references.size||references.any{ref->original.pages.any{it.source.reference==ref}})return null
    var next=original.pages.maxOfOrNull{it.id.value}?:0L
    if(next>Long.MAX_VALUE-replacements.size.toLong()*2)return null
    val byId=replacements.associateBy{it.original.id}
    val pages=original.pages.flatMap{page->
        val replacement=byId[page.id]?:return@flatMap listOf(page)
        replacement.halves.map{half->page.copy(id=DocumentPageId(++next),source=half.source,imageMetadata=half.metadata,
            contentType="image/jpeg",rotation=DocumentPageRotation.DEGREES_0,persistentId=null,paperSize=null)}
    }
    return original.copy(pages=pages,directPdfSource=null,directPdfPageIds=emptyList())
}
