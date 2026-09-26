package org.synapseworks.pageharbor.backup.format

/** Test-fixture validator for legacy record sources; production writes disk-prepared sources. */
internal object BackupStreamingValidator {
    fun prepareManifestForWrite(
        manifest: BackupManifest,
        records: BackupRecordSource,
        supportedRequiredFeatures: Set<String>,
        limits: BackupFormatLimits,
    ): BackupManifest {
        BackupFormatValidator.validateCompatibility(manifest, supportedRequiredFeatures, limits)
        val index = validateBaseRecords(manifest, records, limits)
        val prepared = when (manifest.formatVersion) {
            RME_BACKUP_FORMAT_VERSION_V1 -> {
                if (records.hasAnyV2OcrRecords()) {
                    throw backupFailure(
                        BackupFormatFailure.UNSUPPORTED_VERSION,
                        "V2 OCR metadata cannot be written to a v1 backup.",
                    )
                }
                manifest
            }

            RME_BACKUP_FORMAT_VERSION_V2 -> manifest.copy(
                ocr = BackupOcrFormatValidator.analyze(records, index, limits),
            )

            else -> throw backupFailure(
                BackupFormatFailure.UNSUPPORTED_VERSION,
                "The backup format requires an unsupported writer version.",
            )
        }
        BackupFormatValidator.validateCompatibility(prepared, supportedRequiredFeatures, limits)
        return prepared
    }

