package org.synapseworks.pageharbor.document.curvature

import org.synapseworks.pageharbor.document.booklayout.BookDerivedPage
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter

internal fun curvatureSession(original:DocumentSession,replacement:CurvatureReplacement):DocumentSession? =
    replacePageGeometry(original, replacement.original, replacement.derived, replacement.original.filter)

internal fun replacePageGeometry(
    original: DocumentSession,
    page: DocumentPage,
    derived: BookDerivedPage,
    filter: DocumentFilter,
): DocumentSession? {
    if(!page.annotations.isEmpty)return null
    if(derived.source.ownedTemporaryFile?.canonicalIdentityOrNull()==null)return null
    if(original.pages.firstOrNull{it.id==page.id}!==page||derived.source.ownership!=DocumentResourceOwnership.RME_OWNED_TEMPORARY||
        original.pages.any{it.source.reference==derived.source.reference})return null
    val highest=original.pages.maxOfOrNull{it.id.value}?:return null
    if(highest==Long.MAX_VALUE)return null
    val result=page.copy(id=DocumentPageId(highest+1),source=derived.source,contentType="image/jpeg",imageMetadata=derived.metadata,
        rotation=DocumentPageRotation.DEGREES_0,persistentId=null,paperSize=null,filter=filter)
    return original.copy(pages=original.pages.map{if(it===page)result else it},directPdfSource=null,directPdfPageIds=emptyList())
}
