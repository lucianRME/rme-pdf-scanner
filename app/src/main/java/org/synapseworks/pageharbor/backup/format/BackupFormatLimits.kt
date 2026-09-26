package org.synapseworks.pageharbor.backup.format

data class BackupFormatLimits(
    val maximumEntryCount: Int = 1_100_010,
    val maximumFolderCount: Int = 100_000,
    val maximumDocumentCount: Int = 100_000,
    val maximumPageCount: Int = 1_000_000,
    val maximumSourceAssetCount: Int = 100_000,
    val maximumOcrArtifactCount: Int = 4_000_000,
    val maximumOcrCorrectionCount: Int = 1_000_000,
    val maximumOcrLineCount: Int = 20_000_000,
    val maximumOcrLinesPerArtifact: Int = 5_000,
    val maximumOcrLineChunkRecords: Int = 10_000,
    val maximumOcrLineChunkBytes: Int = 8 * 1024 * 1024,
    val maximumManifestBytes: Int = 1 * 1024 * 1024,
    val maximumMetadataEntryBytes: Int = 64 * 1024 * 1024,
    val maximumOcrMetadataEntryBytes: Int = 64 * 1024 * 1024,
    val maximumJsonLineBytes: Int = 1 * 1024 * 1024,
    val maximumLedgerBytes: Int = 128 * 1024 * 1024,
    val maximumSingleAssetBytes: Long = 512L * 1024L * 1024L,
    val maximumTotalUncompressedBytes: Long = 8L * 1024L * 1024L * 1024L,
    val maximumCompressionRatio: Long = 250L,
    val maximumJsonDepth: Int = 32,
    val maximumJsonTokens: Int = 2_000_000,
    val maximumStringCharacters: Int = 1_000_000,
) {
    init {
        require(maximumEntryCount >= 6)
        require(maximumFolderCount >= 0)
        require(maximumDocumentCount >= 0)
        require(maximumPageCount >= 0)
        require(maximumSourceAssetCount >= 0)
        require(maximumOcrArtifactCount >= 0)
        require(maximumOcrCorrectionCount >= 0)
        require(maximumOcrLineCount >= 0)
        require(maximumOcrLinesPerArtifact in 1..5_000)
        require(maximumOcrLineChunkRecords > 0)
        require(maximumOcrLineChunkBytes > 0)
        require(maximumManifestBytes > 0)
        require(maximumMetadataEntryBytes > 0)
        require(maximumOcrMetadataEntryBytes > 0)
        require(maximumJsonLineBytes > 0)
        require(maximumLedgerBytes > 0)
        require(maximumSingleAssetBytes > 0)
        require(maximumTotalUncompressedBytes > 0)
        require(maximumCompressionRatio > 0)
        require(maximumJsonDepth > 0)
        require(maximumJsonTokens > 0)
        require(maximumStringCharacters > 0)
    }
}
