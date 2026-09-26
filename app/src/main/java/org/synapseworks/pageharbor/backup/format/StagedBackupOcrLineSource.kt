package org.synapseworks.pageharbor.backup.format

import java.io.InputStream

internal class StagedBackupOcrLineSource(
    private val descriptors: List<BackupOcrLineChunkDescriptor>,
    private val limits: BackupFormatLimits,
    private val opener: (String) -> InputStream?,
) : BackupOcrLineSource {
    override val recordCount: Int = descriptors.sumOf(BackupOcrLineChunkDescriptor::recordCount)

    override fun records(): Sequence<BackupOcrLineRecord> = sequence {
        descriptors.forEach { descriptor ->
            val records = ArrayList<BackupOcrLineRecord>(descriptor.recordCount)
            val source = opener(descriptor.path)
                ?: throw backupFailure(
                    BackupFormatFailure.STAGING_FAILED,
                    "A verified OCR metadata chunk is unavailable.",
                )
            val count = source.use { input ->
                BackupOcrJsonCodec.readLines(input, limits, records::add)
            }
            if (count != descriptor.recordCount) {
                throw backupFailure(
                    BackupFormatFailure.RELATIONSHIP_INVALID,
                    "A verified OCR metadata chunk changed after verification.",
                )
            }
            yieldAll(records)
        }
    }
}
