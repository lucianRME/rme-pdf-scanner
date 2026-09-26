package org.synapseworks.pageharbor.backup.format

/** Small-fixture source used only by JVM tests. */
class SnapshotBackupRecordSource(
    folders: Collection<BackupFolderRecord>,
    documents: Collection<BackupDocumentRecord>,
    pages: Collection<BackupPageRecord>,
    sourceAssets: Collection<BackupSourceAssetRecord>,
    ocrDocumentStates: Collection<BackupOcrDocumentStateRecord> = emptyList(),
    ocrPageStates: Collection<BackupOcrPageStateRecord> = emptyList(),
    ocrArtifacts: Collection<BackupOcrArtifactRecord> = emptyList(),
    ocrCorrections: Collection<BackupOcrCorrectionRecord> = emptyList(),
    ocrLines: Collection<BackupOcrLineRecord> = emptyList(),
) : BackupRecordSource {
    private val folderSnapshot = folders.toList()
    private val documentSnapshot = documents.toList()
    private val pageSnapshot = pages.toList()
    private val sourceAssetSnapshot = sourceAssets.toList()
    private val ocrDocumentStateSnapshot = ocrDocumentStates.toList()
    private val ocrPageStateSnapshot = ocrPageStates.toList()
    private val ocrArtifactSnapshot = ocrArtifacts.toList()
    private val ocrCorrectionSnapshot = ocrCorrections.toList()
    private val ocrLineSnapshot = ocrLines.toList()

    override fun folders(): Sequence<BackupFolderRecord> = folderSnapshot.asSequence()

    override fun documents(): Sequence<BackupDocumentRecord> = documentSnapshot.asSequence()

    override fun pages(): Sequence<BackupPageRecord> = pageSnapshot.asSequence()

    override fun sourceAssets(): Sequence<BackupSourceAssetRecord> = sourceAssetSnapshot.asSequence()

    override fun ocrDocumentStates(): Sequence<BackupOcrDocumentStateRecord> =
        ocrDocumentStateSnapshot.asSequence()

    override fun ocrPageStates(): Sequence<BackupOcrPageStateRecord> = ocrPageStateSnapshot.asSequence()

    override fun ocrArtifacts(): Sequence<BackupOcrArtifactRecord> = ocrArtifactSnapshot.asSequence()

    override fun ocrCorrections(): Sequence<BackupOcrCorrectionRecord> = ocrCorrectionSnapshot.asSequence()

    override fun ocrLines(): Sequence<BackupOcrLineRecord> = ocrLineSnapshot.asSequence()
}
