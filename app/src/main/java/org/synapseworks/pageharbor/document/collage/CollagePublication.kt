package org.synapseworks.pageharbor.document.collage

import org.synapseworks.pageharbor.document.session.*

/** Originals and their OCR identities are retained; derived pages contain only accepted pixels. */
internal fun collageSession(original:DocumentSession,output:CollageOutput):DocumentSession? {
    if(output.pages.isEmpty()||output.pages.size>12||original.pages.size+output.pages.size>500)return null
    val highest=original.pages.maxOfOrNull{it.id.value}?:return null
    if(highest>Long.MAX_VALUE-output.pages.size)return null
    val sources=original.pages.map{it.source.reference}.toSet()
    if(output.pages.map{it.source.reference}.distinct().size!=output.pages.size)return null
    if(output.pages.any{it.source.ownership!=DocumentResourceOwnership.RME_OWNED_TEMPORARY||it.source.reference in sources||
        !validCollagePath(it.source.ownedTemporaryFile)||!output.paper.matchesPixels(it.metadata.width,it.metadata.height)})return null
    val derived=output.pages.mapIndexed{index,page->DocumentPage(DocumentPageId(highest+1+index),page.source,
        DocumentSourceCategory.SELECTED_IMAGE,"image/jpeg",page.metadata,paperSize=output.paper)}
    return original.copy(pages=original.pages+derived,directPdfSource=null,directPdfPageIds=emptyList())
}

/** Lexical validation only on Main; filesystem ownership is proven by the IO processor. */
private fun validCollagePath(owned:OwnedTemporaryFile?):Boolean {
    if(owned==null)return false
    return runCatching {
        val path=java.nio.file.Paths.get(owned.path);val root=java.nio.file.Paths.get(owned.rootPath)
        path.isAbsolute&&root.isAbsolute&&path==path.normalize()&&root==root.normalize()&&path!=root&&path.startsWith(root)
    }.getOrDefault(false)
}
