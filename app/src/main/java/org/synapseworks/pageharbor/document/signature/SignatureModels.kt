package org.synapseworks.pageharbor.document.signature

import java.io.*
import java.util.Base64
import java.util.Collections

const val MAX_SIGNATURE_STROKES = 128
const val MAX_SIGNATURE_POINTS_PER_STROKE = 2_048
const val MAX_SIGNATURE_POINTS = 8_192
const val MAX_SIGNATURE_ENCODING = 128 * 1024

/** Spatial samples only: no timing, pressure, identity or biometric profile. */
data class SignaturePoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) }
    override fun toString() = "SignaturePoint"
}
class SignatureStroke(points: List<SignaturePoint>) {
    init { require(points.size in 1..MAX_SIGNATURE_POINTS_PER_STROKE) }
    val points: List<SignaturePoint> = Collections.unmodifiableList(points.toList())
    init { require(this.points.size in 1..MAX_SIGNATURE_POINTS_PER_STROKE) }
    fun copy(points: List<SignaturePoint> = this.points) = SignatureStroke(points)
    override fun equals(other: Any?) = other is SignatureStroke && points == other.points
    override fun hashCode() = points.hashCode()
    override fun toString() = "SignatureStroke"
}
/** Frozen handwriting including the pad's physical aspect ratio and deliberate whitespace. */
class HandwrittenSignature(strokes: List<SignatureStroke>, val strokeWidthFraction: Float = .012f, val aspectRatio: Float = 1f) {
    init { require(strokes.size in 1..MAX_SIGNATURE_STROKES) }
    val strokes: List<SignatureStroke> = Collections.unmodifiableList(strokes.toList())
    val pointCount = this.strokes.sumOf { it.points.size }
    init {
        require(this.strokes.size in 1..MAX_SIGNATURE_STROKES && pointCount <= MAX_SIGNATURE_POINTS)
        require(strokeWidthFraction.isFinite() && strokeWidthFraction in .001f.. .05f)
        require(aspectRatio.isFinite() && aspectRatio in .125f..8f)
    }
    val encoding: String = run {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeFloat(strokeWidthFraction); out.writeFloat(aspectRatio); out.writeInt(this.strokes.size)
            for (stroke in this.strokes) { out.writeInt(stroke.points.size); for (p in stroke.points) { out.writeFloat(p.x); out.writeFloat(p.y) } }
        }
        Base64.getEncoder().encodeToString(bytes.toByteArray()).also { require(it.length <= MAX_SIGNATURE_ENCODING) }
    }
    val metadataBytes: Long = 256L + 2L * encoding.length + this.strokes.size * 64L + pointCount * 40L
    fun copy(strokes: List<SignatureStroke> = this.strokes, strokeWidthFraction: Float = this.strokeWidthFraction, aspectRatio: Float = this.aspectRatio) = HandwrittenSignature(strokes, strokeWidthFraction, aspectRatio)
    fun rotated() = HandwrittenSignature(strokes.map { SignatureStroke(it.points.map { p -> SignaturePoint(1f-p.y,p.x) }) }, strokeWidthFraction, 1f/aspectRatio)
    override fun equals(other: Any?) = other is HandwrittenSignature && encoding == other.encoding
    override fun hashCode() = encoding.hashCode()
    override fun toString() = "HandwrittenSignature"
    companion object {
        fun decode(encoded: String): HandwrittenSignature {
            require(encoded.length <= MAX_SIGNATURE_ENCODING)
            try {
                DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                    require(input.readInt() == 1)
                    val width = input.readFloat(); val ratio = input.readFloat(); val count = input.readInt()
                    require(count in 1..MAX_SIGNATURE_STROKES)
                    var total = 0
                    val strokes = List(count) {
                        val n = input.readInt(); require(n in 1..MAX_SIGNATURE_POINTS_PER_STROKE)
                        total += n; require(total <= MAX_SIGNATURE_POINTS)
                        SignatureStroke(List(n) { SignaturePoint(input.readFloat(),input.readFloat()) })
                    }
                    require(input.read() == -1)
                    return HandwrittenSignature(strokes,width,ratio)
                }
            } catch (_: Exception) { throw IllegalArgumentException("Invalid handwriting") }
        }
    }
}

/** Bounds on the displayed, rotated crop of a page; upper-left origin, values in 0..1. */
data class SignaturePlacement(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(right > left && bottom > top)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class SignaturePdfRequest(
    val pageIndex: Int,
    val signature: HandwrittenSignature,
    val placement: SignaturePlacement,
) {
    init { require(pageIndex >= 0) }
}

data class SignaturePdfPoint(val x: Float, val y: Float)

/** Maps visual pad coordinates to PDF user space, including crop origins and page rotation. */
data class SignaturePageGeometry(
    val cropLeft: Float,
    val cropBottom: Float,
    val cropWidth: Float,
    val cropHeight: Float,
    val rotationDegrees: Int,
) {
    init {
        require(listOf(cropLeft, cropBottom, cropWidth, cropHeight).all { it.isFinite() })
        require(cropWidth > 0f && cropHeight > 0f)
        require(rotationDegrees % 90 == 0)
        require((cropLeft + cropWidth).isFinite() && (cropBottom + cropHeight).isFinite())
    }

    private val rotation: Int get() = (rotationDegrees % 360 + 360) % 360
    val displayedWidth: Float get() = if (rotation % 180 == 0) cropWidth else cropHeight
    val displayedHeight: Float get() = if (rotation % 180 == 0) cropHeight else cropWidth

    fun map(point: SignaturePoint, placement: SignaturePlacement): SignaturePdfPoint {
        val x = placement.left + point.x * placement.width
        val y = placement.top + point.y * placement.height
        val (u, v) = when (rotation) {
            0 -> x to (1f - y)
            90 -> y to x
            180 -> (1f - x) to y
            else -> (1f - y) to (1f - x)
        }
        return SignaturePdfPoint(cropLeft + u * cropWidth, cropBottom + v * cropHeight)
    }

    fun strokeWidth(signature: HandwrittenSignature, placement: SignaturePlacement): Float =
        (displayedWidth * placement.width * signature.strokeWidthFraction).coerceAtLeast(0.01f)
}

enum class SignaturePdfFailure {
    SOURCE_MISSING,
    SOURCE_TOO_LARGE,
    OUTPUT_EXISTS,
    INVALID_REQUEST,
    SOURCE_LOCKED,
    SOURCE_CHANGED,
    WRITE_FAILED,
}

sealed interface SignaturePdfResult {
    data class Success(val pageCount: Int) : SignaturePdfResult
    data class Failure(val reason: SignaturePdfFailure) : SignaturePdfResult
}
