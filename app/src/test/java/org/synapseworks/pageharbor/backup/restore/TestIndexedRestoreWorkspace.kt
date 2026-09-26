package org.synapseworks.pageharbor.backup.restore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.synapseworks.pageharbor.backup.format.BackupChecksum
import org.synapseworks.pageharbor.backup.format.BackupDocumentRecord
import org.synapseworks.pageharbor.backup.format.BackupFolderRecord
import org.synapseworks.pageharbor.backup.format.BackupFormatLimits
import org.synapseworks.pageharbor.backup.format.BackupFormatValidator
import org.synapseworks.pageharbor.backup.format.BackupIndexedTotals
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.format.BackupObservedEntryRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrDocumentStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineChunkDescriptor
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupPageRecord
import org.synapseworks.pageharbor.backup.format.BackupSourceAssetRecord
import org.synapseworks.pageharbor.backup.format.IndexedVerifiedBackup
import org.synapseworks.pageharbor.library.duplicate.DuplicateKind

/** Test-only collection-backed implementation; production restore always uses the SQLite index. */
internal class TestIndexedRestoreWorkspace(
    private val available: Long = Long.MAX_VALUE,
) : RestoreStagingWorkspace {
    private val areas = linkedMapOf<String, TestIndexedRestoreArea>()
    var createCount: Int = 0
        private set
    var discardCount: Int = 0
        private set

    override fun availableBytes(): Long = available

    override fun create(operationId: String): RestoreStagingArea {
        createCount += 1
        check(operationId !in areas)
        return TestIndexedRestoreArea(operationId) {
            discardCount += 1
            areas.remove(operationId)
        }.also { areas[operationId] = it }
    }

    override fun discard(operationId: String): Boolean = areas.remove(operationId)?.discard() ?: true

    override fun discardOrphans(retainedOperationIds: Set<String>): Boolean {
        areas.keys.filterNot(retainedOperationIds::contains).toList().forEach { operationId ->
            areas.remove(operationId)?.discard()
        }
        return true
    }

    fun area(operationId: String): TestIndexedRestoreArea = areas.getValue(operationId)

    fun removeAsset(operationId: String, path: String) = area(operationId).removeAsset(path)

    fun operationIds(): Set<String> = areas.keys.toSet()

    fun isEmpty(): Boolean = areas.isEmpty()
}

