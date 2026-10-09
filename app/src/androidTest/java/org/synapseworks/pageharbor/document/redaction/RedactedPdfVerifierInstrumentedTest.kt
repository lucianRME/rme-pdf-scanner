package org.synapseworks.pageharbor.document.redaction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine

class RedactedPdfVerifierInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testRoot: File
    private lateinit var verifier: RedactedPdfVerifier
    private val expectedImageHashes = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        testRoot = File(context.cacheDir, "redacted-verifier-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
        verifier = RedactedPdfVerifier(context)
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun acceptsFreshOpaqueRasterAndAttestsExactBytes() = runBlocking {
        val file = safeRasterPdf("accepted")
        val centerPixel = renderedCenterPixel(file, REDACTION)
        assertTrue(
            "rendered center ARGB=${Integer.toHexString(centerPixel)}",
            Color.red(centerPixel) <= 16 && Color.green(centerPixel) <= 16 &&
                Color.blue(centerPixel) <= 16,
        )
        val result = verifier.verify(file, rasterPolicy(file))

        assertTrue("result=$result", result is RedactedPdfVerificationResult.Verified)
        val evidence = (result as RedactedPdfVerificationResult.Verified).evidence
        assertEquals(file.length(), evidence.bytes)
        assertEquals(1, evidence.pageCount)
        assertEquals(1, evidence.imageObjectCount)
        assertEquals(0, evidence.searchableTextPageCount)
        assertTrue(verifier.stillMatches(file, evidence))
        assertTrue(
            File(context.cacheDir, "redacted-pdf-verifier").listFiles().orEmpty().isEmpty(),
        )

        val changed = file.readBytes().also { bytes ->
            val index = (bytes.size / 2).coerceAtLeast(1)
            bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        }
        FileOutputStream(file, false).use { output -> output.write(changed) }
        assertEquals(evidence.bytes, file.length())
        assertFalse(verifier.stillMatches(file, evidence))
    }

    @Test
    fun rejectsVisualBlackOverlayBecauseOriginalPixelsRemainEmbedded() = runBlocking {
        val file = overlayPdf("overlay")

        val result = verifier.verify(file, rasterPolicy(file))

        assertRejected(result, RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
    }

    @Test
    fun rejectsHiddenDocumentChannelsAndPageBoxTricks() = runBlocking {
        val cases = listOf<Pair<String, (PDDocument) -> Unit>>(
            "document-info" to { document ->
                document.documentInformation.subject = SENTINEL
            },
            "annotation" to { document ->
                document.getPage(0).annotations = listOf(
                    PDAnnotationText().apply {
                        contents = SENTINEL
                        rectangle = PDRectangle(20f, 20f)
                    },
                )
            },
            "attachment-name-tree" to { document ->
                document.documentCatalog.cosObject.setItem(
                    COSName.NAMES,
                    COSDictionary().apply {
                        setItem(COSName.EMBEDDED_FILES, COSDictionary())
                    },
                )
            },
            "open-action" to { document ->
                document.documentCatalog.cosObject.setItem(
                    COSName.OPEN_ACTION,
                    COSDictionary().apply { setString(COSName.getPDFName("S"), "JavaScript") },
                )
            },
            "optional-content" to { document ->
                document.documentCatalog.cosObject.setItem(
                    COSName.getPDFName("OCProperties"),
                    COSDictionary(),
                )
            },
            "crop-box" to { document ->
                document.getPage(0).cropBox = PDRectangle(PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f)
            },
            "hidden-stream" to { document ->
                val hidden = PDStream(document)
                hidden.createOutputStream().use { output -> output.write(SENTINEL.toByteArray()) }
                document.documentCatalog.cosObject.setItem(
                    COSName.getPDFName("RMEHidden"),
                    hidden,
                )
            },
            "unknown-catalog-string" to { document ->
                document.documentCatalog.cosObject.setString(
                    COSName.getPDFName("RMEHidden"),
                    SENTINEL,
                )
            },
        )

        cases.forEach { (name, mutate) ->
            val file = mutateSafePdf(name, mutate)
            val result = verifier.verify(file, rasterPolicy(file))
            assertTrue("$name result=$result", result is RedactedPdfVerificationResult.Rejected)
        }
    }

    @Test
    fun rejectsInertStreamPayloadsAndNestedResourcesWithoutSentinelPolicy() = runBlocking {
        val changedHeader = safeRasterPdf("changed-binary-header")
        val changedHeaderBytes = changedHeader.readBytes()
        assertTrue(
            changedHeaderBytes.copyOfRange(10, 14).contentEquals(
                byteArrayOf(0xF6.toByte(), 0xE4.toByte(), 0xFC.toByte(), 0xDF.toByte()),
            ),
        )
        "LEAK".toByteArray().copyInto(changedHeaderBytes, destinationOffset = 10)
        FileOutputStream(changedHeader, false).use { output -> output.write(changedHeaderBytes) }
        assertRejected(
            verifier.verify(changedHeader, rasterPolicy(changedHeader)),
            RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE,
        )

        val trailingPayload = safeRasterPdf("trailing-payload")
        FileOutputStream(trailingPayload, true).use { output ->
            output.write("BINARY_PAYLOAD_AFTER_EOF".toByteArray())
        }
        assertRejected(
            verifier.verify(trailingPayload, rasterPolicy(trailingPayload)),
            RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE,
        )

        val commentedContent = mutateSafePdf("commented-content") { document ->
            val stream = document.getPage(0).contentStreams.asSequence().single().cosObject
            val original = stream.createInputStream().use { it.readBytes() }
            stream.removeItem(COSName.FILTER)
            stream.createOutputStream().use { output ->
                output.write(original)
                output.write("% inert hidden payload\n".toByteArray())
            }
        }
        assertRejected(
            verifier.verify(commentedContent, rasterPolicy(commentedContent)),
            RedactedPdfVerificationFailure.UNEXPECTED_CONTENT,
        )

        val nestedResource = mutateSafePdf("nested-resource") { document ->
            document.getPage(0).resources.cosObject.setItem(
                COSName.COLORSPACE,
                COSDictionary().apply {
                    setItem(
                        COSName.getPDFName("LeakName"),
                        COSName.getPDFName("EncodedSecret"),
                    )
                },
            )
        }
        assertRejected(
            verifier.verify(nestedResource, rasterPolicy(nestedResource)),
            RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE,
        )

        val searchable = searchablePdf(
            rasterJpeg("font-stream-image", redacted = true),
            "font-stream-source",
            SAFE_TEXT,
        )
        val changedFont = File(testRoot, "font-stream-payload.pdf")
        PDDocument.load(searchable).use { document ->
            val font = document.getPage(0).resources.fontNames
                .map(document.getPage(0).resources::getFont)
                .filterIsInstance<com.tom_roush.pdfbox.pdmodel.font.PDType3Font>()
                .single()
            val toUnicode = font.cosObject.getDictionaryObject(COSName.TO_UNICODE) as COSStream
            val original = toUnicode.createInputStream().use { it.readBytes() }
            toUnicode.removeItem(COSName.FILTER)
            toUnicode.createOutputStream().use { output ->
                output.write(original)
                output.write("% unused mapping payload\n".toByteArray())
            }
            document.save(changedFont)
        }
        expectedImageHashes[changedFont.absolutePath] =
            requireNotNull(expectedImageHashes[searchable.absolutePath])
        assertRejected(
            verifier.verify(changedFont, textPolicy(changedFont, SAFE_TEXT)),
            RedactedPdfVerificationFailure.UNEXPECTED_CONTENT,
        )
    }

    @Test
    fun rejectsImagePixelsHiddenOutsideThePageByAnOversizedTransform() = runBlocking {
        val jpeg = rasterJpeg("off-page-image", redacted = true)
        val file = File(testRoot, "off-page-image.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(PAGE_WIDTH, PAGE_HEIGHT))
            document.addPage(page)
            val image = jpeg.inputStream().use { JPEGFactory.createFromStream(document, it) }
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 0f, 0f, PAGE_WIDTH * 2f, PAGE_HEIGHT)
            }
            document.save(file)
        }
        expectedImageHashes[file.absolutePath] =
            requireNotNull(secureRedactionSha256(jpeg::inputStream))

        assertRejected(
            verifier.verify(file, rasterPolicy(file)),
            RedactedPdfVerificationFailure.UNEXPECTED_CONTENT,
        )
    }

    @Test
    fun rejectsAChangedImageEvenWhenItStillRendersBlack() = runBlocking {
        val original = safeRasterPdf("image-attestation-original")
        val replacementJpeg = rasterJpeg("image-attestation-replacement", redacted = true)
        FileOutputStream(replacementJpeg, true).use { output ->
            output.write("HIDDEN_TRAILING_PAYLOAD".toByteArray())
        }
        val changed = mutateSafePdf("image-attestation-changed") { document ->
            val page = document.getPage(0)
            val name = page.resources.xObjectNames.single()
            val replacement = replacementJpeg.inputStream().use {
                JPEGFactory.createFromStream(document, it)
            }
            page.resources.put(name, replacement)
        }
        expectedImageHashes[changed.absolutePath] =
            requireNotNull(expectedImageHashes[original.absolutePath])

        assertRejected(
            verifier.verify(changed, rasterPolicy(changed)),
            RedactedPdfVerificationFailure.UNSAFE_IMAGE_OBJECT,
        )
    }

    @Test
    fun rejectsAlternateImageAndImageMaskEvenWhenPageStillRenders() = runBlocking {
        val alternate = mutateSafePdf("alternate-image") { document ->
            val extraJpeg = rasterJpeg("alternate-image-payload", redacted = false)
            val extra = extraJpeg.inputStream().use { JPEGFactory.createFromStream(document, it) }
            document.getPage(0).resources.add(extra)
        }
        assertRejected(
            verifier.verify(alternate, rasterPolicy(alternate)),
            RedactedPdfVerificationFailure.UNEXPECTED_CONTENT,
        )

        val masked = mutateSafePdf("masked-image") { document ->
            val image = document.getPage(0).resources.xObjectNames
                .map(document.getPage(0).resources::getXObject)
                .filterIsInstance<PDImageXObject>()
                .single()
            image.cosObject.setItem(
                COSName.MASK,
                COSArray().apply { repeat(6) { add(COSInteger.ZERO) } },
            )
        }
        assertRejected(
            verifier.verify(masked, rasterPolicy(masked)),
            RedactedPdfVerificationFailure.UNSAFE_IMAGE_OBJECT,
        )
    }

    @Test
    fun sentinelSearchCoversMetadataAndDecodedStreams() = runBlocking {
        val metadata = mutateSafePdf("sentinel-metadata") { document ->
            document.documentInformation.setCustomMetadataValue("RMESecret", SENTINEL)
        }
        assertRejected(
            verifier.verify(
                metadata,
                rasterPolicy(metadata, forbiddenSentinels = setOf(SENTINEL)),
            ),
            RedactedPdfVerificationFailure.FORBIDDEN_SENTINEL_SURVIVED,
        )

        val hiddenStream = mutateSafePdf("sentinel-stream") { document ->
            val hidden = PDStream(document)
            hidden.createOutputStream(COSName.FLATE_DECODE).use { output ->
                output.write(SENTINEL.toByteArray())
            }
            document.documentCatalog.cosObject.setItem(COSName.getPDFName("Hidden"), hidden)
        }
        val result = verifier.verify(
            hiddenStream,
            rasterPolicy(hiddenStream, forbiddenSentinels = setOf(SENTINEL)),
        )
        assertTrue("result=$result", result is RedactedPdfVerificationResult.Rejected)
    }

    @Test
    fun acceptsExactlyAllowlistedInvisibleType3TextAndRejectsSecretText() = runBlocking {
        val image = rasterJpeg("searchable", redacted = true)
        val safe = searchablePdf(image, "searchable-safe", SAFE_TEXT)
        val accepted = verifier.verify(
            safe,
            textPolicy(safe, expectedText = SAFE_TEXT),
        )
        assertTrue("accepted=$accepted", accepted is RedactedPdfVerificationResult.Verified)
        assertEquals(
            1,
            (accepted as RedactedPdfVerificationResult.Verified)
                .evidence.searchableTextPageCount,
        )

        val unsafe = searchablePdf(image, "searchable-secret", SENTINEL)
        val rejected = verifier.verify(
            unsafe,
            textPolicy(
                unsafe,
                expectedText = SAFE_TEXT,
                forbiddenSentinels = setOf(SENTINEL),
            ),
        )
        assertTrue("rejected=$rejected", rejected is RedactedPdfVerificationResult.Rejected)
    }

    private fun safeRasterPdf(name: String): File {
        val jpeg = rasterJpeg("$name-image", redacted = true)
        val file = File(testRoot, "$name.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(PAGE_WIDTH, PAGE_HEIGHT))
            document.addPage(page)
            val image = jpeg.inputStream().use { JPEGFactory.createFromStream(document, it) }
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 0f, 0f, PAGE_WIDTH, PAGE_HEIGHT)
            }
            document.save(file)
        }
        expectedImageHashes[file.absolutePath] =
            requireNotNull(secureRedactionSha256(jpeg::inputStream))
        return file
    }

    private fun overlayPdf(name: String): File {
        val jpeg = rasterJpeg("$name-image", redacted = false)
        val file = File(testRoot, "$name.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(PAGE_WIDTH, PAGE_HEIGHT))
            document.addPage(page)
            val image = jpeg.inputStream().use { JPEGFactory.createFromStream(document, it) }
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 0f, 0f, PAGE_WIDTH, PAGE_HEIGHT)
                content.setNonStrokingColor(0, 0, 0)
                content.addRect(
                    REDACTION.left * PAGE_WIDTH,
                    (1f - REDACTION.bottom) * PAGE_HEIGHT,
                    REDACTION.width * PAGE_WIDTH,
                    REDACTION.height * PAGE_HEIGHT,
                )
                content.fill()
            }
            document.save(file)
        }
        expectedImageHashes[file.absolutePath] =
            requireNotNull(secureRedactionSha256(jpeg::inputStream))
        return file
    }

    private fun mutateSafePdf(name: String, mutate: (PDDocument) -> Unit): File {
        val source = safeRasterPdf("$name-source")
        val destination = File(testRoot, "$name.pdf")
        PDDocument.load(source).use { document ->
            mutate(document)
            document.save(destination)
        }
        expectedImageHashes[destination.absolutePath] =
            requireNotNull(expectedImageHashes[source.absolutePath])
        return destination
    }

    private fun searchablePdf(image: File, name: String, text: String): File = runBlocking {
        val output = File(testRoot, "$name.pdf")
        val result = PdfBoxSearchablePdfGenerator(context).generate(
            SearchablePdfRequest(
                pages = listOf(
                    SearchablePdfPage(
                        openJpegStream = image::inputStream,
                        ocrResult = OcrPageResult(
                            pageIndex = 0,
                            text = text,
                            layout = OcrPageLayout(
                                imageWidthPx = IMAGE_WIDTH,
                                imageHeightPx = IMAGE_HEIGHT,
                                lines = listOf(
                                    OcrTextLine(
                                        text = text,
                                        bounds = OcrTextBounds(20f, 20f, 300f, 70f),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                outputFile = output,
                scratchDirectory = testRoot,
            ),
        )
        assertTrue("generation=$result", result is SearchablePdfGenerationResult.Success)
        expectedImageHashes[output.absolutePath] =
            requireNotNull(secureRedactionSha256(image::inputStream))
        output
    }

    private fun rasterJpeg(name: String, redacted: Boolean): File {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 72, 150)
                textSize = 28f
            }
            canvas.drawText(SENTINEL, 72f, 210f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            repeat(12) { offset ->
                paint.color = Color.rgb((offset * 19) % 255, 40, 180)
                canvas.drawLine(82f + offset, 128f, 244f, 302f - offset, paint)
            }
            if (redacted) {
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                val safety = RedactionCoordinateMapper.DEFAULT_RASTER_SAFETY_PIXELS.toFloat()
                canvas.drawRect(
                    REDACTION.left * IMAGE_WIDTH - safety,
                    REDACTION.top * IMAGE_HEIGHT - safety,
                    REDACTION.right * IMAGE_WIDTH + safety,
                    REDACTION.bottom * IMAGE_HEIGHT + safety,
                    paint,
                )
            }
            val file = File(testRoot, "$name.jpg")
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
                output.flush()
                output.fd.sync()
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun rasterPolicy(
        file: File,
        forbiddenSentinels: Set<String> = emptySet(),
    ) = RedactedPdfVerificationPolicy(
        pages = listOf(
            RedactedPdfPageExpectation(
                widthPoints = PAGE_WIDTH,
                heightPoints = PAGE_HEIGHT,
                expectedImages = listOf(
                    RedactedPdfImageExpectation(
                        0f,
                        0f,
                        PAGE_WIDTH,
                        PAGE_HEIGHT,
                        IMAGE_WIDTH,
                        IMAGE_HEIGHT,
                        requireNotNull(expectedImageHashes[file.absolutePath]),
                    ),
                ),
                requiredOpaqueBlackRegions = listOf(REDACTION),
            ),
        ),
        forbiddenSentinels = forbiddenSentinels,
    )

    private fun textPolicy(
        file: File,
        expectedText: String,
        forbiddenSentinels: Set<String> = emptySet(),
    ) = RedactedPdfVerificationPolicy(
        pages = listOf(
            RedactedPdfPageExpectation(
                widthPoints = IMAGE_WIDTH.toFloat(),
                heightPoints = IMAGE_HEIGHT.toFloat(),
                expectedImages = listOf(
                    RedactedPdfImageExpectation(
                        0f,
                        0f,
                        IMAGE_WIDTH.toFloat(),
                        IMAGE_HEIGHT.toFloat(),
                        IMAGE_WIDTH,
                        IMAGE_HEIGHT,
                        requireNotNull(expectedImageHashes[file.absolutePath]),
                    ),
                ),
                expectedExtractedText = expectedText,
                requiredOpaqueBlackRegions = listOf(REDACTION),
            ),
        ),
        forbiddenSentinels = forbiddenSentinels,
    )

    private fun assertRejected(
        result: RedactedPdfVerificationResult,
        expected: RedactedPdfVerificationFailure,
    ) {
        assertTrue("result=$result", result is RedactedPdfVerificationResult.Rejected)
        assertEquals(expected, (result as RedactedPdfVerificationResult.Rejected).reason)
    }

    private fun renderedCenterPixel(file: File, region: RedactionBounds): Int {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap.getPixel(
                            (((region.left + region.right) / 2f) * bitmap.width).toInt(),
                            (((region.top + region.bottom) / 2f) * bitmap.height).toInt(),
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private companion object {
        const val PAGE_WIDTH = 320f
        const val PAGE_HEIGHT = 440f
        const val IMAGE_WIDTH = 320
        const val IMAGE_HEIGHT = 440
        const val SENTINEL = "RME_SECRET_7F42A9"
        const val SAFE_TEXT = "Allowed outside text"
        val REDACTION = RedactionBounds(0.2f, 0.25f, 0.8f, 0.72f)
    }
}
