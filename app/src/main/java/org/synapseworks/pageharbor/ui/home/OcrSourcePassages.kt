package org.synapseworks.pageharbor.ui.home

import androidx.compose.ui.geometry.Rect
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrTextPlacementMode

internal data class OcrSourcePassage(
    val block: Int,
    val start: Int,
    val end: Int,
    val bounds: Rect,
)

/** Match geometry in recognition order. Advancing by occurrence keeps repeated labels and table cells distinct. */
internal fun sourcePassages(blocks: List<OcrReadoutBlock>, layout: OcrPageLayout?): List<OcrSourcePassage> {
    if (layout == null || layout.placementMode != OcrTextPlacementMode.POSITIONED_LINES || layout.rotationDegrees != 0) return emptyList()
    val lines = layout.lines.filter { it.text.isNotBlank() }
    var nextLine = 0
    val passages = mutableListOf<OcrSourcePassage>()
    for ((blockIndex, block) in blocks.withIndex()) {
        var offset = 0
        while (offset < block.text.length) {
            if (block.text[offset].isWhitespace()) { offset++; continue }
            val line = lines.getOrNull(nextLine++) ?: return emptyList()
            val text = line.text.trim()
            if (!block.text.startsWith(text, offset)) return emptyList()
            val box = line.bounds
            if (box != null) {
                val rect = Rect(box.left / layout.imageWidthPx, box.top / layout.imageHeightPx,
                    box.right / layout.imageWidthPx, box.bottom / layout.imageHeightPx)
                if (!rect.isEmpty && rect.left >= 0 && rect.top >= 0 && rect.right <= 1 && rect.bottom <= 1) {
                    passages += OcrSourcePassage(blockIndex, offset, offset + text.length, rect)
                }
            }
            offset += text.length
        }
    }
    return if (nextLine == lines.size) passages else emptyList()
}
