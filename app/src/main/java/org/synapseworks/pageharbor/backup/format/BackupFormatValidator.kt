package org.synapseworks.pageharbor.backup.format

import java.util.UUID

object BackupFormatValidator {
    private val sha256 = Regex("[0-9a-f]{64}")
    private val featureName = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    private val supportedFilters = setOf(
        "ORIGINAL",
        "AUTO_ENHANCE",
        "GRAYSCALE",
        "BLACK_AND_WHITE",
        "HIGH_CONTRAST",
    )

    fun validateCompatibility(
        manifest: BackupManifest,
        supportedRequiredFeatures: Set<String> = emptySet(),
        limits: BackupFormatLimits = BackupFormatLimits(),
    ) {
        if (
            manifest.formatVersion != RME_BACKUP_FORMAT_VERSION ||
            manifest.minimumReaderVersion !in 1..RME_BACKUP_READER_VERSION
        ) {
            throw backupFailure(
                BackupFormatFailure.UNSUPPORTED_VERSION,
                "The backup format requires an unsupported reader version.",
            )
        }
        if (
            manifest.requiredFeatures.distinct().size != manifest.requiredFeatures.size ||
            manifest.requiredFeatures.any { !featureName.matches(it) }
        ) {
            throw backupFailure(
                BackupFormatFailure.INVALID_METADATA,
                "The required-feature list is invalid.",
            )
        }
        if (manifest.requiredFeatures.any { it !in supportedRequiredFeatures }) {
            throw backupFailure(
                BackupFormatFailure.UNSUPPORTED_REQUIRED_FEATURE,
                "The backup requires an unsupported feature.",
            )
        }
        requireCanonicalUuid(manifest.backupId)
        requireNonNegative(manifest.createdAtEpochMillis, "The backup timestamp is invalid.")
        requireBoundedText(manifest.producer.applicationId, 255, "The producer application ID is invalid.")
        requireBoundedText(manifest.producer.versionName, 128, "The producer version is invalid.")
        if (manifest.producer.versionCode <= 0) invalidMetadata("The producer version code is invalid.")
        if (
            manifest.metadata.folders != RME_BACKUP_FOLDERS_PATH ||
            manifest.metadata.documents != RME_BACKUP_DOCUMENTS_PATH ||
            manifest.metadata.pages != RME_BACKUP_PAGES_PATH ||
            manifest.metadata.sourceAssets != RME_BACKUP_SOURCE_ASSETS_PATH ||
            manifest.integrity.algorithm != "SHA-256" ||
            manifest.integrity.checksumsEntry != RME_BACKUP_CHECKSUMS_PATH
        ) {
            invalidMetadata("The v1 manifest declares unsupported metadata or integrity paths.")
        }
        val summary = manifest.summary
        if (
            summary.folderCount !in 0..limits.maximumFolderCount ||
            summary.documentCount !in 0..limits.maximumDocumentCount ||
            summary.pageCount !in 0..limits.maximumPageCount ||
            summary.sourceAssetCount !in 0..limits.maximumSourceAssetCount ||
            summary.contentByteLength !in 0..limits.maximumTotalUncompressedBytes
        ) {
            limitExceeded("The manifest summary exceeds configured limits.")
        }
    }

