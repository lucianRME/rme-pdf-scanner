package org.synapseworks.pageharbor.migration

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import org.synapseworks.pageharbor.document.importing.InboundShareResource

/**
 * Tolerant SAF/content-provider adapter. Metadata is never trusted for decoder selection and an
 * omitted/invalid size or timestamp stays null rather than being rewritten as zero.
 */
class AndroidMigrationSourceAdapter(context: Context) : MigrationSourceAccess {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun describe(
        resource: InboundShareResource,
        relativeFolderPath: List<String> = emptyList(),
    ): MigrationSource = describe(
        uri = resource.uri,
        relativeFolderPath = relativeFolderPath,
        declaredContentType = resource.declaredContentType,
        platformAccessFlags = resource.grantFlags,
    )

    fun describe(
        uri: Uri,
        relativeFolderPath: List<String> = emptyList(),
        declaredContentType: String? = null,
        platformAccessFlags: Int = 0,
    ): MigrationSource {
        require(relativeFolderPath.none(::isUnsafePathSegment))
        var displayName: String? = null
        var sizeBytes: Long? = null
        var modifiedAtMillis: Long? = null
        try {
            resolver.query(uri, METADATA_COLUMNS, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.safeString(OpenableColumns.DISPLAY_NAME)
                    sizeBytes = cursor.safeNonNegativeLong(OpenableColumns.SIZE)
                    modifiedAtMillis = cursor.safePositiveLong(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                }
            }
        } catch (_: SecurityException) {
            // The source can still be previewed through openInputStream if metadata is denied.
        } catch (_: IllegalArgumentException) {
            // Providers are allowed to omit or reject optional metadata columns.
        } catch (_: IllegalStateException) {
            // A failed provider cursor must not abort discovery of the rest of the batch.
        } catch (_: RuntimeException) {
            // Provider-specific failures are treated as absent optional metadata.
        }
        val providerType = try {
            resolver.getType(uri)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        return MigrationSource(
            id = uri.toString(),
            displayName = displayName,
            relativeFolderPath = relativeFolderPath.toList(),
            declaredContentType = declaredContentType ?: providerType,
            platformAccessFlags = platformAccessFlags,
            sizeBytes = sizeBytes,
            modifiedAtMillis = modifiedAtMillis,
        )
    }

    override fun open(source: MigrationSource): InputStream = try {
        resolver.openInputStream(Uri.parse(source.id))
            ?: throw IOException("Provider returned no stream")
    } catch (exception: SecurityException) {
        throw exception
    } catch (exception: IOException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw IOException("Provider stream failed", exception)
    }

    private fun Cursor.safeString(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return try {
            getString(index)?.takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun Cursor.safeNonNegativeLong(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return try {
            getLong(index).takeIf { it >= 0L }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun Cursor.safePositiveLong(column: String): Long? =
        safeNonNegativeLong(column)?.takeIf { it > 0L }

    private companion object {
        val METADATA_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
