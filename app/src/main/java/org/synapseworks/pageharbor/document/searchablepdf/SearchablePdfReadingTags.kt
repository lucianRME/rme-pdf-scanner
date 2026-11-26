package org.synapseworks.pageharbor.document.searchablepdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDNumberTreeNode
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDParentTreeValue
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot

/** One logical text sequence per searchable page. The existing positioned glyphs provide its contents. */
internal class SearchablePdfReadingTags {
    private val root = PDStructureTreeRoot()
    private val documentElement = PDStructureElement("Document", root).also { root.appendKid(it) }
    private val parents = linkedMapOf<Int, PDParentTreeValue>()

    fun addPage(page: PDPage) {
        val index = parents.size
        page.structParents = index
        val region = PDStructureElement("Div", documentElement)
        region.page = page
        region.cosObject.setInt(COSName.K, 0)
        documentElement.appendKid(region)
        parents[index] = PDParentTreeValue(COSArray().apply { add(region) })
    }

    fun attach(document: PDDocument) {
        root.parentTree = PDNumberTreeNode(PDParentTreeValue::class.java).apply { setNumbers(parents) }
        root.parentTreeNextKey = parents.size
        document.documentCatalog.structureTreeRoot = root
        document.documentCatalog.markInfo = PDMarkInfo().apply { isMarked = true }
    }
}
