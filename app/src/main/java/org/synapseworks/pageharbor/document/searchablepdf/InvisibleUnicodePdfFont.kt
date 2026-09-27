package org.synapseworks.pageharbor.document.searchablepdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import java.nio.charset.StandardCharsets

/**
 * A tiny embedded font used only with invisible text rendering.
 *
 * Each blank Type 3 glyph has a real advance width and an explicit ToUnicode mapping. This makes
 * arbitrary valid Unicode searchable without bundling script fonts whose visible glyph outlines
 * can never be painted by the hidden OCR layer. Character sets are subset per document.
 */
internal class InvisibleUnicodePdfFontSet(
    document: PDDocument,
    texts: Sequence<String>,
) {
    private val codePoints = texts
        .flatMap { text -> text.pdfCodePoints().asSequence() }
        .distinct()
        .toList()
        .also { require(it.size <= MAX_DOCUMENT_CODE_POINTS) }
    private val subsets = codePoints.chunked(MAX_CODES_PER_FONT).map { subset ->
        InvisibleUnicodePdfFont(document, subset)
    }
    private val encodingByCodePoint = buildMap {
        subsets.forEach { subset ->
            subset.codeByCodePoint.forEach { (codePoint, code) ->
                put(codePoint, EncodedCode(subset.font, code))
            }
        }
    }

    fun encode(text: String): List<InvisibleUnicodeTextRun> {
        val result = mutableListOf<InvisibleUnicodeTextRun>()
        var currentFont: PDType3Font? = null
        var currentBytes = mutableListOf<Byte>()
        fun flush() {
            val font = currentFont ?: return
            result += InvisibleUnicodeTextRun(font, currentBytes.toByteArray())
            currentBytes = mutableListOf()
        }
        text.pdfCodePoints().forEach { codePoint ->
            val encoded = requireNotNull(encodingByCodePoint[codePoint])
            if (currentFont !== encoded.font) {
                flush()
                currentFont = encoded.font
            }
            currentBytes += encoded.code.toByte()
        }
        flush()
        return result
    }

    private data class EncodedCode(val font: PDType3Font, val code: Int)

    private companion object {
        const val MAX_CODES_PER_FONT = 255
        const val MAX_DOCUMENT_CODE_POINTS = 8_192
    }
}

internal data class InvisibleUnicodeTextRun(
    val font: PDType3Font,
    val encodedBytes: ByteArray,
) {
    val codePointCount: Int get() = encodedBytes.size
}

internal fun ByteArray.toPdfHexString(): String = joinToString(
    prefix = "<",
    postfix = ">",
    separator = "",
) { byte -> "%02X".format(byte.toInt() and 0xFF) }

private class InvisibleUnicodePdfFont(
    document: PDDocument,
    codePoints: List<Int>,
) {
    val codeByCodePoint = codePoints.mapIndexed { index, codePoint -> codePoint to index + 1 }.toMap()
    val font: PDType3Font = PDType3Font(createDictionary(document, codeByCodePoint))

    private fun createDictionary(
        document: PDDocument,
        mapping: Map<Int, Int>,
    ): COSDictionary {
        val dictionary = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE3)
            setItem(COSName.FONT_BBOX, floatArray(0f, 0f, GLYPH_WIDTH, GLYPH_WIDTH))
            setItem(COSName.FONT_MATRIX, floatArray(0.001f, 0f, 0f, 0.001f, 0f, 0f))
            setInt(COSName.FIRST_CHAR, 1)
            setInt(COSName.LAST_CHAR, mapping.size)
            setItem(COSName.RESOURCES, PDResources())
        }
        val widths = COSArray()
        repeat(mapping.size) { widths.add(COSInteger.get(GLYPH_WIDTH.toLong())) }
        dictionary.setItem(COSName.WIDTHS, widths)

        val charProcs = COSDictionary()
        val blankGlyph = PDStream(document).apply {
            createOutputStream().use { output ->
                output.write("${GLYPH_WIDTH.toInt()} 0 d0\n".toByteArray(StandardCharsets.US_ASCII))
            }
        }
        val differences = COSArray().apply { add(COSInteger.ONE) }
        mapping.values.forEach { code ->
            val glyphName = COSName.getPDFName("g$code")
            differences.add(glyphName)
            charProcs.setItem(glyphName, blankGlyph)
        }
        dictionary.setItem(COSName.CHAR_PROCS, charProcs)
        dictionary.setItem(
            COSName.ENCODING,
            COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ENCODING)
                setItem(COSName.DIFFERENCES, differences)
            },
        )
        dictionary.setItem(COSName.TO_UNICODE, toUnicodeStream(document, mapping))
        return dictionary
    }

    private fun toUnicodeStream(document: PDDocument, mapping: Map<Int, Int>): PDStream {
        val cmap = buildString {
            append("/CIDInit /ProcSet findresource begin\n")
            append("12 dict begin\nbegincmap\n")
            append("/CIDSystemInfo << /Registry (RME) /Ordering (Unicode) /Supplement 0 >> def\n")
            append("/CMapName /RMEInvisibleUnicode def\n/CMapType 2 def\n")
            append("1 begincodespacerange\n<01> <FF>\nendcodespacerange\n")
            mapping.entries.chunked(MAX_BFCHAR_ENTRIES).forEach { entries ->
                append(entries.size).append(" beginbfchar\n")
                entries.forEach { (codePoint, code) ->
                    append('<').append(code.toString(16).padStart(2, '0').uppercase()).append("> <")
                    append(String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_16BE)
                        .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) })
                    append(">\n")
                }
                append("endbfchar\n")
            }
            append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        }
        return PDStream(document).apply {
            createOutputStream().use { output ->
                output.write(cmap.toByteArray(StandardCharsets.US_ASCII))
            }
        }
    }

    private fun floatArray(vararg values: Float) = COSArray().apply {
        values.forEach { value -> add(COSFloat(value)) }
    }

    private companion object {
        const val GLYPH_WIDTH = 1_000f
        const val MAX_BFCHAR_ENTRIES = 100
    }
}

private fun String.pdfCodePoints(): List<Int> = buildList {
    var index = 0
    while (index < this@pdfCodePoints.length) {
        val original = this@pdfCodePoints.codePointAt(index)
        index += Character.charCount(original)
        val valid = if (
            original in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code ||
            !Character.isValidCodePoint(original)
        ) {
            REPLACEMENT_CHARACTER
        } else if (Character.isISOControl(original)) {
            SPACE_CHARACTER
        } else {
            original
        }
        add(valid)
    }
}

private const val REPLACEMENT_CHARACTER = 0xFFFD
private const val SPACE_CHARACTER = 0x20
