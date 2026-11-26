package org.synapseworks.pageharbor.document.booklayout

import kotlin.math.min
import kotlin.math.abs

enum class BookLayoutMode { SPLIT_SPREAD, TWO_UP }

enum class BookReadingOrder { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/** Paper sizes are PDF points; two-up output uses the landscape orientation. */
enum class BookPaperSize(val portraitWidthPoints: Float, val portraitHeightPoints: Float) {
    A4(595.2756f, 841.8898f),
    LETTER(612f, 792f),
}

data class BookLayoutSettings(
    val mode: BookLayoutMode = BookLayoutMode.SPLIT_SPREAD,
    val splitFraction: Float = 0.5f,
    val readingOrder: BookReadingOrder = BookReadingOrder.LEFT_TO_RIGHT,
    val paperSize: BookPaperSize = BookPaperSize.A4,
    val marginPoints: Float = 18f,
    val gapPoints: Float = 12f,
) {
    init {
        require(splitFraction.isFinite() && splitFraction in 0.1f..0.9f)
        require(marginPoints.isFinite() && marginPoints in 0f..144f)
        require(gapPoints.isFinite() && gapPoints in 0f..144f)
        require(paperSize.portraitHeightPoints - marginPoints * 2f - gapPoints > 0f)
        require(paperSize.portraitWidthPoints - marginPoints * 2f > 0f)
    }
}

enum class BookLayoutFailure {
    INVALID_SOURCE,
    SOURCE_MISSING,
    SOURCE_TOO_LARGE,
    TOO_MANY_PAGES,
    PROTECTED_SOURCE,
    UNSUPPORTED_PAGE,
    OUTPUT_EXISTS,
    SOURCE_CHANGED,
    WRITE_FAILED,
}

sealed interface BookLayoutResult {
    data class Success(val pageCount: Int) : BookLayoutResult
    data class Failure(val reason: BookLayoutFailure) : BookLayoutResult
}

internal data class BookPoint(val x: Float, val y: Float)

/** PDF affine transform: x' = ax + cy + e; y' = bx + dy + f. */
internal data class BookAffineTransform(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    fun map(x: Float, y: Float): BookPoint = BookPoint(a * x + c * y + e, b * x + d * y + f)

    fun scaleAndTranslate(scale: Float, x: Float, y: Float): BookAffineTransform =
        BookAffineTransform(a * scale, b * scale, c * scale, d * scale, e * scale + x, f * scale + y)
}

/** Source crop coordinates before the page's clockwise display rotation. */
internal data class BookSourcePage(
    val left: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Int = 0,
    val userUnit: Float = 1f,
) {
    init {
        require(listOf(left, bottom, width, height, userUnit).all(Float::isFinite))
        require(width > 0f && height > 0f && userUnit > 0f)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require((width * userUnit) in 1f..14_400f && (height * userUnit) in 1f..14_400f)
        require((left + width).isFinite() && (bottom + height).isFinite())
        require(listOf(left, bottom, left + width, bottom + height).all { abs(it * userUnit) <= 1_000_000f })
    }

    val displayWidth: Float get() = (if (rotationDegrees % 180 == 0) width else height) * userUnit
    val displayHeight: Float get() = (if (rotationDegrees % 180 == 0) height else width) * userUnit

    fun displayTransform(): BookAffineTransform = when (rotationDegrees) {
        0 -> BookAffineTransform(userUnit, 0f, 0f, userUnit, -left * userUnit, -bottom * userUnit)
        90 -> BookAffineTransform(0f, -userUnit, userUnit, 0f, -bottom * userUnit, (left + width) * userUnit)
        180 -> BookAffineTransform(-userUnit, 0f, 0f, -userUnit, (left + width) * userUnit, (bottom + height) * userUnit)
        else -> BookAffineTransform(0f, userUnit, -userUnit, 0f, (bottom + height) * userUnit, -left * userUnit)
    }
}

internal data class BookClip(val left: Float, val bottom: Float, val width: Float, val height: Float)

internal data class BookPagePlacement(
    val sourceIndex: Int,
    val transform: BookAffineTransform,
    val clip: BookClip,
)

internal data class BookOutputPage(
    val width: Float,
    val height: Float,
    val placements: List<BookPagePlacement>,
) {
    init {
        require(width.isFinite() && height.isFinite() && width >= 1f && height >= 1f)
    }
}

/** Original geometry planning, independent of Android and PDF parsing. */
internal object BookLayoutGeometry {
    fun plan(pages: List<BookSourcePage>, settings: BookLayoutSettings): List<BookOutputPage> {
        require(pages.isNotEmpty())
        return when (settings.mode) {
            BookLayoutMode.SPLIT_SPREAD -> pages.flatMapIndexed { index, page ->
                val splitX = page.displayWidth * settings.splitFraction
                val portions = listOf(0f to splitX, splitX to page.displayWidth - splitX)
                val ordered = if (settings.readingOrder == BookReadingOrder.RIGHT_TO_LEFT) portions.reversed() else portions
                ordered.map { (left, width) ->
                    BookOutputPage(
                        width = width,
                        height = page.displayHeight,
                        placements = listOf(
                            BookPagePlacement(index, page.displayTransform().scaleAndTranslate(1f, -left, 0f), BookClip(0f, 0f, width, page.displayHeight)),
                        ),
                    )
                }
            }

            BookLayoutMode.TWO_UP -> pages.indices.chunked(2).map { indices ->
                val width = settings.paperSize.portraitHeightPoints
                val height = settings.paperSize.portraitWidthPoints
                val slotWidth = (width - settings.marginPoints * 2f - settings.gapPoints) / 2f
                val slotHeight = height - settings.marginPoints * 2f
                BookOutputPage(
                    width,
                    height,
                    indices.mapIndexed { position, index ->
                        val slot = if (settings.readingOrder == BookReadingOrder.RIGHT_TO_LEFT) 1 - position else position
                        val source = pages[index]
                        val scale = min(slotWidth / source.displayWidth, slotHeight / source.displayHeight)
                        val slotLeft = settings.marginPoints + slot * (slotWidth + settings.gapPoints)
                        val placedLeft = slotLeft + (slotWidth - source.displayWidth * scale) / 2f
                        val placedBottom = settings.marginPoints + (slotHeight - source.displayHeight * scale) / 2f
                        BookPagePlacement(
                            index,
                            source.displayTransform().scaleAndTranslate(scale, placedLeft, placedBottom),
                            BookClip(slotLeft, settings.marginPoints, slotWidth, slotHeight),
                        )
                    },
                )
            }
        }
    }
}
