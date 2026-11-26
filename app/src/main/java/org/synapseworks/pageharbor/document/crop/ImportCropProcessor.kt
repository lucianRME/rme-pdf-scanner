package org.synapseworks.pageharbor.document.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.booklayout.BookDerivedPage
import org.synapseworks.pageharbor.document.booklayout.BookPreviewImage
import org.synapseworks.pageharbor.document.booklayout.BookSplitProcessor
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.OwnedTemporaryFile

internal fun DocumentPage.supportsImportCrop(): Boolean =
    sourceCategory != DocumentSourceCategory.RENDERED_PDF_PAGE &&
        contentType in setOf("image/jpeg", "image/png", "image/webp") &&
        annotations.isEmpty && paperSize == null

internal data class CropSource(val snapshot: File, val original: BookPreviewImage, val proposal: PageCrop?)

internal class ImportCropProcessor(private val app: Context) {
    private val store = BookSplitProcessor(app, rootName = "page-crops", maximumOutputBytes = 24L * 1024 * 1024)

    suspend fun begin() = store.begin()
    suspend fun finish(workspace: File, keep: Set<String>) = store.finish(workspace, keep)

    suspend fun analyze(workspace: File, page: DocumentPage): CropSource = withContext(Dispatchers.IO) {
        val (snapshot, _) = store.snapshot(workspace, page, allowSampledSource = true)
        val thumbnail = store.decode(snapshot, page, 0, analysis = true)
        try {
            val pixels = IntArray(thumbnail.width * thumbnail.height)
            thumbnail.getPixels(pixels, 0, thumbnail.width, 0, 0, thumbnail.width, thumbnail.height)
            val job = coroutineContext
            val proposal = ImportCropDetector.detect(thumbnail.width, thumbnail.height, pixels) { job.ensureActive() }
            val original = store.writeBitmap(workspace, "original.jpg", thumbnail)
            CropSource(snapshot, original, proposal)
        } finally {
            thumbnail.recycle()
        }
    }

    suspend fun preview(workspace: File, page: DocumentPage, source: CropSource, crop: PageCrop): BookPreviewImage =
        withContext(Dispatchers.IO) {
            val result = render(workspace, page, source, crop, analysis = true)
            workspace.listFiles()?.filter { it.name.startsWith("preview-") && it != result.file }?.forEach { it.delete() }
            result
        }

    suspend fun prepare(workspace: File, page: DocumentPage, source: CropSource, crop: PageCrop): BookDerivedPage =
        withContext(Dispatchers.IO) {
            val output = render(workspace, page, source, crop, analysis = false)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", output.file)
            BookDerivedPage(
                DocumentResource(uri.toString(), DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                    OwnedTemporaryFile(output.file.path, workspace.path)),
                output.metadata,
            )
        }

    private suspend fun render(
        workspace: File,
        page: DocumentPage,
        source: CropSource,
        crop: PageCrop,
        analysis: Boolean,
    ): BookPreviewImage {
        val image = store.decode(source.snapshot, page, 0, analysis, allowSampledSource = true)
        try {
            coroutineContext.ensureActive()
            val corrected = rectifyCrop(image, crop)
            try {
                val prefix = if (analysis) "preview" else "page"
                return store.writeBitmap(workspace, "$prefix-${UUID.randomUUID()}.jpg", corrected)
            } finally {
                corrected.recycle()
            }
        } finally {
            image.recycle()
        }
    }
}

internal fun rectifyCrop(image: Bitmap, crop: PageCrop): Bitmap {
    val (width, height) = crop.outputSize(image.width, image.height)
    val source = crop.corners.flatMap { listOf(it.x * image.width, it.y * image.height) }.toFloatArray()
    val target = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
    val mapping = Matrix()
    check(mapping.setPolyToPoly(source, 0, target, 0, 4))
    val corrected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(corrected).apply {
            drawColor(Color.WHITE)
            drawBitmap(image, mapping, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return corrected
    } catch (failure: Throwable) {
        corrected.recycle()
        throw failure
    }
}
