package org.synapseworks.pageharbor.document.signature

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Appends black handwriting as page content in a new PDF. The caller initializes PDFBox's
 * Android resource loader and owns the private source/output lifecycle. No signature is saved
 * separately. Existing source bytes, page order, resources and text remain untouched.
 */
object SignaturePdfStamper {
    const val MAX_INPUT_BYTES = 256L * 1024 * 1024
    const val MAX_INPUT_PAGES = 10_000

    suspend fun stamp(
        source: File,
        output: File,
        request: SignaturePdfRequest,
    ): SignaturePdfResult {
        var ownsOutput = false
        var keepOutput = false
        try {
            val result = withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                // Freeze caller-owned collections before background work or pointer updates.
                require(request.signature.strokes.size in 1..MAX_SIGNATURE_STROKES)
                require(request.signature.strokes.sumOf { it.points.size.toLong() } <= MAX_SIGNATURE_POINTS)
                val frozenRequest = request.copy(
                    signature = request.signature.copy(
                        strokes = request.signature.strokes.map { stroke ->
                            require(stroke.points.size in 1..MAX_SIGNATURE_POINTS_PER_STROKE)
                            stroke.copy(points = stroke.points.toList())
                        },
                    ),
                )
                if (!source.isFile) return@withContext failure(SignaturePdfFailure.SOURCE_MISSING)
                if (source.length() > MAX_INPUT_BYTES) {
                    return@withContext failure(SignaturePdfFailure.SOURCE_TOO_LARGE)
                }
                if (source.canonicalFile == output.canonicalFile) {
                    return@withContext failure(SignaturePdfFailure.INVALID_REQUEST)
                }
                if (output.exists()) return@withContext failure(SignaturePdfFailure.OUTPUT_EXISTS)
                val parent = output.absoluteFile.parentFile
                if (parent == null || !parent.isDirectory) {
                    return@withContext failure(SignaturePdfFailure.WRITE_FAILED)
                }
                val originalDigest = fingerprint(source)
                if (!output.createNewFile()) {
                    return@withContext failure(SignaturePdfFailure.OUTPUT_EXISTS)
                }
                ownsOutput = true
                val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(parent)
                val count = PDDocument.load(source, memory).use { document ->
                    coroutineContext.ensureActive()
                    if (document.isEncrypted) return@withContext failure(SignaturePdfFailure.SOURCE_LOCKED)
                    if (document.numberOfPages !in 1..MAX_INPUT_PAGES ||
                        frozenRequest.pageIndex >= document.numberOfPages
                    ) {
                        return@withContext failure(SignaturePdfFailure.INVALID_REQUEST)
                    }
                    val page = document.getPage(frozenRequest.pageIndex)
                    val crop = page.cropBox
                    val geometry = SignaturePageGeometry(
                        cropLeft = crop.lowerLeftX,
                        cropBottom = crop.lowerLeftY,
                        cropWidth = crop.width,
                        cropHeight = crop.height,
                        rotationDegrees = page.rotation,
                    )
                    PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true,
                    ).use { content ->
                        content.setStrokingColor(0, 0, 0)
                        content.setLineWidth(geometry.strokeWidth(frozenRequest.signature, frozenRequest.placement))
                        content.setLineCapStyle(1)
                        content.setLineJoinStyle(1)
                        for (stroke in frozenRequest.signature.strokes) {
                            coroutineContext.ensureActive()
                            val first = geometry.map(stroke.points.first(), frozenRequest.placement)
                            content.moveTo(first.x, first.y)
                            if (stroke.points.size == 1) {
                                // A very short round-capped segment retains a deliberate pen tap.
                                content.lineTo(first.x + 0.001f, first.y)
                            } else {
                                stroke.points.drop(1).forEachIndexed { index, point ->
                                    if (index % 256 == 0) coroutineContext.ensureActive()
                                    val mapped = geometry.map(point, frozenRequest.placement)
                                    content.lineTo(mapped.x, mapped.y)
                                }
                            }
                            content.stroke()
                        }
                    }
                    coroutineContext.ensureActive()
                    document.save(output)
                    document.numberOfPages
                }
                coroutineContext.ensureActive()
                if (!originalDigest.contentEquals(fingerprint(source))) {
                    return@withContext failure(SignaturePdfFailure.SOURCE_CHANGED)
                }
                if (output.length() == 0L) return@withContext failure(SignaturePdfFailure.WRITE_FAILED)
                SignaturePdfResult.Success(count)
            }
            keepOutput = result is SignaturePdfResult.Success
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidPasswordException) {
            return failure(SignaturePdfFailure.SOURCE_LOCKED)
        } catch (_: IllegalArgumentException) {
            return failure(SignaturePdfFailure.INVALID_REQUEST)
        } catch (_: Exception) {
            return failure(SignaturePdfFailure.WRITE_FAILED)
        } finally {
            if (ownsOutput && !keepOutput) {
                try {
                    output.delete()
                } catch (_: Exception) {
                    // The caller's owned-cache cleanup can retry a denied deletion.
                }
            }
        }
    }

    private fun failure(reason: SignaturePdfFailure) = SignaturePdfResult.Failure(reason)

    private suspend fun fingerprint(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
