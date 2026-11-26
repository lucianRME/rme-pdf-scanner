package org.synapseworks.pageharbor.document

import org.synapseworks.pageharbor.document.searchablepdf.PdfPageSize
import org.synapseworks.pageharbor.document.session.DocumentPaperSize
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

internal fun physicalPdfPageSize(size: DocumentPaperSize?,rotation: DocumentPageRotation): PdfPageSize? =
    size?.rotated(rotation.degrees)?.let { PdfPageSize(it.widthPoints,it.heightPoints) }