    fun validateCompleteBackup(
        manifest: BackupManifest,
        folders: List<BackupFolderRecord>,
        documents: List<BackupDocumentRecord>,
        pages: List<BackupPageRecord>,
        sourceAssets: List<BackupSourceAssetRecord>,
        supportedRequiredFeatures: Set<String> = emptySet(),
        limits: BackupFormatLimits = BackupFormatLimits(),
    ) {
        validateCompatibility(manifest, supportedRequiredFeatures, limits)
        if (
            folders.size > limits.maximumFolderCount ||
            documents.size > limits.maximumDocumentCount ||
            pages.size > limits.maximumPageCount ||
            sourceAssets.size > limits.maximumSourceAssetCount
        ) {
            limitExceeded("A metadata record count exceeds configured limits.")
        }

        val foldersById = HashMap<String, BackupFolderRecord>(folders.size)
        folders.forEach { folder ->
            BackupPathValidator.requireStableId(folder.folderId, "folderId")
            requireBoundedText(folder.name, 80, "A folder name is invalid.")
            requireTimestamps(folder.createdAtEpochMillis, folder.modifiedAtEpochMillis)
            if (foldersById.put(folder.folderId, folder) != null) duplicateMetadataId("folder")
        }
        folders.forEach { folder ->
            val parentId = folder.parentFolderId ?: return@forEach
            BackupPathValidator.requireStableId(parentId, "parentFolderId")
            if (parentId == folder.folderId || parentId !in foldersById) {
                relationshipInvalid("A folder parent relationship is invalid.")
            }
        }
        requireAcyclicFolders(foldersById)

        val documentsById = HashMap<String, BackupDocumentRecord>(documents.size)
        documents.forEach { document ->
            BackupPathValidator.requireStableId(document.documentId, "documentId")
            document.folderId?.let { folderId ->
                BackupPathValidator.requireStableId(folderId, "folderId")
                if (folderId !in foldersById) relationshipInvalid("A document folder relationship is invalid.")
            }
            requireBoundedText(document.title, 120, "A document title is invalid.")
            requireTimestamps(document.createdAtEpochMillis, document.modifiedAtEpochMillis)
            if (document.contentHashVersion <= 0) invalidMetadata("A content hash version is invalid.")
            document.contentSha256?.let(::requireSha256)
            if (document.pageCount < 0 || document.sourceAssetCount < 0) {
                invalidMetadata("A document count is invalid.")
            }
            if (documentsById.put(document.documentId, document) != null) {
                duplicateMetadataId("document")
            }
        }

        val pageIds = HashSet<String>(pages.size)
        val pagePositionsByDocument = HashMap<String, MutableSet<Int>>()
        val assetPaths = HashSet<String>()
        val canonicalAssetPaths = HashSet<String>()
        var contentByteLength = 0L
        pages.forEach { page ->
            BackupPathValidator.requireStableId(page.pageId, "pageId")
            BackupPathValidator.requireStableId(page.documentId, "documentId")
            if (!pageIds.add(page.pageId)) duplicateMetadataId("page")
            if (page.documentId !in documentsById) relationshipInvalid("A page document relationship is invalid.")
            if (page.position < 0) invalidMetadata("A page position is invalid.")
            val positions = pagePositionsByDocument.getOrPut(page.documentId) { HashSet() }
            if (!positions.add(page.position)) relationshipInvalid("A document has duplicate page positions.")
            requireSha256(page.sha256)
            requireAssetLength(page.byteLength, limits)
            if (page.width <= 0 || page.height <= 0) invalidMetadata("A page dimension is invalid.")
            if (page.rotationDegrees !in setOf(0, 90, 180, 270)) {
                invalidMetadata("A page rotation is invalid.")
            }
            if (page.filterName !in supportedFilters) invalidMetadata("A page filter is unsupported.")
            if (page.sourcePageIndex != null && page.sourcePageIndex < 0) {
                invalidMetadata("A source page index is invalid.")
            }
            BackupPathValidator.requirePageAssetPath(page)
            addAssetPath(page.relativePath, assetPaths, canonicalAssetPaths)
            contentByteLength = checkedAdd(contentByteLength, page.byteLength)
        }

        val sourceIds = HashSet<String>(sourceAssets.size)
        val sourceCountByDocument = HashMap<String, Int>()
        sourceAssets.forEach { source ->
            BackupPathValidator.requireStableId(source.sourceId, "sourceId")
            BackupPathValidator.requireStableId(source.documentId, "documentId")
            if (!sourceIds.add(source.sourceId)) duplicateMetadataId("source asset")
            if (source.documentId !in documentsById) {
                relationshipInvalid("A source asset document relationship is invalid.")
            }
            if (source.role != "ORIGINAL_DOCUMENT" || source.mimeType != "application/pdf") {
                invalidMetadata("A source asset has an unsupported role or MIME type.")
            }
            requireSha256(source.sha256)
            requireAssetLength(source.byteLength, limits)
            source.sourceModifiedAtEpochMillis?.let { requireNonNegative(it, "A source timestamp is invalid.") }
            BackupPathValidator.requireSourceAssetPath(source)
            addAssetPath(source.relativePath, assetPaths, canonicalAssetPaths)
            sourceCountByDocument[source.documentId] =
                (sourceCountByDocument[source.documentId] ?: 0) + 1
            contentByteLength = checkedAdd(contentByteLength, source.byteLength)
        }

        documents.forEach { document ->
            val positions = pagePositionsByDocument[document.documentId].orEmpty()
            if (positions.size != document.pageCount || positions.any { it !in 0 until document.pageCount }) {
                relationshipInvalid("A document page count or position sequence is invalid.")
            }
            if ((sourceCountByDocument[document.documentId] ?: 0) != document.sourceAssetCount) {
                relationshipInvalid("A document source-asset count is invalid.")
            }
        }
        pages.forEach { page ->
            if (page.sourcePageIndex != null && (sourceCountByDocument[page.documentId] ?: 0) == 0) {
                relationshipInvalid("A page source index has no source asset.")
            }
        }

        if (
            manifest.summary.folderCount != folders.size ||
            manifest.summary.documentCount != documents.size ||
            manifest.summary.pageCount != pages.size ||
            manifest.summary.sourceAssetCount != sourceAssets.size ||
            manifest.summary.contentByteLength != contentByteLength
        ) {
            relationshipInvalid("The manifest summary does not match the metadata.")
        }
    }

