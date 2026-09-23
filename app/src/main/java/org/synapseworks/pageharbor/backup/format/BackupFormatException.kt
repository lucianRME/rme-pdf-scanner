package org.synapseworks.pageharbor.backup.format

import java.io.IOException

enum class BackupFormatFailure {
    INVALID_PATH,
    DUPLICATE_ENTRY,
    UNDECLARED_ENTRY,
    MISSING_ENTRY,
    INVALID_JSON,
    INVALID_METADATA,
    INVALID_LEDGER,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_REQUIRED_FEATURE,
    RELATIONSHIP_INVALID,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    SIGNATURE_MISMATCH,
    DIMENSION_MISMATCH,
    LIMIT_EXCEEDED,
    ASSET_UNAVAILABLE,
    STAGING_FAILED,
    MALFORMED_ZIP,
    IO_ERROR,
}

class BackupFormatException(
    val failure: BackupFormatFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun backupFailure(
    failure: BackupFormatFailure,
    message: String,
    cause: Throwable? = null,
): BackupFormatException = BackupFormatException(failure, message, cause)
