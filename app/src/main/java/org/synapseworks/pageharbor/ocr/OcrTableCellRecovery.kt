package org.synapseworks.pageharbor.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Bounded second reads of source pixels in an already identified table. */
internal class OcrTableCellRecovery(
    private val width: Int,
    private val height: Int,
    private val read: (OcrTextBounds, Float, Int) -> OcrTextLine?,
) {
    private var attempts = 0

    fun recover(rows: List<List<OcrTextLine?>>, slope: Float): List<List<OcrTextLine?>> =
        rows.mapIndexed { rowIndex, row ->
            row.mapIndexed { column, cell ->
                if (cell != null) recoverWord(cell) else recoverQuantity(rows, rowIndex, column, slope)
            }
        }

    private fun recoverWord(cell: OcrTextLine): OcrTextLine {
        val elements = cell.elements
        if (elements.size < 2 || elements.joinToString(" ", transform = OcrTextElement::text) != cell.text) {
            return cell
        }
        val index = elements.indices.singleOrNull { embeddedZero.matches(elements[it].text) } ?: return cell
        val word = elements[index]
        val box = word.bounds ?: return cell
        val padding = box.height * .5f
        val previous = elements.getOrNull(index - 1)
        val next = elements.getOrNull(index + 1)
        // Keep some background for recognition without admitting pixels from an adjacent word.
        val left = previous?.bounds?.let { maxOf(box.left - padding, (it.right + box.left) / 2f) }
            ?: (box.left - padding)
        val right = next?.bounds?.let { minOf(box.right + padding, (box.right + it.left) / 2f) }
            ?: (box.right + padding)
        if (left > box.left || right < box.right) return cell
        val crop = cropBounds(left, box.top - padding, right, box.bottom + padding) ?: return cell
        val reading = agreedRead(crop, box.height, 48)?.text ?: return cell
        val zero = word.text.indexOf('0')
        if (reading != word.text.replaceRange(zero, zero + 1, "O") &&
            reading != word.text.replaceRange(zero, zero + 1, "o")
        ) return cell
        val updated = elements.mapIndexed { i, element -> if (i == index) element.copy(text = reading) else element }
        return cell.copy(text = updated.joinToString(" ", transform = OcrTextElement::text), elements = updated)
    }

    private fun recoverQuantity(
        rows: List<List<OcrTextLine?>>,
        index: Int,
        column: Int,
        slope: Float,
    ): OcrTextLine? {
        val row = rows[index]
        if (column == 0 || column == row.lastIndex || row.count { it == null } != 1) return null
        val above = rows.getOrNull(index - 1)?.getOrNull(column)?.integerBounds() ?: return null
        val below = rows.getOrNull(index + 1)?.getOrNull(column)?.integerBounds() ?: return null
        val left = row[column - 1]?.bounds ?: return null
        val right = row[column + 1]?.bounds ?: return null
        val textHeight = (above.height + below.height) / 2f
        val x = (above.centerX + below.centerX) / 2f
        val y = (above.centerY + below.centerY) / 2f
        val rowY = (left.centerY + right.centerY - slope * (left.centerX + right.centerX)) / 2f
        if (abs(above.centerX - below.centerX) > textHeight ||
            abs(y - slope * x - rowY) > textHeight * .6f
        ) return null

        val halfWidth = maxOf(above.width + above.height, below.width + below.height) / 2f
        val crop = cropBounds(floor(x - halfWidth), floor(y - textHeight), ceil(x + halfWidth), ceil(y + textHeight))
            ?: return null
        if (crop.left <= left.right || crop.right >= right.left ||
            crop.top <= above.bottom || crop.bottom >= below.top
        ) return null
        val reading = agreedRead(crop, textHeight, 40) ?: return null
        val box = reading.integerBounds() ?: return null
        if (abs(box.centerX - x) > textHeight * .5f || abs(box.centerY - y) > textHeight * .6f ||
            box.height !in (textHeight * .45f)..(textHeight * 1.5f)
        ) return null
        return reading
    }

    private fun agreedRead(crop: OcrTextBounds, textHeight: Float, firstScale: Int): OcrTextLine? {
        if (textHeight < 4f || attempts >= MAX_CELLS) return null
        attempts++
        val first = read(crop, textHeight, firstScale) ?: return null
        val second = read(crop, textHeight, 96) ?: return null
        val firstBox = first.bounds ?: return null
        val secondBox = second.bounds ?: return null
        // The two scales must recognize the same ink, in the same location.
        return first.takeIf {
            first.text == second.text &&
                abs(firstBox.centerX - secondBox.centerX) <= textHeight * .4f &&
                abs(firstBox.centerY - secondBox.centerY) <= textHeight * .4f
        }
    }

    private fun cropBounds(left: Float, top: Float, right: Float, bottom: Float): OcrTextBounds? {
        val clampedLeft = left.coerceAtLeast(0f)
        val clampedTop = top.coerceAtLeast(0f)
        val clampedRight = right.coerceAtMost(width.toFloat())
        val clampedBottom = bottom.coerceAtMost(height.toFloat())
        if (clampedLeft >= clampedRight || clampedTop >= clampedBottom) return null
        return OcrTextBounds(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }

    private fun OcrTextLine.integerBounds() = bounds?.takeIf { it.height >= 4f && integer.matches(text) }
    private val OcrTextBounds.width get() = right - left
    private val OcrTextBounds.height get() = bottom - top
    private val OcrTextBounds.centerX get() = (left + right) / 2f
    private val OcrTextBounds.centerY get() = (top + bottom) / 2f

    private companion object {
        const val MAX_CELLS = 4
        val embeddedZero = Regex("""\p{L}+0\p{L}+""")
        val integer = Regex("[0-9]{1,4}")
    }
}