    private fun requireAcyclicFolders(foldersById: Map<String, BackupFolderRecord>) {
        val state = HashMap<String, Int>(foldersById.size)
        foldersById.keys.forEach { start ->
            if (state[start] == 2) return@forEach
            val chain = ArrayList<String>()
            var current: String? = start
            while (current != null && state[current] != 2) {
                if (state[current] == 1) relationshipInvalid("The folder hierarchy contains a cycle.")
                state[current] = 1
                chain += current
                current = foldersById[current]?.parentFolderId
            }
            chain.forEach { state[it] = 2 }
        }
    }

    private fun addAssetPath(
        path: String,
        exactPaths: MutableSet<String>,
        canonicalPaths: MutableSet<String>,
    ) {
        if (!exactPaths.add(path) || !canonicalPaths.add(BackupPathValidator.canonicalCollisionKey(path))) {
            throw backupFailure(
                BackupFormatFailure.DUPLICATE_ENTRY,
                "Asset metadata contains duplicate or colliding paths.",
            )
        }
    }

    private fun requireAssetLength(byteLength: Long, limits: BackupFormatLimits) {
        if (byteLength !in 1..limits.maximumSingleAssetBytes) {
            limitExceeded("An asset byte length exceeds configured limits.")
        }
    }

    private fun requireSha256(value: String) {
        if (!sha256.matches(value)) invalidMetadata("A SHA-256 value is invalid.")
    }

    private fun requireCanonicalUuid(value: String) {
        val canonical = runCatching { UUID.fromString(value).toString() }.getOrNull()
        if (canonical != value) invalidMetadata("The backup ID is not a canonical UUID.")
    }

    private fun requireTimestamps(created: Long, modified: Long) {
        if (created < 0 || modified < created) invalidMetadata("A metadata timestamp is invalid.")
    }

    private fun requireNonNegative(value: Long, message: String) {
        if (value < 0) invalidMetadata(message)
    }

    private fun requireBoundedText(value: String, maximumCharacters: Int, message: String) {
        if (value.isBlank() || value.length > maximumCharacters || value.any { it == '\u0000' }) {
            invalidMetadata(message)
        }
    }

    private fun duplicateMetadataId(type: String): Nothing = throw backupFailure(
        BackupFormatFailure.RELATIONSHIP_INVALID,
        "The metadata contains a duplicate $type ID.",
    )

    private fun relationshipInvalid(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.RELATIONSHIP_INVALID,
        message,
    )

    private fun invalidMetadata(message: String): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_METADATA,
        message,
    )
}
