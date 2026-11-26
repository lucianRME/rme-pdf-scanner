package org.synapseworks.pageharbor.document.booklayout

import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Creates a separate layout copy using PDF forms, retaining text and vector page content.
 * Split mode is visual cropping: clipped-away source content remains in the imported form.
 * It must never be used as a redaction or document-sanitization operation.
 *
 * The caller initializes PDFBoxResourceLoader and owns the completed output's lifetime. Source
 * PDFs with password protection or page annotations are rejected rather than partially copied.
 * Existing destination files are never overwritten. No document content or metadata is logged.
 */
class BookLayoutPdfEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun transform(
        source: File,
        output: File,
        settings: BookLayoutSettings,
    ): BookLayoutResult {
        var ownsOutput = false
        var keepOutput = false
        var scratch: File? = null
        try {
            val result = withContext(ioDispatcher) {
                coroutineContext.ensureActive()
                if (!source.isFile) return@withContext failure(BookLayoutFailure.SOURCE_MISSING)
                if (source.length() !in 1..MAX_SOURCE_BYTES) return@withContext failure(BookLayoutFailure.SOURCE_TOO_LARGE)
                if (source.canonicalFile == output.canonicalFile || output.exists()) {
                    return@withContext failure(BookLayoutFailure.OUTPUT_EXISTS)
                }
                val parent = output.absoluteFile.parentFile
                if (parent?.isDirectory != true) return@withContext failure(BookLayoutFailure.WRITE_FAILED)
                val beforeDigest = digest(source)
                scratch = Files.createTempDirectory(parent.toPath(), ".book-layout-").toFile()
                val work = requireNotNull(scratch)
                PDDocument.load(source, memoryUsage(work)).use { original ->
                    if (original.isEncrypted) return@withContext failure(BookLayoutFailure.PROTECTED_SOURCE)
                    if (original.numberOfPages !in 1..MAX_SOURCE_PAGES) {
                        return@withContext failure(BookLayoutFailure.TOO_MANY_PAGES)
                    }
                    val sourcePages = original.pages.map { page ->
                        coroutineContext.ensureActive()
                        if (page.annotations.isNotEmpty()) return@withContext failure(BookLayoutFailure.UNSUPPORTED_PAGE)
                        val crop = page.cropBox
                        try {
                            BookSourcePage(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.rotation, page.userUnit)
                        } catch (_: IllegalArgumentException) {
                            return@withContext failure(BookLayoutFailure.UNSUPPORTED_PAGE)
                        }
                    }
                    val plan = BookLayoutGeometry.plan(sourcePages, settings)
                    PDDocument(memoryUsage(work)).use { target ->
                        val importer = LayerUtility(target)
                        val forms = HashMap<Int, com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject>()
                        for (sheet in plan) {
                            coroutineContext.ensureActive()
                            val page = PDPage(PDRectangle(sheet.width, sheet.height))
                            target.addPage(page)
                            PDPageContentStream(target, page).use { content ->
                                for (placement in sheet.placements) {
                                    coroutineContext.ensureActive()
                                    val form = forms.getOrPut(placement.sourceIndex) {
                                        importer.importPageAsForm(original, placement.sourceIndex).also {
                                            // LayerUtility applies its own rotation/scaling. The independently
                                            // calculated display transform below replaces that normalization.
                                            it.setMatrix(AffineTransform())
                                        }
                                    }
                                    content.saveGraphicsState()
                                    val clip = placement.clip
                                    content.addRect(clip.left, clip.bottom, clip.width, clip.height)
                                    content.clip()
                                    val m = placement.transform
                                    content.transform(Matrix(m.a, m.b, m.c, m.d, m.e, m.f))
                                    content.drawForm(form)
                                    content.restoreGraphicsState()
                                }
                            }
                        }
                        coroutineContext.ensureActive()
                        Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { destination ->
                            ownsOutput = true
                            val operationContext = coroutineContext
                            target.save(LimitedOutputStream(destination, MAX_OUTPUT_BYTES) { operationContext.ensureActive() })
                        }
                    }
                    coroutineContext.ensureActive()
                    if (!beforeDigest.contentEquals(digest(source))) return@withContext failure(BookLayoutFailure.SOURCE_CHANGED)
                    BookLayoutResult.Success(plan.size)
                }
            }
            keepOutput = result is BookLayoutResult.Success
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidPasswordException) {
            return failure(BookLayoutFailure.PROTECTED_SOURCE)
        } catch (_: FileAlreadyExistsException) {
            return failure(BookLayoutFailure.OUTPUT_EXISTS)
        } catch (_: IOException) {
            return failure(BookLayoutFailure.WRITE_FAILED)
        } catch (_: SecurityException) {
            return failure(BookLayoutFailure.WRITE_FAILED)
        } catch (_: IllegalArgumentException) {
            return failure(BookLayoutFailure.INVALID_SOURCE)
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                if (ownsOutput && !keepOutput) output.delete()
                scratch?.deleteRecursively()
            }
        }
    }

    private suspend fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1_024)
            var readBytes = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                readBytes += count
                if (readBytes > MAX_SOURCE_BYTES) throw IOException("Layout source exceeds size limit")
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun memoryUsage(scratch: File): MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(8L * 1_024 * 1_024).setTempDir(scratch)

    private fun failure(reason: BookLayoutFailure): BookLayoutResult.Failure = BookLayoutResult.Failure(reason)

    private class LimitedOutputStream(
        output: OutputStream,
        private val maximumBytes: Long,
        private val checkCancellation: () -> Unit,
    ) : FilterOutputStream(output) {
        private var writtenBytes = 0L

        override fun write(value: Int) {
            account(1)
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            account(length)
            out.write(buffer, offset, length)
        }

        private fun account(count: Int) {
            checkCancellation()
            if (count < 0 || writtenBytes > maximumBytes - count) throw IOException("Layout output exceeds size limit")
            writtenBytes += count
        }
    }

    companion object {
        const val MAX_SOURCE_BYTES = 512L * 1_024 * 1_024
        const val MAX_SOURCE_PAGES = 1_000
        const val MAX_OUTPUT_BYTES = 1_024L * 1_024 * 1_024
    }
}