internal class TestIndexedRestoreArea(
    override val operationId: String,
    private val onDiscard: () -> Unit,
) : IndexedRestoreStagingArea {
    private val assets = linkedMapOf<String, ByteArrayOutputStream>()
    private val folders = mutableListOf<BackupFolderRecord>()
    private val documents = mutableListOf<BackupDocumentRecord>()
    private val pages = mutableListOf<BackupPageRecord>()
    private val sources = mutableListOf<BackupSourceAssetRecord>()
    private val ocrDocuments = mutableListOf<BackupOcrDocumentStateRecord>()
    private val ocrPages = mutableListOf<BackupOcrPageStateRecord>()
    private val ocrArtifacts = mutableListOf<BackupOcrArtifactRecord>()
    private val ocrCorrections = mutableListOf<BackupOcrCorrectionRecord>()
    private val ocrLines = mutableListOf<BackupOcrLineRecord>()
    private val observed = linkedMapOf<String, BackupObservedEntryRecord>()
    private val checksums = linkedMapOf<String, BackupChecksum>()
    private val folderOrdinalById = linkedMapOf<String, Int>()
    private val childFolderOrdinalsByParent = linkedMapOf<String, MutableList<Int>>()
    private val readyFolderOrdinals = java.util.TreeSet<Int>()
    private val plannedFolders = linkedMapOf<String, RestoreFolderPlanRow>()
    private val plannedFoldersByTargetId = linkedMapOf<String, RestoreFolderPlanRow>()
    private val plannedFolderPathCharacters = linkedMapOf<String, Int>()
    private val plannedFolderNames = hashSetOf<Pair<String?, String>>()
    private val identities = linkedMapOf<String, RestoreIndexedDocument>()
    private val decisions = linkedMapOf<String, DuplicateKind>()
    private var manifest: BackupManifest? = null
    private var sealed = false
    var discarded = false
        private set

    var openCount = 0
        private set
    var activeOutputs = 0
        private set
    var maximumConcurrentOutputs = 0
        private set
    var maximumWriteRequestBytes = 0
        private set
    var totalBytes = 0L
        private set
    var verified = false
        private set
    var aborted = false
        private set

    override fun acceptManifest(manifest: BackupManifest) {
        check(this.manifest == null)
        this.manifest = manifest
    }

    override fun acceptFolder(ordinal: Int, record: BackupFolderRecord) {
        check(ordinal == folders.size)
        folders += record
        check(folderOrdinalById.put(record.folderId, ordinal) == null)
        if (record.parentFolderId == null) {
            readyFolderOrdinals += ordinal
        } else {
            childFolderOrdinalsByParent.getOrPut(record.parentFolderId, ::mutableListOf) += ordinal
        }
    }

    override fun acceptDocument(ordinal: Int, record: BackupDocumentRecord) {
        check(ordinal == documents.size)
        documents += record
    }

    override fun acceptPage(ordinal: Int, record: BackupPageRecord) {
        check(ordinal == pages.size)
        pages += record
    }

    override fun acceptSourceAsset(ordinal: Int, record: BackupSourceAssetRecord) {
        check(ordinal == sources.size)
        sources += record
    }

    override fun acceptOcrDocumentState(ordinal: Int, record: BackupOcrDocumentStateRecord) {
        check(ordinal == ocrDocuments.size)
        ocrDocuments += record
    }

    override fun acceptOcrPageState(ordinal: Int, record: BackupOcrPageStateRecord) {
        check(ordinal == ocrPages.size)
        ocrPages += record
    }

    override fun acceptOcrArtifact(ordinal: Int, record: BackupOcrArtifactRecord) {
        check(ordinal == ocrArtifacts.size)
        ocrArtifacts += record
    }

    override fun acceptOcrCorrection(ordinal: Int, record: BackupOcrCorrectionRecord) {
        check(ordinal == ocrCorrections.size)
        ocrCorrections += record
    }

    override fun acceptOcrLine(ordinal: Int, record: BackupOcrLineRecord) {
        check(ordinal == ocrLines.size)
        ocrLines += record
    }

    override fun acceptOcrLineChunk(ordinal: Int, descriptor: BackupOcrLineChunkDescriptor) = Unit

    override fun acceptArchivePath(path: String) = Unit

    override fun acceptObservedEntry(record: BackupObservedEntryRecord) {
        check(observed.put(record.path, record) == null)
    }

    override fun acceptChecksum(record: BackupChecksum) {
        check(checksums.put(record.path, record) == null)
    }

    override fun verifyIndexed(
        supportedRequiredFeatures: Set<String>,
        limits: BackupFormatLimits,
        checkCancellation: () -> Unit,
    ): IndexedVerifiedBackup {
        val storedManifest = requireNotNull(manifest)
        checkCancellation()
        BackupFormatValidator.validateCompleteBackup(
            storedManifest,
            folders,
            documents,
            pages,
            sources,
            supportedRequiredFeatures,
            limits,
        )
        require(checksums.isNotEmpty())
        require(checksums.keys == observed.keys)
        checksums.forEach { (path, checksum) ->
            checkCancellation()
            val entry = requireNotNull(observed[path])
            require(checksum.sha256 == entry.sha256 && checksum.byteLength == entry.byteLength)
        }
        sealed = true
        verified()
        return IndexedVerifiedBackup(storedManifest, this)
    }

    override fun foldersPage(afterOrdinal: Int, limit: Int): List<BackupFolderRecord> =
        folders.drop(afterOrdinal + 1).take(limit)

    override fun documentsPage(afterOrdinal: Int, limit: Int): List<BackupDocumentRecord> =
        documents.drop(afterOrdinal + 1).take(limit)

    override fun pagesPage(documentId: String, afterPosition: Int, limit: Int): List<BackupPageRecord> =
        pages.asSequence().filter { it.documentId == documentId && it.position > afterPosition }
            .sortedBy(BackupPageRecord::position).take(limit).toList()

    override fun sourceAssetsPage(
        documentId: String,
        afterSourceId: String?,
        limit: Int,
    ): List<BackupSourceAssetRecord> = sources.asSequence()
        .filter { it.documentId == documentId && (afterSourceId == null || it.sourceId > afterSourceId) }
        .sortedBy(BackupSourceAssetRecord::sourceId).take(limit).toList()

    override fun ocrDocumentState(documentId: String): BackupOcrDocumentStateRecord? =
        ocrDocuments.singleOrNull { it.documentId == documentId }

    override fun ocrPageState(pageId: String): BackupOcrPageStateRecord? =
        ocrPages.singleOrNull { it.pageId == pageId }

    override fun ocrArtifactsPage(
        pageId: String,
        afterRevision: Long,
        limit: Int,
    ): List<BackupOcrArtifactRecord> = ocrArtifacts.asSequence()
        .filter { it.pageId == pageId && it.artifactRevision > afterRevision }
        .sortedBy(BackupOcrArtifactRecord::artifactRevision).take(limit).toList()

    override fun ocrArtifactCount(pageId: String): Int = ocrArtifacts.count { it.pageId == pageId }

    override fun ocrCorrection(pageId: String): BackupOcrCorrectionRecord? =
        ocrCorrections.singleOrNull { it.pageId == pageId }

    override fun ocrLinesPage(
        pageId: String,
        artifactRevision: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<BackupOcrLineRecord> = ocrLines.asSequence()
        .filter {
            it.pageId == pageId && it.artifactRevision == artifactRevision &&
                it.lineOrdinal > afterOrdinal
        }
        .sortedBy(BackupOcrLineRecord::lineOrdinal).take(limit).toList()

    override fun totals(): BackupIndexedTotals = BackupIndexedTotals(
        folderCount = folders.size,
        documentCount = documents.size,
        pageCount = pages.size,
        sourceAssetCount = sources.size,
        ocrDocumentStateCount = ocrDocuments.size,
        ocrPageStateCount = ocrPages.size,
        ocrArtifactCount = ocrArtifacts.size,
        ocrCorrectionCount = ocrCorrections.size,
        ocrLineCount = ocrLines.size,
        metadataByteEstimate = 0L,
        ocrTextByteCount = ocrArtifacts.sumOf { it.rawText.length.toLong() } +
            ocrCorrections.sumOf { it.correctedText.length.toLong() },
    )

    override fun unplannedReadyFoldersPage(limit: Int): List<BackupFolderRecord> =
        readyFolderOrdinals.asSequence().take(limit).map(folders::get).toList()

    override fun targetFolderId(originalFolderId: String): String? =
        plannedFolders[originalFolderId]?.folder?.folderId

    override fun targetFolderSearchPath(originalFolderId: String): String? {
        var current = plannedFolders[originalFolderId] ?: return null
        val expectedCharacters = requireNotNull(plannedFolderPathCharacters[current.folder.folderId])
        val builder = RestoreFolderSearchPathBuilder()
        val visited = HashSet<String>()
        var depth = 0
        while (true) {
            check(visited.add(current.folder.folderId))
            builder.add(
                targetId = current.folder.folderId,
                targetParentId = current.folder.parentFolderId,
                name = current.folder.name,
                depthFromLeaf = depth++,
            )
            val parentId = current.folder.parentFolderId ?: break
            current = requireNotNull(plannedFoldersByTargetId[parentId])
        }
        return builder.build(expectedCharacters)
    }

    override fun plannedFolderNameExists(
        targetParentFolderId: String?,
        normalizedName: String,
    ): Boolean = targetParentFolderId to normalizedName in plannedFolderNames

    override fun recordPlannedFolder(folder: RestoreFolderToCreate) {
        val ordinal = requireNotNull(folderOrdinalById[folder.originalFolderId])
        check(folder.originalFolderId !in plannedFolders)
        val row = RestoreFolderPlanRow(ordinal, folder)
        val parentPathCharacters = folder.parentFolderId?.let(plannedFolderPathCharacters::getValue)
        val pathCharacters = restoreFolderSearchPathCharacters(parentPathCharacters, folder.name)
        plannedFolders[folder.originalFolderId] = row
        check(plannedFoldersByTargetId.put(folder.folderId, row) == null)
        check(plannedFolderPathCharacters.put(folder.folderId, pathCharacters) == null)
        check(plannedFolderNames.add(folder.parentFolderId to folder.normalizedName))
        check(readyFolderOrdinals.remove(ordinal))
        childFolderOrdinalsByParent[folder.originalFolderId].orEmpty().forEach(readyFolderOrdinals::add)
    }

    override fun plannedFolderCount(): Int = plannedFolders.size

    override fun plannedFoldersPage(afterOrdinal: Int, limit: Int): List<RestoreFolderPlanRow> =
        plannedFolders.values.asSequence().filter { it.ordinal > afterOrdinal }
            .sortedBy(RestoreFolderPlanRow::ordinal).take(limit).toList()

    override fun recordDocumentIdentity(source: RestoreIndexedDocument) {
        check(identities.put(source.document.documentId, source) == null)
    }

    override fun documentIdentitiesPage(afterOrdinal: Int, limit: Int): List<RestoreIndexedDocumentRow> =
        documents.withIndex().asSequence().filter { it.index > afterOrdinal }.mapNotNull { indexed ->
            identities[indexed.value.documentId]?.let { RestoreIndexedDocumentRow(indexed.index, it) }
        }.take(limit).toList()

    override fun recordRestoreDecision(documentId: String, duplicateKind: DuplicateKind) {
        check(documentId in identities && decisions.put(documentId, duplicateKind) == null)
    }

    override fun restorePlanPage(afterOrdinal: Int, limit: Int): List<RestorePlannedDocumentRow> =
        documents.withIndex().asSequence().filter { it.index > afterOrdinal }.mapNotNull { indexed ->
            identities[indexed.value.documentId]?.let { source ->
                decisions[indexed.value.documentId]?.let { kind ->
                    RestorePlannedDocumentRow(
                        indexed.index,
                        RestorePlannedDocument(source, kind),
                    )
                }
            }
        }.take(limit).toList()

    override fun open(relativePath: String): OutputStream {
        check(!sealed && !discarded)
        check(relativePath !in assets)
        openCount += 1
        activeOutputs += 1
        maximumConcurrentOutputs = maxOf(maximumConcurrentOutputs, activeOutputs)
        val bytes = ByteArrayOutputStream()
        assets[relativePath] = bytes
        return object : OutputStream() {
            private var closed = false

            override fun write(value: Int) {
                maximumWriteRequestBytes = maxOf(maximumWriteRequestBytes, 1)
                bytes.write(value)
                totalBytes += 1
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                maximumWriteRequestBytes = maxOf(maximumWriteRequestBytes, length)
                bytes.write(buffer, offset, length)
                totalBytes += length
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    activeOutputs -= 1
                }
            }
        }
    }

    override fun openMetadata(relativePath: String): OutputStream = ByteArrayOutputStream()

    override fun openAsset(relativePath: String): InputStream {
        check(verified && !discarded)
        return ByteArrayInputStream(assets[relativePath]?.toByteArray() ?: throw IOException("missing asset"))
    }

    override fun verified() {
        verified = true
    }

    override fun abort() {
        aborted = true
        discard()
    }

    override fun discard(): Boolean {
        if (!discarded) {
            discarded = true
            assets.clear()
            onDiscard()
        }
        return true
    }

    fun removeAsset(path: String) {
        assets.remove(path)
    }
}