    private fun validateBaseRecords(
        manifest: BackupManifest,
        records: BackupRecordSource,
        limits: BackupFormatLimits,
    ): BackupBaseRecordIndex {
        val folderParents = HashMap<String, String?>()
        var folderCount = 0
        records.folders().forEach { folder ->
            folderCount = incrementBounded(folderCount, limits.maximumFolderCount)
            BackupPathValidator.requireStableId(folder.folderId, "folderId")
            requireBoundedText(folder.name, 80, "A folder name is invalid.")
            requireTimestamps(folder.createdAtEpochMillis, folder.modifiedAtEpochMillis)
            if (folderParents.containsKey(folder.folderId)) duplicateId("folder")
            folderParents[folder.folderId] = folder.parentFolderId
        }
        folderParents.forEach { (folderId, parentId) ->
            parentId ?: return@forEach
            BackupPathValidator.requireStableId(parentId, "parentFolderId")
            if (parentId == folderId || parentId !in folderParents) {
                relationshipInvalid("A folder parent relationship is invalid.")
            }
        }
        requireAcyclicFolders(folderParents)

        val documents = HashMap<String, DocumentCounts>()
        val documentOrder = HashMap<String, Int>()
        var documentCount = 0
        records.documents().forEach { document ->
            documentCount = incrementBounded(documentCount, limits.maximumDocumentCount)
            BackupPathValidator.requireStableId(document.documentId, "documentId")
            document.folderId?.let { folderId ->
                BackupPathValidator.requireStableId(folderId, "folderId")
                if (folderId !in folderParents) relationshipInvalid("A document folder relationship is invalid.")
            }
            requireBoundedText(document.title, 120, "A document title is invalid.")
            requireTimestamps(document.createdAtEpochMillis, document.modifiedAtEpochMillis)
            if (document.contentHashVersion <= 0) invalidMetadata("A content hash version is invalid.")
            document.contentSha256?.let(::requireSha256)
            if (document.pageCount < 0 || document.sourceAssetCount < 0) {
                invalidMetadata("A document count is invalid.")
            }
            if (documents.put(
                    document.documentId,
                    DocumentCounts(document.pageCount, document.sourceAssetCount),
                ) != null
            ) {
                duplicateId("document")
            }
            documentOrder[document.documentId] = documentCount - 1
        }

        val pageIds = HashSet<String>()
        val pageDocumentIds = HashMap<String, String>()
        val pageOrder = HashMap<String, BackupPageOrder>()
        val pagePositions = HashMap<String, MutableSet<Int>>()
        val pagesWithSourceIndex = HashSet<String>()
        val assetPaths = HashSet<String>()
        val canonicalAssetPaths = HashSet<String>()
        var pageCount = 0
        var contentByteLength = 0L
        records.pages().forEach { page ->
            pageCount = incrementBounded(pageCount, limits.maximumPageCount)
            BackupPathValidator.requireStableId(page.pageId, "pageId")
            BackupPathValidator.requireStableId(page.documentId, "documentId")
            if (!pageIds.add(page.pageId)) duplicateId("page")
            if (page.documentId !in documents) {
                relationshipInvalid("A page document relationship is invalid.")
            }
            pageDocumentIds[page.pageId] = page.documentId
            pageOrder[page.pageId] = BackupPageOrder(
                documentOrdinal = documentOrder.getValue(page.documentId),
                pagePosition = page.position,
            )
            if (page.position < 0) invalidMetadata("A page position is invalid.")
            if (!pagePositions.getOrPut(page.documentId, ::HashSet).add(page.position)) {
                relationshipInvalid("A document has duplicate page positions.")
            }
            requireSha256(page.sha256)
            requireAssetLength(page.byteLength, limits)
            if (page.width <= 0 || page.height <= 0) invalidMetadata("A page dimension is invalid.")
            if (page.rotationDegrees !in VALID_ROTATIONS) invalidMetadata("A page rotation is invalid.")
            if (page.filterName !in SUPPORTED_FILTERS) invalidMetadata("A page filter is unsupported.")
            if (page.sourcePageIndex != null) {
                if (page.sourcePageIndex < 0) invalidMetadata("A source page index is invalid.")
                pagesWithSourceIndex += page.pageId
            }
            BackupPathValidator.requirePageAssetPath(page)
            addAssetPath(page.relativePath, assetPaths, canonicalAssetPaths)
            contentByteLength = checkedAdd(contentByteLength, page.byteLength)
        }

        val sourceCounts = HashMap<String, Int>()
        val sourceIds = HashSet<String>()
        var sourceAssetCount = 0
        records.sourceAssets().forEach { source ->
            sourceAssetCount = incrementBounded(sourceAssetCount, limits.maximumSourceAssetCount)
            BackupPathValidator.requireStableId(source.sourceId, "sourceId")
            BackupPathValidator.requireStableId(source.documentId, "documentId")
            if (!sourceIds.add(source.sourceId)) duplicateId("source asset")
            if (source.documentId !in documents) relationshipInvalid("A source asset document relationship is invalid.")
            if (source.role != "ORIGINAL_DOCUMENT" || source.mimeType != "application/pdf") {
                invalidMetadata("A source asset has an unsupported role or MIME type.")
            }
            requireSha256(source.sha256)
            requireAssetLength(source.byteLength, limits)
            source.sourceModifiedAtEpochMillis?.let {
                if (it < 0L) invalidMetadata("A source timestamp is invalid.")
            }
            BackupPathValidator.requireSourceAssetPath(source)
            addAssetPath(source.relativePath, assetPaths, canonicalAssetPaths)
            sourceCounts[source.documentId] = (sourceCounts[source.documentId] ?: 0) + 1
            contentByteLength = checkedAdd(contentByteLength, source.byteLength)
        }

        documents.forEach { (documentId, expected) ->
            val positions = pagePositions[documentId].orEmpty()
            if (positions.size != expected.pageCount || positions.any { it !in 0 until expected.pageCount }) {
                relationshipInvalid("A document page count or position sequence is invalid.")
            }
            if ((sourceCounts[documentId] ?: 0) != expected.sourceAssetCount) {
                relationshipInvalid("A document source-asset count is invalid.")
            }
        }
        pagesWithSourceIndex.forEach { pageId ->
            if ((sourceCounts[pageDocumentIds.getValue(pageId)] ?: 0) == 0) {
                relationshipInvalid("A page source index has no source asset.")
            }
        }
        if (manifest.summary.folderCount != folderCount ||
            manifest.summary.documentCount != documentCount ||
            manifest.summary.pageCount != pageCount ||
            manifest.summary.sourceAssetCount != sourceAssetCount ||
            manifest.summary.contentByteLength != contentByteLength
        ) {
            relationshipInvalid("The manifest summary does not match the metadata.")
        }
        return BackupBaseRecordIndex(
            documents.keys.toSet(),
            pageIds.toSet(),
            pageDocumentIds.toMap(),
            pageOrder.toMap(),
            records.pages().associateBy(BackupPageRecord::pageId),
        )
    }

