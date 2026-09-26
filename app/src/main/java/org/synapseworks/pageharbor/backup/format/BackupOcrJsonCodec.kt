package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal object BackupOcrJsonCodec {
    fun writeDocumentStates(
        records: Sequence<BackupOcrDocumentStateRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits,
    ): Int = writeJsonLines(
        records,
        destination,
        limits.maximumDocumentCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::encodeDocumentState,
    )

    fun readDocumentStates(
        source: InputStream,
        limits: BackupFormatLimits,
        accept: (BackupOcrDocumentStateRecord) -> Unit,
    ): Int = readJsonLines(
        source,
        limits.maximumDocumentCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::decodeDocumentState,
        accept,
    )

    fun writePageStates(
        records: Sequence<BackupOcrPageStateRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits,
    ): Int = writeJsonLines(
        records,
        destination,
        limits.maximumPageCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::encodePageState,
    )

    fun readPageStates(
        source: InputStream,
        limits: BackupFormatLimits,
        accept: (BackupOcrPageStateRecord) -> Unit,
    ): Int = readJsonLines(
        source,
        limits.maximumPageCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::decodePageState,
        accept,
    )

    fun writeArtifacts(
        records: Sequence<BackupOcrArtifactRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits,
    ): Int = writeJsonLines(
        records,
        destination,
        limits.maximumOcrArtifactCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::encodeArtifact,
    )

    fun readArtifacts(
        source: InputStream,
        limits: BackupFormatLimits,
        accept: (BackupOcrArtifactRecord) -> Unit,
    ): Int = readJsonLines(
        source,
        limits.maximumOcrArtifactCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::decodeArtifact,
        accept,
    )

    fun writeCorrections(
        records: Sequence<BackupOcrCorrectionRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits,
    ): Int = writeJsonLines(
        records,
        destination,
        limits.maximumOcrCorrectionCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::encodeCorrection,
    )

    fun readCorrections(
        source: InputStream,
        limits: BackupFormatLimits,
        accept: (BackupOcrCorrectionRecord) -> Unit,
    ): Int = readJsonLines(
        source,
        limits.maximumOcrCorrectionCount,
        limits.maximumOcrMetadataEntryBytes,
        limits,
        ::decodeCorrection,
        accept,
    )

    fun readLines(
        source: InputStream,
        limits: BackupFormatLimits,
        accept: (BackupOcrLineRecord) -> Unit,
    ): Int = readJsonLines(
        source,
        limits.maximumOcrLineChunkRecords,
        limits.maximumOcrLineChunkBytes,
        limits,
        ::decodeLine,
        accept,
    )

    fun encodeLineBytes(record: BackupOcrLineRecord): ByteArray =
        (encodeLine(record) + '\n').toByteArray(StandardCharsets.UTF_8)

    internal fun encodeDocumentState(record: BackupOcrDocumentStateRecord): String = buildString {
        append('{')
        append("\"documentId\":").append(jsonString(record.documentId))
        append(",\"contentRevision\":").append(record.contentRevision)
        append(",\"scriptPreference\":").append(nullableJsonString(record.scriptPreference))
        append('}')
    }

    internal fun decodeDocumentState(line: String, limits: BackupFormatLimits): BackupOcrDocumentStateRecord {
        val root = parseJsonObject(line, limits)
        return BackupOcrDocumentStateRecord(
            documentId = root.requiredString("documentId"),
            contentRevision = root.requiredLong("contentRevision"),
            scriptPreference = root.requiredNullableString("scriptPreference"),
        )
    }

    internal fun encodePageState(record: BackupOcrPageStateRecord): String = buildString {
        append('{')
        append("\"pageId\":").append(jsonString(record.pageId))
        append(",\"visualRevision\":").append(record.visualRevision)
        append(",\"ocrStateRevision\":").append(record.ocrStateRevision)
        append(",\"activeArtifactRevision\":").append(record.activeArtifactRevision ?: "null")
        append('}')
    }

    internal fun decodePageState(line: String, limits: BackupFormatLimits): BackupOcrPageStateRecord {
        val root = parseJsonObject(line, limits)
        return BackupOcrPageStateRecord(
            pageId = root.requiredString("pageId"),
            visualRevision = root.requiredLong("visualRevision"),
            ocrStateRevision = root.requiredLong("ocrStateRevision"),
            activeArtifactRevision = root.requiredNullableLong("activeArtifactRevision"),
        )
    }

    internal fun encodeArtifact(record: BackupOcrArtifactRecord): String = buildString {
        append('{')
        append("\"pageId\":").append(jsonString(record.pageId))
        append(",\"artifactRevision\":").append(record.artifactRevision)
        append(",\"capturedPageVisualRevision\":")
            .append(record.capturedPageVisualRevision ?: "null")
        append(",\"capturedDocumentContentRevision\":")
            .append(record.capturedDocumentContentRevision ?: "null")
        append(",\"verificationState\":").append(jsonString(record.verificationState.name))
        append(",\"inputFingerprint\":")
        val fingerprint = record.inputFingerprint
        if (fingerprint == null) {
            append("null")
        } else {
            append('{')
            append("\"version\":").append(fingerprint.version)
            append(",\"value\":").append(jsonString(fingerprint.value))
            append(",\"contentSha256\":").append(jsonString(fingerprint.contentSha256))
            append(",\"visualRevision\":").append(fingerprint.visualRevision)
            append(",\"rotationDegrees\":").append(fingerprint.rotationDegrees)
            append(",\"filterName\":").append(jsonString(fingerprint.filterName))
            append(",\"uprightWidth\":").append(fingerprint.uprightWidth)
            append(",\"uprightHeight\":").append(fingerprint.uprightHeight)
            append(",\"coordinateSystemVersion\":").append(fingerprint.coordinateSystemVersion)
            append(",\"transformVersion\":").append(fingerprint.transformVersion)
            append('}')
        }
        append(",\"actualScript\":").append(nullableJsonString(record.actualScript))
        append(",\"recognizerId\":").append(nullableJsonString(record.recognizerId))
        append(",\"pipelineVersion\":").append(nullableJsonString(record.pipelineVersion))
        append(",\"clientVersion\":").append(nullableJsonString(record.clientVersion))
        append(",\"delivery\":").append(nullableJsonString(record.delivery))
        append(",\"recognizedAtEpochMillis\":").append(record.recognizedAtEpochMillis ?: "null")
        append(",\"rawText\":").append(jsonString(record.rawText))
        append(",\"lineCount\":").append(record.lineCount)
        append('}')
    }

    internal fun decodeArtifact(line: String, limits: BackupFormatLimits): BackupOcrArtifactRecord {
        val root = parseJsonObject(line, limits)
        val fingerprint = when (val value = root.fields["inputFingerprint"]) {
            JsonNull -> null
            is JsonObject -> BackupOcrInputFingerprint(
                version = value.requiredInt("version"),
                value = value.requiredString("value"),
                contentSha256 = value.requiredString("contentSha256"),
                visualRevision = value.requiredLong("visualRevision"),
                rotationDegrees = value.requiredInt("rotationDegrees"),
                filterName = value.requiredString("filterName"),
                uprightWidth = value.requiredInt("uprightWidth"),
                uprightHeight = value.requiredInt("uprightHeight"),
                coordinateSystemVersion = value.requiredInt("coordinateSystemVersion"),
                transformVersion = value.requiredInt("transformVersion"),
            )
            else -> throw backupFailure(
                BackupFormatFailure.INVALID_JSON,
                "An OCR input fingerprint is missing or invalid.",
            )
        }
        return BackupOcrArtifactRecord(
            pageId = root.requiredString("pageId"),
            artifactRevision = root.requiredLong("artifactRevision"),
            capturedPageVisualRevision = root.requiredNullableLong("capturedPageVisualRevision"),
            capturedDocumentContentRevision = root.requiredNullableLong("capturedDocumentContentRevision"),
            verificationState = enumValue(root.requiredString("verificationState"), "verification state"),
            inputFingerprint = fingerprint,
            actualScript = root.requiredNullableString("actualScript"),
            recognizerId = root.requiredNullableString("recognizerId"),
            pipelineVersion = root.requiredNullableString("pipelineVersion"),
            clientVersion = root.requiredNullableString("clientVersion"),
            delivery = root.requiredNullableString("delivery"),
            recognizedAtEpochMillis = root.requiredNullableLong("recognizedAtEpochMillis"),
            rawText = root.requiredString("rawText"),
            lineCount = root.requiredInt("lineCount"),
        )
    }

    internal fun encodeCorrection(record: BackupOcrCorrectionRecord): String = buildString {
        append('{')
        append("\"pageId\":").append(jsonString(record.pageId))
        append(",\"baseArtifactRevision\":").append(record.baseArtifactRevision)
        append(",\"correctedText\":").append(jsonString(record.correctedText))
        append(",\"correctedAtEpochMillis\":").append(record.correctedAtEpochMillis)
        append(",\"alignment\":").append(jsonString(record.alignment.name))
        append(",\"lineCorrections\":[")
        record.lineCorrections.forEachIndexed { index, correction ->
            if (index > 0) append(',')
            append('{')
            append("\"lineOrdinal\":").append(correction.lineOrdinal)
            append(",\"correctedText\":").append(jsonString(correction.correctedText))
            append('}')
        }
        append("]}")
    }

    internal fun decodeCorrection(line: String, limits: BackupFormatLimits): BackupOcrCorrectionRecord {
        val root = parseJsonObject(line, limits)
        return BackupOcrCorrectionRecord(
            pageId = root.requiredString("pageId"),
            baseArtifactRevision = root.requiredLong("baseArtifactRevision"),
            correctedText = root.requiredString("correctedText"),
            correctedAtEpochMillis = root.requiredLong("correctedAtEpochMillis"),
            alignment = enumValue(root.requiredString("alignment"), "correction alignment"),
            lineCorrections = root.requiredArray("lineCorrections").values.map { value ->
                val correction = value as? JsonObject ?: throw backupFailure(
                    BackupFormatFailure.INVALID_JSON,
                    "An OCR line correction is invalid.",
                )
                BackupOcrLineCorrectionRecord(
                    lineOrdinal = correction.requiredInt("lineOrdinal"),
                    correctedText = correction.requiredString("correctedText"),
                )
            },
        )
    }

    internal fun encodeLine(record: BackupOcrLineRecord): String = buildString {
        append('{')
        append("\"pageId\":").append(jsonString(record.pageId))
        append(",\"artifactRevision\":").append(record.artifactRevision)
        append(",\"lineOrdinal\":").append(record.lineOrdinal)
        append(",\"rawText\":").append(jsonString(record.rawText))
        append(",\"cornerPoints\":[")
        record.cornerPoints.forEachIndexed { index, point ->
            if (index > 0) append(',')
            appendPoint(point)
        }
        append(']')
        append(",\"baselineStart\":")
        appendPoint(record.baselineStart)
        append(",\"baselineEnd\":")
        appendPoint(record.baselineEnd)
        append(",\"baselineAngleDegrees\":").append(jsonDouble(record.baselineAngleDegrees))
        append(",\"writingOrientation\":").append(nullableJsonString(record.writingOrientation))
        append('}')
    }

    private fun StringBuilder.appendPoint(point: BackupOcrPoint) {
        append('[')
        append(jsonDouble(point.x)).append(',').append(jsonDouble(point.y))
        append(']')
    }

    internal fun decodeLine(line: String, limits: BackupFormatLimits): BackupOcrLineRecord {
        val root = parseJsonObject(line, limits)
        return BackupOcrLineRecord(
            pageId = root.requiredString("pageId"),
            artifactRevision = root.requiredLong("artifactRevision"),
            lineOrdinal = root.requiredInt("lineOrdinal"),
            rawText = root.requiredString("rawText"),
            cornerPoints = root.requiredArray("cornerPoints").values.map(::decodePoint),
            baselineStart = decodePoint(root.fields["baselineStart"]),
            baselineEnd = decodePoint(root.fields["baselineEnd"]),
            baselineAngleDegrees = root.requiredDouble("baselineAngleDegrees"),
            writingOrientation = root.requiredNullableString("writingOrientation"),
        )
    }

    private fun decodePoint(value: JsonValue?): BackupOcrPoint {
        val point = value as? JsonArray
            ?: throw backupFailure(BackupFormatFailure.INVALID_JSON, "An OCR point is invalid.")
        if (point.values.size != 2) {
            throw backupFailure(BackupFormatFailure.INVALID_JSON, "An OCR point is invalid.")
        }
        return BackupOcrPoint(
            x = point.values[0].requiredFiniteNumber(),
            y = point.values[1].requiredFiniteNumber(),
        )
    }

    private fun JsonValue.requiredFiniteNumber(): Double {
        val token = (this as? JsonNumber)?.token
            ?: throw backupFailure(BackupFormatFailure.INVALID_JSON, "An OCR coordinate is invalid.")
        val value = token.toDoubleOrNull()
            ?: throw backupFailure(BackupFormatFailure.INVALID_JSON, "An OCR coordinate is invalid.")
        if (!value.isFinite()) {
            throw backupFailure(BackupFormatFailure.INVALID_JSON, "An OCR coordinate must be finite.")
        }
        return value
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        enumValues<T>().singleOrNull { it.name == value }
            ?: throw backupFailure(BackupFormatFailure.INVALID_METADATA, "An OCR $field is unsupported.")

    private fun <T> writeJsonLines(
        records: Sequence<T>,
        destination: OutputStream,
        maximumRecords: Int,
        maximumBytes: Int,
        limits: BackupFormatLimits,
        encode: (T) -> String,
    ): Int {
        var count = 0
        var totalBytes = 0L
        records.forEach { record ->
            count += 1
            if (count > maximumRecords) limitExceeded("An OCR metadata record count exceeds its limit.")
            val bytes = (encode(record) + '\n').toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > limits.maximumJsonLineBytes) limitExceeded("An OCR JSONL record is too large.")
            totalBytes = checkedAdd(totalBytes, bytes.size.toLong())
            if (totalBytes > maximumBytes) limitExceeded("An OCR metadata stream is too large.")
            destination.write(bytes)
        }
        return count
    }

    private fun <T> readJsonLines(
        source: InputStream,
        maximumRecords: Int,
        maximumBytes: Int,
        limits: BackupFormatLimits,
        decode: (String, BackupFormatLimits) -> T,
        accept: (T) -> Unit,
    ): Int {
        val line = ByteArrayOutputStream(minOf(8 * 1024, limits.maximumJsonLineBytes))
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0L
        var recordCount = 0

        fun consumeLine() {
            var bytes = line.toByteArray()
            line.reset()
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                bytes = bytes.copyOf(bytes.size - 1)
            }
            if (bytes.isEmpty()) return
            recordCount += 1
            if (recordCount > maximumRecords) limitExceeded("An OCR metadata record count exceeds its limit.")
            accept(decode(decodeStrictUtf8(bytes), limits))
        }

        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            totalBytes = checkedAdd(totalBytes, count.toLong())
            if (totalBytes > maximumBytes) limitExceeded("An OCR metadata stream is too large.")
            for (index in 0 until count) {
                if (buffer[index] == '\n'.code.toByte()) {
                    consumeLine()
                } else {
                    if (line.size() >= limits.maximumJsonLineBytes) {
                        limitExceeded("An OCR JSONL record is too large.")
                    }
                    line.write(buffer[index].toInt())
                }
            }
        }
        if (line.size() > 0) consumeLine()
        return recordCount
    }

    private fun jsonDouble(value: Double): String {
        if (!value.isFinite()) {
            throw backupFailure(BackupFormatFailure.INVALID_METADATA, "An OCR coordinate must be finite.")
        }
        return value.toString()
    }
}
