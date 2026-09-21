package org.synapseworks.pageharbor.backup.format

internal class BackupAssetInspection private constructor(
    private val prefix: ByteArray,
    private val jpegDimensions: AssetDimensions?,
) {
    fun requireMatches(mimeType: String, expectedWidth: Int?, expectedHeight: Int?) {
        val signatureMatches = when (mimeType) {
            "image/jpeg" -> prefix.startsWith(0xff, 0xd8, 0xff)
            "image/png" -> prefix.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            "image/webp" -> prefix.startsWith(0x52, 0x49, 0x46, 0x46) &&
                prefix.sliceMatches(8, 0x57, 0x45, 0x42, 0x50)
            "application/pdf" -> prefix.startsWith(0x25, 0x50, 0x44, 0x46, 0x2d)
            else -> false
        }
        if (!signatureMatches) {
            throw backupFailure(
                BackupFormatFailure.SIGNATURE_MISMATCH,
                "An asset signature does not match its declared MIME type.",
            )
        }
        if (expectedWidth == null && expectedHeight == null) return
        if (expectedWidth == null || expectedHeight == null) {
            throw backupFailure(
                BackupFormatFailure.INVALID_METADATA,
                "Page dimensions must be declared together.",
            )
        }
        val measured = when (mimeType) {
            "image/jpeg" -> jpegDimensions
            "image/png" -> pngDimensions()
            "image/webp" -> webpDimensions()
            else -> null
        }
        if (measured == null || measured.width != expectedWidth || measured.height != expectedHeight) {
            throw backupFailure(
                BackupFormatFailure.DIMENSION_MISMATCH,
                "An image asset does not match its declared dimensions.",
            )
        }
    }

    private fun pngDimensions(): AssetDimensions? {
        if (!prefix.sliceMatches(12, 0x49, 0x48, 0x44, 0x52) || prefix.size < 24) return null
        val width = prefix.bigEndianInt(16)
        val height = prefix.bigEndianInt(20)
        return if (width > 0 && height > 0) AssetDimensions(width, height) else null
    }

    private fun webpDimensions(): AssetDimensions? {
        if (prefix.size < 16) return null
        return when {
            prefix.sliceMatches(12, 0x56, 0x50, 0x38, 0x58) && prefix.size >= 30 -> {
                val width = prefix.littleEndian24(24) + 1
                val height = prefix.littleEndian24(27) + 1
                AssetDimensions(width, height)
            }

            prefix.sliceMatches(12, 0x56, 0x50, 0x38, 0x4c) &&
                prefix.size >= 25 && prefix[20] == 0x2f.toByte() -> {
                val first = prefix[21].toInt() and 0xff
                val second = prefix[22].toInt() and 0xff
                val third = prefix[23].toInt() and 0xff
                val fourth = prefix[24].toInt() and 0xff
                AssetDimensions(
                    width = 1 + first + ((second and 0x3f) shl 8),
                    height = 1 + (second shr 6) + (third shl 2) + ((fourth and 0x0f) shl 10),
                )
            }

            prefix.sliceMatches(12, 0x56, 0x50, 0x38, 0x20) &&
                prefix.size >= 30 && prefix.sliceMatches(23, 0x9d, 0x01, 0x2a) -> {
                AssetDimensions(
                    width = prefix.littleEndian16(26) and 0x3fff,
                    height = prefix.littleEndian16(28) and 0x3fff,
                ).takeIf { it.width > 0 && it.height > 0 }
            }

            else -> null
        }
    }

    class Collector {
        private val prefix = ByteArray(MAX_PREFIX_BYTES)
        private var prefixCount = 0
        private val jpegParser = JpegDimensionParser()

        fun update(buffer: ByteArray, offset: Int, length: Int) {
            val copied = minOf(length, prefix.size - prefixCount)
            if (copied > 0) {
                buffer.copyInto(prefix, prefixCount, offset, offset + copied)
                prefixCount += copied
            }
            jpegParser.update(buffer, offset, length)
        }

        fun finish(): BackupAssetInspection = BackupAssetInspection(
            prefix = prefix.copyOf(prefixCount),
            jpegDimensions = jpegParser.dimensions,
        )
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean = sliceMatches(0, *values)

    private fun ByteArray.sliceMatches(offset: Int, vararg values: Int): Boolean =
        size >= offset + values.size && values.indices.all { index ->
            this[offset + index] == values[index].toByte()
        }

    private fun ByteArray.bigEndianInt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.littleEndian16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.littleEndian24(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)

    private companion object {
        const val MAX_PREFIX_BYTES = 32
    }
}

private data class AssetDimensions(val width: Int, val height: Int)

private class JpegDimensionParser {
    private var state = State.EXPECT_FF
    private var marker = 0
    private var segmentLength = 0
    private var remainingSegmentBytes = 0
    private var sofDataIndex = 0
    private var heightHigh = 0
    private var heightLow = 0
    private var widthHigh = 0
    private var validJpeg = true
    var dimensions: AssetDimensions? = null
        private set

    fun update(buffer: ByteArray, offset: Int, length: Int) {
        if (!validJpeg || dimensions != null) return
        for (index in offset until offset + length) {
            consume(buffer[index].toInt() and 0xff)
            if (!validJpeg || dimensions != null) return
        }
    }

    private fun consume(value: Int) {
        when (state) {
            State.EXPECT_FF -> if (value == 0xff) state = State.EXPECT_SOI else validJpeg = false
            State.EXPECT_SOI -> if (value == 0xd8) state = State.MARKER_PREFIX else validJpeg = false
            State.MARKER_PREFIX -> if (value == 0xff) state = State.MARKER else validJpeg = false
            State.MARKER -> when {
                value == 0xff -> Unit
                value == 0x00 -> validJpeg = false
                value == 0xd9 || value == 0xda -> validJpeg = false
                value == 0x01 || value in 0xd0..0xd8 -> state = State.MARKER_PREFIX
                else -> {
                    marker = value
                    segmentLength = 0
                    state = State.LENGTH_HIGH
                }
            }

            State.LENGTH_HIGH -> {
                segmentLength = value shl 8
                state = State.LENGTH_LOW
            }

            State.LENGTH_LOW -> {
                segmentLength = segmentLength or value
                if (segmentLength < 2) {
                    validJpeg = false
                } else {
                    remainingSegmentBytes = segmentLength - 2
                    sofDataIndex = 0
                    state = if (remainingSegmentBytes == 0) State.MARKER_PREFIX else State.SEGMENT
                }
            }

            State.SEGMENT -> {
                if (marker in SOF_MARKERS) consumeSofByte(value)
                remainingSegmentBytes -= 1
                if (remainingSegmentBytes == 0 && dimensions == null) state = State.MARKER_PREFIX
            }
        }
    }

    private fun consumeSofByte(value: Int) {
        when (sofDataIndex) {
            1 -> heightHigh = value
            2 -> heightLow = value
            3 -> widthHigh = value
            4 -> {
                val height = (heightHigh shl 8) or heightLow
                val width = (widthHigh shl 8) or value
                if (width > 0 && height > 0) dimensions = AssetDimensions(width, height)
            }
        }
        sofDataIndex += 1
    }

    private enum class State {
        EXPECT_FF,
        EXPECT_SOI,
        MARKER_PREFIX,
        MARKER,
        LENGTH_HIGH,
        LENGTH_LOW,
        SEGMENT,
    }

    private companion object {
        val SOF_MARKERS = setOf(
            0xc0,
            0xc1,
            0xc2,
            0xc3,
            0xc5,
            0xc6,
            0xc7,
            0xc9,
            0xca,
            0xcb,
            0xcd,
            0xce,
            0xcf,
        )
    }
}