    private fun BackupRecordSource.hasAnyV2OcrRecords(): Boolean =
        ocrDocumentStates().iterator().hasNext() ||
            ocrPageStates().iterator().hasNext() ||
            ocrArtifacts().iterator().hasNext() ||
            ocrCorrections().iterator().hasNext() ||
            ocrLines().iterator().hasNext()

    private fun requireAcyclicFolders(folderParents: Map<String, String?>) {
        val state = HashMap<String, Int>()
        folderParents.keys.forEach { start ->
            if (state[start] == 2) return@forEach
            val chain = ArrayList<String>()
            var current: String? = start
            while (current != null && state[current] != 2) {
                if (state[current] == 1) relationshipInvalid("The folder hierarchy contains a cycle.")
                state[current] = 1
                chain += current
                current = folderParents[current]
            }
            chain.forEach { state[it] = 2 }
        }
    }

    private fun incrementBounded(current: Int, maximum: Int): Int {
        if (current >= maximum) limitExceeded("A metadata record count exceeds configured limits.")
        return current + 1
    }

    private fun requireAssetLength(byteLength: Long, limits: BackupFormatLimits) {
        if (byteLength !in 1..limits.maximumSingleAssetBytes) {
            limitExceeded("An asset byte length exceeds configured limits.")
        }
    }

    private fun requireTimestamps(created: Long, modified: Long) {
        if (created < 0 || modified < created) invalidMetadata("A metadata timestamp is invalid.")
    }

    private fun requireBoundedText(value: String, maximum: Int, message: String) {
        if (value.isBlank() || value.length > maximum || value.any { it == '\u0000' }) invalidMetadata(message)
    }

    private fun requireSha256(value: String) {
        if (!SHA256.matches(value)) invalidMetadata("A SHA-256 value is invalid.")
    }

    private fun addAssetPath(path: String, exact: MutableSet<String>, canonical: MutableSet<String>) {
        if (!exact.add(path) || !canonical.add(BackupPathValidator.canonicalCollisionKey(path))) {
            throw backupFailure(
                BackupFormatFailure.DUPLICATE_ENTRY,
                "Asset metadata contains duplicate or colliding paths.",
            )
        }
    }

    private fun duplicateId(type: String): Nothing = relationshipInvalid("The metadata contains a duplicate $type ID.")

    private fun relationshipInvalid(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.RELATIONSHIP_INVALID,
        message,
    )

    private fun invalidMetadata(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_METADATA,
        message,
    )

    private data class DocumentCounts(val pageCount: Int, val sourceAssetCount: Int)

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val VALID_ROTATIONS = setOf(0, 90, 180, 270)
    private val SUPPORTED_FILTERS = setOf(
        "ORIGINAL",
        "AUTO_ENHANCE",
        "GRAYSCALE",
        "BLACK_AND_WHITE",
        "HIGH_CONTRAST",
    )
}

internal data class BackupBaseRecordIndex(
    val documentIds: Set<String>,
    val pageIds: Set<String>,
    val pageDocumentIds: Map<String, String>,
    val pageOrder: Map<String, BackupPageOrder>,
    val pagesById: Map<String, BackupPageRecord>,
)

internal data class BackupPageOrder(
    val documentOrdinal: Int,
    val pagePosition: Int,
) : Comparable<BackupPageOrder> {
    override fun compareTo(other: BackupPageOrder): Int {
        val documentComparison = documentOrdinal.compareTo(other.documentOrdinal)
        return if (documentComparison != 0) documentComparison else pagePosition.compareTo(other.pagePosition)
    }
}
