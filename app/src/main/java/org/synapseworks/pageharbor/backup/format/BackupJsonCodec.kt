package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object BackupJsonCodec {
    fun writeManifest(
        manifest: BackupManifest,
        destination: OutputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ) {
        val bytes = encodeManifest(manifest).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > limits.maximumManifestBytes) limitExceeded("The manifest is too large.")
        destination.write(bytes)
    }

    fun readManifest(
        source: InputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): BackupManifest {
        val bytes = readBounded(source, limits.maximumManifestBytes, "The manifest is too large.")
        val root = parseJsonObject(decodeStrictUtf8(bytes), limits)
        val producer = root.requiredObject("producer")
        val summary = root.requiredObject("summary")
        val metadata = root.requiredObject("metadata")
        val integrity = root.requiredObject("integrity")
        val requiredFeatures = root.requiredArray("requiredFeatures").values.map { value ->
            (value as? JsonString)?.value
                ?: throw backupFailure(
                    BackupFormatFailure.INVALID_JSON,
                    "Every required feature must be a string.",
                )
        }
        return BackupManifest(
            formatVersion = root.requiredInt("formatVersion"),
            minimumReaderVersion = root.requiredInt("minimumReaderVersion"),
            requiredFeatures = requiredFeatures.toList(),
            backupId = root.requiredString("backupId"),
            createdAtEpochMillis = root.requiredLong("createdAtEpochMillis"),
            producer = BackupProducer(
                applicationId = producer.requiredString("applicationId"),
                versionName = producer.requiredString("versionName"),
                versionCode = producer.requiredInt("versionCode"),
            ),
            summary = BackupSummary(
                folderCount = summary.requiredInt("folderCount"),
                documentCount = summary.requiredInt("documentCount"),
                pageCount = summary.requiredInt("pageCount"),
                sourceAssetCount = summary.requiredInt("sourceAssetCount"),
                contentByteLength = summary.requiredLong("contentByteLength"),
            ),
            metadata = BackupMetadataPaths(
                folders = metadata.requiredString("folders"),
                documents = metadata.requiredString("documents"),
                pages = metadata.requiredString("pages"),
                sourceAssets = metadata.requiredString("sourceAssets"),
            ),
            integrity = BackupIntegrity(
                algorithm = integrity.requiredString("algorithm"),
                checksumsEntry = integrity.requiredString("checksumsEntry"),
            ),
        )
    }

    fun writeFolders(
        records: Sequence<BackupFolderRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): Int = writeJsonLines(
        records = records,
        destination = destination,
        maximumRecords = limits.maximumFolderCount,
        limits = limits,
        encode = ::encodeFolder,
    )

    fun readFolders(
        source: InputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
        accept: (BackupFolderRecord) -> Unit,
    ): Int = readJsonLines(source, limits.maximumFolderCount, limits, ::decodeFolder, accept)

    fun writeDocuments(
        records: Sequence<BackupDocumentRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): Int = writeJsonLines(
        records = records,
        destination = destination,
        maximumRecords = limits.maximumDocumentCount,
        limits = limits,
        encode = ::encodeDocument,
    )

    fun readDocuments(
        source: InputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
        accept: (BackupDocumentRecord) -> Unit,
    ): Int = readJsonLines(source, limits.maximumDocumentCount, limits, ::decodeDocument, accept)

    fun writePages(
        records: Sequence<BackupPageRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): Int = writeJsonLines(
        records = records,
        destination = destination,
        maximumRecords = limits.maximumPageCount,
        limits = limits,
        encode = ::encodePage,
    )

    fun readPages(
        source: InputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
        accept: (BackupPageRecord) -> Unit,
    ): Int = readJsonLines(source, limits.maximumPageCount, limits, ::decodePage, accept)

    fun writeSourceAssets(
        records: Sequence<BackupSourceAssetRecord>,
        destination: OutputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
    ): Int = writeJsonLines(
        records = records,
        destination = destination,
        maximumRecords = limits.maximumSourceAssetCount,
        limits = limits,
        encode = ::encodeSourceAsset,
    )

    fun readSourceAssets(
        source: InputStream,
        limits: BackupFormatLimits = BackupFormatLimits(),
        accept: (BackupSourceAssetRecord) -> Unit,
    ): Int = readJsonLines(
        source,
        limits.maximumSourceAssetCount,
        limits,
        ::decodeSourceAsset,
        accept,
    )

    private fun encodeManifest(manifest: BackupManifest): String = buildString {
        append('{')
        append("\"formatVersion\":").append(manifest.formatVersion)
        append(",\"minimumReaderVersion\":").append(manifest.minimumReaderVersion)
        append(",\"requiredFeatures\":[")
        manifest.requiredFeatures.forEachIndexed { index, feature ->
            if (index > 0) append(',')
            append(jsonString(feature))
        }
        append(']')
        append(",\"backupId\":").append(jsonString(manifest.backupId))
        append(",\"createdAtEpochMillis\":").append(manifest.createdAtEpochMillis)
        append(",\"producer\":{")
        append("\"applicationId\":").append(jsonString(manifest.producer.applicationId))
        append(",\"versionName\":").append(jsonString(manifest.producer.versionName))
        append(",\"versionCode\":").append(manifest.producer.versionCode)
        append('}')
        append(",\"summary\":{")
        append("\"folderCount\":").append(manifest.summary.folderCount)
        append(",\"documentCount\":").append(manifest.summary.documentCount)
        append(",\"pageCount\":").append(manifest.summary.pageCount)
        append(",\"sourceAssetCount\":").append(manifest.summary.sourceAssetCount)
        append(",\"contentByteLength\":").append(manifest.summary.contentByteLength)
        append('}')
        append(",\"metadata\":{")
        append("\"folders\":").append(jsonString(manifest.metadata.folders))
        append(",\"documents\":").append(jsonString(manifest.metadata.documents))
        append(",\"pages\":").append(jsonString(manifest.metadata.pages))
        append(",\"sourceAssets\":").append(jsonString(manifest.metadata.sourceAssets))
        append('}')
        append(",\"integrity\":{")
        append("\"algorithm\":").append(jsonString(manifest.integrity.algorithm))
        append(",\"checksumsEntry\":").append(jsonString(manifest.integrity.checksumsEntry))
        append("}}")
    }

    private fun encodeFolder(record: BackupFolderRecord): String = buildString {
        append('{')
        append("\"folderId\":").append(jsonString(record.folderId))
        append(",\"name\":").append(jsonString(record.name))
        append(",\"parentFolderId\":").append(nullableJsonString(record.parentFolderId))
        append(",\"createdAtEpochMillis\":").append(record.createdAtEpochMillis)
        append(",\"modifiedAtEpochMillis\":").append(record.modifiedAtEpochMillis)
        append('}')
    }

    private fun decodeFolder(line: String, limits: BackupFormatLimits): BackupFolderRecord {
        val root = parseJsonObject(line, limits)
        return BackupFolderRecord(
            folderId = root.requiredString("folderId"),
            name = root.requiredString("name"),
            parentFolderId = root.requiredNullableString("parentFolderId"),
            createdAtEpochMillis = root.requiredLong("createdAtEpochMillis"),
            modifiedAtEpochMillis = root.requiredLong("modifiedAtEpochMillis"),
        )
    }

    private fun encodeDocument(record: BackupDocumentRecord): String = buildString {
        append('{')
        append("\"documentId\":").append(jsonString(record.documentId))
        append(",\"folderId\":").append(nullableJsonString(record.folderId))
        append(",\"title\":").append(jsonString(record.title))
        append(",\"createdAtEpochMillis\":").append(record.createdAtEpochMillis)
        append(",\"modifiedAtEpochMillis\":").append(record.modifiedAtEpochMillis)
        append(",\"contentHashVersion\":").append(record.contentHashVersion)
        append(",\"contentSha256\":").append(nullableJsonString(record.contentSha256))
        append(",\"pageCount\":").append(record.pageCount)
        append(",\"sourceAssetCount\":").append(record.sourceAssetCount)
        append('}')
    }

    private fun decodeDocument(line: String, limits: BackupFormatLimits): BackupDocumentRecord {
        val root = parseJsonObject(line, limits)
        return BackupDocumentRecord(
            documentId = root.requiredString("documentId"),
            folderId = root.requiredNullableString("folderId"),
            title = root.requiredString("title"),
            createdAtEpochMillis = root.requiredLong("createdAtEpochMillis"),
            modifiedAtEpochMillis = root.requiredLong("modifiedAtEpochMillis"),
            contentHashVersion = root.requiredInt("contentHashVersion"),
            contentSha256 = root.requiredNullableString("contentSha256"),
            pageCount = root.requiredInt("pageCount"),
            sourceAssetCount = root.requiredInt("sourceAssetCount"),
        )
    }

    private fun encodePage(record: BackupPageRecord): String = buildString {
        append('{')
        append("\"pageId\":").append(jsonString(record.pageId))
        append(",\"documentId\":").append(jsonString(record.documentId))
        append(",\"position\":").append(record.position)
        append(",\"relativePath\":").append(jsonString(record.relativePath))
        append(",\"mimeType\":").append(jsonString(record.mimeType))
        append(",\"sha256\":").append(jsonString(record.sha256))
        append(",\"byteLength\":").append(record.byteLength)
        append(",\"width\":").append(record.width)
        append(",\"height\":").append(record.height)
        append(",\"rotationDegrees\":").append(record.rotationDegrees)
        append(",\"filterName\":").append(jsonString(record.filterName))
        append(",\"ocrText\":").append(nullableJsonString(record.ocrText))
        append(",\"ocrError\":").append(nullableJsonString(record.ocrError))
        append(",\"sourcePageIndex\":").append(record.sourcePageIndex ?: "null")
        append('}')
    }

    private fun decodePage(line: String, limits: BackupFormatLimits): BackupPageRecord {
        val root = parseJsonObject(line, limits)
        return BackupPageRecord(
            pageId = root.requiredString("pageId"),
            documentId = root.requiredString("documentId"),
            position = root.requiredInt("position"),
            relativePath = root.requiredString("relativePath"),
            mimeType = root.requiredString("mimeType"),
            sha256 = root.requiredString("sha256"),
            byteLength = root.requiredLong("byteLength"),
            width = root.requiredInt("width"),
            height = root.requiredInt("height"),
            rotationDegrees = root.requiredInt("rotationDegrees"),
            filterName = root.requiredString("filterName"),
            ocrText = root.requiredNullableString("ocrText"),
            ocrError = root.requiredNullableString("ocrError"),
            sourcePageIndex = root.optionalNullableInt("sourcePageIndex"),
        )
    }

    private fun encodeSourceAsset(record: BackupSourceAssetRecord): String = buildString {
        append('{')
        append("\"sourceId\":").append(jsonString(record.sourceId))
        append(",\"documentId\":").append(jsonString(record.documentId))
        append(",\"role\":").append(jsonString(record.role))
        append(",\"relativePath\":").append(jsonString(record.relativePath))
        append(",\"mimeType\":").append(jsonString(record.mimeType))
        append(",\"sha256\":").append(jsonString(record.sha256))
        append(",\"byteLength\":").append(record.byteLength)
        append(",\"sourceModifiedAtEpochMillis\":")
            .append(record.sourceModifiedAtEpochMillis ?: "null")
        append(",\"matchesCurrentRevision\":").append(record.matchesCurrentRevision)
        append('}')
    }

    private fun decodeSourceAsset(line: String, limits: BackupFormatLimits): BackupSourceAssetRecord {
        val root = parseJsonObject(line, limits)
        return BackupSourceAssetRecord(
            sourceId = root.requiredString("sourceId"),
            documentId = root.requiredString("documentId"),
            role = root.requiredString("role"),
            relativePath = root.requiredString("relativePath"),
            mimeType = root.requiredString("mimeType"),
            sha256 = root.requiredString("sha256"),
            byteLength = root.requiredLong("byteLength"),
            sourceModifiedAtEpochMillis = root.requiredNullableLong("sourceModifiedAtEpochMillis"),
            matchesCurrentRevision = root.requiredBoolean("matchesCurrentRevision"),
        )
    }

    private fun <T> writeJsonLines(
        records: Sequence<T>,
        destination: OutputStream,
        maximumRecords: Int,
        limits: BackupFormatLimits,
        encode: (T) -> String,
    ): Int {
        var count = 0
        var totalBytes = 0L
        records.forEach { record ->
            count += 1
            if (count > maximumRecords) limitExceeded("A metadata record count exceeds its limit.")
            val bytes = encode(record).toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > limits.maximumJsonLineBytes) limitExceeded("A JSONL record is too large.")
            totalBytes = checkedAdd(totalBytes, bytes.size.toLong() + 1L)
            if (totalBytes > limits.maximumMetadataEntryBytes) {
                limitExceeded("A metadata stream is too large.")
            }
            destination.write(bytes)
            destination.write('\n'.code)
        }
        return count
    }

    private fun <T> readJsonLines(
        source: InputStream,
        maximumRecords: Int,
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
            if (recordCount > maximumRecords) limitExceeded("A metadata record count exceeds its limit.")
            accept(decode(decodeStrictUtf8(bytes), limits))
        }

        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            totalBytes = checkedAdd(totalBytes, count.toLong())
            if (totalBytes > limits.maximumMetadataEntryBytes) {
                limitExceeded("A metadata stream is too large.")
            }
            for (index in 0 until count) {
                if (buffer[index] == '\n'.code.toByte()) {
                    consumeLine()
                } else {
                    if (line.size() >= limits.maximumJsonLineBytes) {
                        limitExceeded("A JSONL record is too large.")
                    }
                    line.write(buffer[index].toInt())
                }
            }
        }
        if (line.size() > 0) consumeLine()
        return recordCount
    }

    private fun readBounded(source: InputStream, maximumBytes: Int, message: String): ByteArray {
        val destination = ByteArrayOutputStream(minOf(8 * 1024, maximumBytes))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > maximumBytes - count) limitExceeded(message)
            destination.write(buffer, 0, count)
            total += count
        }
        return destination.toByteArray()
    }
}

internal fun checkedAdd(left: Long, right: Long): Long {
    if (right > 0 && left > Long.MAX_VALUE - right) limitExceeded("A byte count overflowed.")
    return left + right
}

internal fun limitExceeded(message: String): Nothing = throw backupFailure(
    BackupFormatFailure.LIMIT_EXCEEDED,
    message,
)
