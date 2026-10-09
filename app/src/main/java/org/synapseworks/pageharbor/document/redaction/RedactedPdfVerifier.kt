package org.synapseworks.pageharbor.document.redaction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.DeflaterOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact visible placement of one complete opaque image in bottom-left-origin PDF points. */
data class RedactedPdfImageExpectation(
    val leftPoints: Float,
    val bottomPoints: Float,
    val widthPoints: Float,
    val heightPoints: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** SHA-256 of the already-sanitized JPEG bytes passed into fresh PDF reconstruction. */
    val encodedSha256: String,
) {
    init {
        require(listOf(leftPoints, bottomPoints, widthPoints, heightPoints).all(Float::isFinite))
        require(leftPoints >= 0f && bottomPoints >= 0f)
        require(widthPoints > 0f && heightPoints > 0f)
        require(pixelWidth > 0 && pixelHeight > 0)
        require(encodedSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Exact final-page expectations used to prove a newly reconstructed redacted page is simple. */
data class RedactedPdfPageExpectation(
    val widthPoints: Float,
    val heightPoints: Float,
    /** Every embedded image must be drawn once, wholly on-page, at one of these exact placements. */
    val expectedImages: List<RedactedPdfImageExpectation>,
    /** Text extraction is normalized for whitespace before this exact comparison. */
    val expectedExtractedText: String = "",
    /** Exact safe OCR line strings used to derive the only permitted invisible-font CMaps. */
    val expectedFontTexts: List<String> = if (expectedExtractedText.isEmpty()) {
        emptyList()
    } else {
        expectedExtractedText.lines()
    },
    /** Top-left-origin final-page regions whose conservative core must render opaque black. */
    val requiredOpaqueBlackRegions: List<RedactionBounds> = emptyList(),
) {
    init {
        require(widthPoints.isFinite() && widthPoints > 0f)
        require(heightPoints.isFinite() && heightPoints > 0f)
        require(expectedImages.isNotEmpty())
        require(expectedImages.all { image ->
            image.leftPoints + image.widthPoints <= widthPoints &&
                image.bottomPoints + image.heightPoints <= heightPoints
        })
        require(expectedImages.indices.none { first ->
            (first + 1 until expectedImages.size).any { second ->
                expectedImages[first].overlaps(expectedImages[second])
            }
        })
        require(expectedFontTexts.none(String::isBlank))
        require(
            expectedFontTexts.joinToString("\n").normalizedPdfText() ==
                expectedExtractedText.normalizedPdfText(),
        )
    }

    val expectedImageCount: Int get() = expectedImages.size
}

data class RedactedPdfVerificationPolicy(
    val pages: List<RedactedPdfPageExpectation>,
    /** Test-only or defense-in-depth sentinels. Production verification does not need a secret. */
    val forbiddenSentinels: Set<String> = emptySet(),
) {
    init {
        require(pages.isNotEmpty())
        require(forbiddenSentinels.none(String::isBlank))
    }
}

data class RedactedPdfVerificationEvidence(
    val sha256: String,
    val bytes: Long,
    val pageCount: Int,
    val imageObjectCount: Int,
    val searchableTextPageCount: Int,
    /** Binds the evidence to page geometry, text, image counts, and required black regions. */
    val policySha256: String,
) {
    init {
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(bytes > 0L)
        require(pageCount > 0)
        require(imageObjectCount > 0)
        require(searchableTextPageCount in 0..pageCount)
        require(policySha256.matches(Regex("[0-9a-f]{64}")))
    }
}

enum class RedactedPdfVerificationFailure {
    FILE_UNAVAILABLE,
    SOURCE_CHANGED,
    ENCRYPTED,
    MALFORMED,
    PAGE_COUNT_MISMATCH,
    PAGE_GEOMETRY_MISMATCH,
    FORBIDDEN_DOCUMENT_STRUCTURE,
    FORBIDDEN_PAGE_STRUCTURE,
    UNEXPECTED_CONTENT,
    UNSAFE_IMAGE_OBJECT,
    SEARCHABLE_TEXT_MISMATCH,
    FORBIDDEN_SENTINEL_SURVIVED,
    BLACK_REGION_NOT_OPAQUE,
    RENDER_FAILED,
    RESOURCE_LIMIT_EXCEEDED,
}

sealed interface RedactedPdfVerificationResult {
    data class Verified(
        val evidence: RedactedPdfVerificationEvidence,
    ) : RedactedPdfVerificationResult

    data class Rejected(
        val reason: RedactedPdfVerificationFailure,
    ) : RedactedPdfVerificationResult
}

/**
 * Fail-closed verification for a freshly reconstructed redacted PDF.
 *
 * This is deliberately stricter than the M4 input classifier. It accepts only the small PDF
 * surface emitted by RME's raster reconstruction: direct opaque image XObjects, an optional
 * invisible Type 3 searchable layer whose extracted text is exactly allowlisted, and at most one
 * full-page white background fill. It rejects alternate/orphan streams and hidden PDF channels.
 */
class RedactedPdfVerifier(context: Context) {
    private val applicationContext = context.applicationContext ?: context

    init {
        PDFBoxResourceLoader.init(applicationContext)
    }

    suspend fun verify(
        file: File,
        policy: RedactedPdfVerificationPolicy,
    ): RedactedPdfVerificationResult = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            return@withContext RedactedPdfVerificationResult.Rejected(
                RedactedPdfVerificationFailure.FILE_UNAVAILABLE,
            )
        }
        verifySource(
            source = VerificationSource(
                openInput = { runCatching { file.inputStream() }.getOrNull() },
                openDescriptor = {
                    runCatching {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    }.getOrNull()
                },
            ),
            policy = policy,
        )
    }

    suspend fun verify(
        uri: Uri,
        policy: RedactedPdfVerificationPolicy,
    ): RedactedPdfVerificationResult = withContext(Dispatchers.IO) {
        verifySource(
            source = VerificationSource(
                openInput = {
                    try {
                        applicationContext.contentResolver.openInputStream(uri)
                    } catch (_: IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                },
                openDescriptor = {
                    try {
                        applicationContext.contentResolver.openFileDescriptor(uri, "r")
                    } catch (_: IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                },
            ),
            policy = policy,
        )
    }

    /** Fast pre-Save/Share attestation check. Workflow generation ownership remains the caller's. */
    fun stillMatches(file: File, evidence: RedactedPdfVerificationEvidence): Boolean {
        if (!file.isFile || file.length() != evidence.bytes) return false
        val fingerprint = fingerprint(
            openInput = { runCatching { file.inputStream() }.getOrNull() },
            sentinelPatterns = emptyList(),
        ) ?: return false
        return fingerprint.bytes == evidence.bytes && fingerprint.sha256 == evidence.sha256
    }

    private suspend fun verifySource(
        source: VerificationSource,
        policy: RedactedPdfVerificationPolicy,
    ): RedactedPdfVerificationResult {
        val scratchDirectory = createScratchDirectory()
            ?: return rejected(RedactedPdfVerificationFailure.FILE_UNAVAILABLE)
        return try {
            verifySourceInWorkspace(source, policy, scratchDirectory)
        } finally {
            scratchDirectory.deleteRecursivelySafely()
        }
    }

    private suspend fun verifySourceInWorkspace(
        source: VerificationSource,
        policy: RedactedPdfVerificationPolicy,
        scratchDirectory: File,
    ): RedactedPdfVerificationResult {
        val sentinelPatterns = sentinelPatterns(policy.forbiddenSentinels)
        val firstFingerprint = fingerprint(source.openInput, sentinelPatterns)
            ?: return rejected(RedactedPdfVerificationFailure.FILE_UNAVAILABLE)
        if (firstFingerprint.bytes <= 0L) {
            return rejected(RedactedPdfVerificationFailure.MALFORMED)
        }
        if (!firstFingerprint.pdfEnvelopeValid) {
            return rejected(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        if (firstFingerprint.sentinelFound) {
            return rejected(RedactedPdfVerificationFailure.FORBIDDEN_SENTINEL_SURVIVED)
        }

        val input = source.openInput()
            ?: return rejected(RedactedPdfVerificationFailure.FILE_UNAVAILABLE)
        val document = try {
            input.use { stream ->
                PDDocument.load(
                    stream,
                    MemoryUsageSetting.setupMixed(MAX_PDFBOX_MAIN_MEMORY_BYTES)
                        .setTempDir(scratchDirectory),
                )
            }
        } catch (_: InvalidPasswordException) {
            return rejected(RedactedPdfVerificationFailure.ENCRYPTED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return rejected(RedactedPdfVerificationFailure.MALFORMED)
        } catch (_: RuntimeException) {
            return rejected(RedactedPdfVerificationFailure.MALFORMED)
        } catch (_: OutOfMemoryError) {
            return rejected(RedactedPdfVerificationFailure.RESOURCE_LIMIT_EXCEEDED)
        }

        val audit = try {
            document.use { pdf -> auditDocument(pdf, policy, sentinelPatterns) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RedactedPdfVerificationException) {
            return rejected(error.reason)
        } catch (_: IOException) {
            return rejected(RedactedPdfVerificationFailure.MALFORMED)
        } catch (_: RuntimeException) {
            return rejected(RedactedPdfVerificationFailure.MALFORMED)
        } catch (_: OutOfMemoryError) {
            return rejected(RedactedPdfVerificationFailure.RESOURCE_LIMIT_EXCEEDED)
        }

        val descriptor = source.openDescriptor()
            ?: return rejected(RedactedPdfVerificationFailure.FILE_UNAVAILABLE)
        try {
            descriptor.use { renderAndVerify(it, policy) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RedactedPdfVerificationException) {
            return rejected(error.reason)
        } catch (_: IOException) {
            return rejected(RedactedPdfVerificationFailure.RENDER_FAILED)
        } catch (_: RuntimeException) {
            return rejected(RedactedPdfVerificationFailure.RENDER_FAILED)
        } catch (_: OutOfMemoryError) {
            return rejected(RedactedPdfVerificationFailure.RESOURCE_LIMIT_EXCEEDED)
        }

        // Providers and private files are re-opened after every audit. A changing source fails.
        val finalFingerprint = fingerprint(source.openInput, sentinelPatterns)
            ?: return rejected(RedactedPdfVerificationFailure.FILE_UNAVAILABLE)
        if (firstFingerprint != finalFingerprint) {
            return rejected(RedactedPdfVerificationFailure.SOURCE_CHANGED)
        }
        return RedactedPdfVerificationResult.Verified(
            RedactedPdfVerificationEvidence(
                sha256 = finalFingerprint.sha256,
                bytes = finalFingerprint.bytes,
                pageCount = policy.pages.size,
                imageObjectCount = audit.imageObjectCount,
                searchableTextPageCount = audit.searchableTextPageCount,
                policySha256 = policy.sha256(),
            ),
        )
    }

    private fun createScratchDirectory(): File? {
        return try {
            val root = File(applicationContext.cacheDir, REDACTED_PDF_VERIFIER_DIRECTORY)
            if (!root.exists() && !root.mkdirs()) return null
            File.createTempFile("redacted-pdf-verifier-", ".work", root).also { marker ->
                if (!marker.delete() || !marker.mkdir()) return null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun auditDocument(
        document: PDDocument,
        policy: RedactedPdfVerificationPolicy,
        sentinelPatterns: List<ByteArray>,
    ): DocumentAudit {
        coroutineContext.ensureActive()
        if (document.isEncrypted) fail(RedactedPdfVerificationFailure.ENCRYPTED)
        if (document.numberOfPages != policy.pages.size) {
            fail(RedactedPdfVerificationFailure.PAGE_COUNT_MISMATCH)
        }
        if (document.signatureDictionaries.isNotEmpty()) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        val trailer = document.document.trailer
        if (trailer.keySet().map(COSName::getName).toSet() != ALLOWED_TRAILER_KEYS ||
            trailer.getDictionaryObject(COSName.INFO) != null ||
            trailer.getDictionaryObject(COSName.ENCRYPT) != null
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        verifyTrailerId(trailer)
        val catalog = document.documentCatalog
        val catalogDictionary = catalog.cosObject
        if (catalogDictionary.keySet().map(COSName::getName).toSet() != ALLOWED_CATALOG_KEYS ||
            catalogDictionary.getCOSName(COSName.TYPE) != COSName.CATALOG ||
            catalogDictionary.getCOSName(COSName.VERSION) != PDF_VERSION_1_4 ||
            catalog.acroForm != null || catalog.metadata != null ||
            catalog.openAction != null || catalog.names != null ||
            catalog.structureTreeRoot != null || catalog.ocProperties != null ||
            catalog.threads.isNotEmpty() || catalog.outputIntents.isNotEmpty()
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        val pagesDictionary = catalog.pages.cosObject
        if (pagesDictionary.keySet().map(COSName::getName).toSet() != ALLOWED_PAGES_KEYS ||
            pagesDictionary.getCOSName(COSName.TYPE) != COSName.PAGES ||
            pagesDictionary.getInt(COSName.COUNT) != policy.pages.size
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        val pageDictionaries = policy.pages.indices.map { index ->
            document.getPage(index).cosObject
        }
        val pageKids = pagesDictionary.getDictionaryObject(COSName.KIDS) as? COSArray
            ?: fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        if (pageKids.size() != pageDictionaries.size ||
            pageDictionaries.indices.any { index ->
                pageKids[index].dereference() !== pageDictionaries[index]
            }
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }

        val approvedStreams = identitySet<COSStream>()
        val referencedImages = identitySet<COSStream>()
        val approvedFonts = identitySet<COSDictionary>()
        val expectedFonts = expectedInvisibleFonts(policy)
        val matchedFontCMaps = mutableSetOf<String>()
        var searchablePages = 0
        policy.pages.forEachIndexed { pageIndex, expectation ->
            coroutineContext.ensureActive()
            val page = document.getPage(pageIndex)
            if (page.cosObject.getCOSName(COSName.TYPE) != COSName.PAGE ||
                page.cosObject.getDictionaryObject(COSName.PARENT) !== pagesDictionary
            ) {
                fail(RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE)
            }
            verifyPageGeometry(page, expectation)
            val pageAudit = verifyPageContent(
                document = document,
                page = page,
                expectation = expectation,
                approvedStreams = approvedStreams,
                referencedImages = referencedImages,
                approvedFonts = approvedFonts,
                expectedFonts = expectedFonts,
                matchedFontCMaps = matchedFontCMaps,
            )
            val extracted = PDFTextStripper().apply {
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }.getText(document).normalizedPdfText()
            if (extracted != expectation.expectedExtractedText.normalizedPdfText()) {
                fail(RedactedPdfVerificationFailure.SEARCHABLE_TEXT_MISMATCH)
            }
            if (extracted.isNotEmpty()) searchablePages++
            if (pageAudit.imageCount != expectation.expectedImageCount) {
                fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
            }
        }
        if (matchedFontCMaps != expectedFonts.keys) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }

        auditAllObjects(
            document = document,
            approvedStreams = approvedStreams,
            referencedImages = referencedImages,
            sentinelPatterns = sentinelPatterns,
        )
        return DocumentAudit(referencedImages.size, searchablePages)
    }

    private fun verifyTrailerId(trailer: COSDictionary) {
        val id = trailer.getDictionaryObject(COSName.ID) as? COSArray
            ?: fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        if (id.size() != 2) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        val first = id[0].dereference() as? COSString
            ?: fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        val second = id[1].dereference() as? COSString
            ?: fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        if (first.bytes.size != PDF_FILE_ID_BYTES ||
            second.bytes.size != PDF_FILE_ID_BYTES ||
            !first.bytes.contentEquals(second.bytes)
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
    }

    private fun verifyPageGeometry(page: PDPage, expected: RedactedPdfPageExpectation) {
        if (page.rotation != 0 || abs(page.userUnit - 1f) > BOX_TOLERANCE_POINTS) {
            fail(RedactedPdfVerificationFailure.PAGE_GEOMETRY_MISMATCH)
        }
        val media = page.mediaBox
        if (abs(media.lowerLeftX) > BOX_TOLERANCE_POINTS ||
            abs(media.lowerLeftY) > BOX_TOLERANCE_POINTS ||
            abs(media.width - expected.widthPoints) > BOX_TOLERANCE_POINTS ||
            abs(media.height - expected.heightPoints) > BOX_TOLERANCE_POINTS ||
            !sameBox(media, page.cropBox) || !sameBox(media, page.bleedBox) ||
            !sameBox(media, page.trimBox) || !sameBox(media, page.artBox)
        ) {
            fail(RedactedPdfVerificationFailure.PAGE_GEOMETRY_MISMATCH)
        }
        if (page.annotations.isNotEmpty() || page.metadata != null ||
            page.threadBeads.isNotEmpty() || page.transition != null ||
            page.cosObject.keySet().any { it.name !in ALLOWED_PAGE_KEYS }
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE)
        }
    }

    private fun verifyPageContent(
        document: PDDocument,
        page: PDPage,
        expectation: RedactedPdfPageExpectation,
        approvedStreams: MutableSet<COSStream>,
        referencedImages: MutableSet<COSStream>,
        approvedFonts: MutableSet<COSDictionary>,
        expectedFonts: Map<String, ExpectedInvisibleFont>,
        matchedFontCMaps: MutableSet<String>,
    ): PageAudit {
        val resources = page.resources
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        val expectedText = expectation.expectedExtractedText.normalizedPdfText()
        val expectedResourceKeys = buildSet {
            add(COSName.XOBJECT.name)
            if (expectedText.isNotEmpty()) add(COSName.FONT.name)
        }
        if (resources.extGStateNames.any() || resources.propertiesNames.any() ||
            resources.patternNames.any() || resources.shadingNames.any() ||
            resources.cosObject.keySet().map(COSName::getName).toSet() != expectedResourceKeys
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE)
        }

        val imageNames = resources.xObjectNames.toList()
        val expectedImageNames = (1..expectation.expectedImageCount)
            .mapTo(mutableSetOf()) { COSName.getPDFName("Im$it") }
        if (imageNames.size != expectation.expectedImageCount ||
            imageNames.toSet() != expectedImageNames
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val imagesByName = imageNames.associateWith { name ->
            val image = resources.getXObject(name) as? PDImageXObject
                ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
            referencedImages += image.cosObject
            approvedStreams += image.cosObject
            image
        }
        val rawImages = resources.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            ?: fail(RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE)
        if (rawImages.keySet() != imageNames.toSet() || imageNames.any { name ->
                rawImages.getDictionaryObject(name) !== imagesByName.getValue(name).cosObject
            }
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE)
        }

        val fontNames = resources.fontNames.toList()
        if (expectedText.isEmpty() && fontNames.isNotEmpty()) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val expectedFontNames = (1..fontNames.size)
            .mapTo(mutableSetOf()) { COSName.getPDFName("F$it") }
        if (fontNames.toSet() != expectedFontNames) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val fontsByName = linkedMapOf<COSName, PDType3Font>()
        fontNames.forEach { name ->
            val font = resources.getFont(name)
            if (font !is PDType3Font) {
                fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
            }
            fontsByName[name] = font
            if (approvedFonts.add(font.cosObject)) {
                val matchedCMap = approveType3FontStreams(
                    font = font.cosObject,
                    approvedStreams = approvedStreams,
                    expectedFonts = expectedFonts,
                )
                if (!matchedFontCMaps.add(matchedCMap)) {
                    fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                }
            }
        }
        if (expectedText.isNotEmpty()) {
            val rawFonts = resources.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary
                ?: fail(RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE)
            if (rawFonts.keySet() != fontNames.toSet() || fontNames.any { name ->
                    rawFonts.getDictionaryObject(name) !== fontsByName.getValue(name).cosObject
                }
            ) {
                fail(RedactedPdfVerificationFailure.FORBIDDEN_PAGE_STRUCTURE)
            }
        }

        val contentStreams = page.contentStreams.asSequence().toList()
        if (contentStreams.size != 1) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        contentStreams.forEach { approvedStreams += it.cosObject }
        verifyCanonicalPageContent(contentStreams.single().cosObject)
        val parser = PDFStreamParser(page)
        parser.parse()
        val operatorAudit = verifyOperators(
            tokens = parser.tokens,
            page = page,
            expectedTextPresent = expectedText.isNotEmpty(),
            imageNames = imageNames.toSet(),
            fontsByName = fontsByName,
        )
        if (operatorAudit.invokedFonts != fontNames.toSet()) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val matchedImages = operatorAudit.imagePlacements.matchExpected(expectation.expectedImages)
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        val blackRegionsByImage = matchedImages.keys.associateWith {
            mutableListOf<RedactionBounds>()
        }
        expectation.requiredOpaqueBlackRegions.forEach { region ->
            val containing = matchedImages.filterValues { image ->
                image.contains(region, expectation.widthPoints, expectation.heightPoints)
            }.keys
            if (containing.size != 1) {
                fail(RedactedPdfVerificationFailure.BLACK_REGION_NOT_OPAQUE)
            }
            blackRegionsByImage.getValue(containing.single()) += region
        }
        matchedImages.forEach { (name, expected) ->
            verifyImage(
                image = requireNotNull(imagesByName[name]),
                expected = expected,
                page = expectation,
                requiredOpaqueBlackRegions = blackRegionsByImage.getValue(name),
            )
        }
        return PageAudit(imageNames.size)
    }

    private fun verifyImage(
        image: PDImageXObject,
        expected: RedactedPdfImageExpectation,
        page: RedactedPdfPageExpectation,
        requiredOpaqueBlackRegions: List<RedactionBounds>,
    ) {
        if (image.width != expected.pixelWidth || image.height != expected.pixelHeight ||
            image.width.toLong() * image.height > MAX_IMAGE_PIXELS ||
            image.cosObject.getDictionaryObject(COSName.FILTER) != COSName.DCT_DECODE ||
            image.cosObject.getInt(COSName.BITS_PER_COMPONENT) != 8 ||
            image.cosObject.getDictionaryObject(COSName.COLORSPACE) != DEVICE_RGB ||
            image.isStencil || image.mask != null || image.softMask != null ||
            image.colorKeyMask != null || image.decode != null ||
            image.metadata != null || image.optionalContent != null ||
            image.cosObject.keySet().any { it.name !in ALLOWED_IMAGE_KEYS }
        ) {
            fail(RedactedPdfVerificationFailure.UNSAFE_IMAGE_OBJECT)
        }
        val encodedSha256 = try {
            image.cosObject.createRawInputStream().use(::sha256)
        } catch (_: IOException) {
            null
        }
        if (encodedSha256 != expected.encodedSha256) {
            fail(RedactedPdfVerificationFailure.UNSAFE_IMAGE_OBJECT)
        }
        val bitmap = image.image
        try {
            val row = IntArray(bitmap.width)
            repeat(bitmap.height) { y ->
                bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                if (row.any { Color.alpha(it) != 255 }) {
                    fail(RedactedPdfVerificationFailure.UNSAFE_IMAGE_OBJECT)
                }
            }
            requiredOpaqueBlackRegions.forEach { region ->
                if (!bitmap.hasOpaqueBlackRegion(expected, page, region)) {
                    fail(RedactedPdfVerificationFailure.BLACK_REGION_NOT_OPAQUE)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun approveType3FontStreams(
        font: COSDictionary,
        approvedStreams: MutableSet<COSStream>,
        expectedFonts: Map<String, ExpectedInvisibleFont>,
    ): String {
        if (font.keySet().any { it.name !in ALLOWED_TYPE3_FONT_KEYS } ||
            font.getCOSName(COSName.TYPE) != COSName.FONT ||
            font.getCOSName(COSName.SUBTYPE) != COSName.TYPE3 ||
            font.getInt(COSName.FIRST_CHAR) != 1 ||
            !numberArrayEquals(
                font.getDictionaryObject(COSName.FONT_BBOX),
                floatArrayOf(0f, 0f, TYPE3_GLYPH_WIDTH, TYPE3_GLYPH_WIDTH),
            ) ||
            !numberArrayEquals(
                font.getDictionaryObject(COSName.FONT_MATRIX),
                floatArrayOf(0.001f, 0f, 0f, 0.001f, 0f, 0f),
            )
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val lastCharacter = font.getInt(COSName.LAST_CHAR)
        val widths = font.getDictionaryObject(COSName.WIDTHS) as? COSArray
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        val encoding = font.getDictionaryObject(COSName.ENCODING) as? COSDictionary
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        val differences = encoding.getDictionaryObject(COSName.DIFFERENCES) as? COSArray
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        val glyphNames = differences.mapNotNull { value ->
            (value.dereference() as? COSName)?.name
        }
        if (lastCharacter <= 0 || widths.size() != lastCharacter ||
            widths.any { value -> (value.dereference() as? COSNumber)?.intValue() != 1_000 } ||
            encoding.keySet().map(COSName::getName).toSet() != ALLOWED_TYPE3_ENCODING_KEYS ||
            encoding.getCOSName(COSName.TYPE) != COSName.ENCODING ||
            (differences.firstOrNull()?.dereference() as? COSNumber)?.intValue() != 1 ||
            glyphNames.size != lastCharacter ||
            glyphNames != (1..lastCharacter).map { index -> "g$index" }
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val fontResources = font.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        if (fontResources.keySet().isNotEmpty()) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val toUnicode = font.getDictionaryObject(COSName.TO_UNICODE) as? COSStream
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        val toUnicodeBytes = canonicalDecodedStreamBytes(toUnicode)
        val toUnicodeSha256 = sha256(toUnicodeBytes)
        val expectedFont = expectedFonts[toUnicodeSha256]
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        if (lastCharacter != expectedFont.codePointCount ||
            !toUnicodeBytes.contentEquals(expectedFont.cMapBytes)
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        approvedStreams += toUnicode
        val charProcs = font.getDictionaryObject(COSName.CHAR_PROCS) as? COSDictionary
            ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        if (charProcs.keySet().map(COSName::getName).toSet() != glyphNames.toSet()) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val glyphStreams = identitySet<COSStream>()
        charProcs.getValues().forEach { value ->
            val stream = value.dereference() as? COSStream
                ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
            glyphStreams += stream
            approvedStreams += stream
        }
        if (glyphStreams.size != 1 ||
            !canonicalDecodedStreamBytes(glyphStreams.single()).contentEquals(TYPE3_BLANK_GLYPH)
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        return toUnicodeSha256
    }

    private fun verifyOperators(
        tokens: List<Any>,
        page: PDPage,
        expectedTextPresent: Boolean,
        imageNames: Set<COSName>,
        fontsByName: Map<COSName, PDType3Font>,
    ): OperatorAudit {
        val operands = mutableListOf<Any>()
        val invokedImages = mutableListOf<COSName>()
        val invokedFonts = mutableSetOf<COSName>()
        val imagePlacements = mutableListOf<ImagePlacement>()
        val fillWhiteStack = ArrayDeque<Boolean>()
        var fillIsWhite = false
        var pendingFullPageRectangle = false
        var whiteBackgroundFills = 0
        var graphicsDepth = 0
        var pendingImagePlacement: PdfImagePlacement? = null
        var pendingImageFrameDepth: Int? = null
        var imageFrameAwaitingClose = false
        var inText = false
        var invisibleText = false
        var currentFont: COSName? = null
        tokens.forEach { token ->
            if (token !is Operator) {
                operands += token
                return@forEach
            }
            if (pendingImagePlacement != null && token.name != "Do" ||
                imageFrameAwaitingClose && token.name != "Q"
            ) {
                fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
            }
            when (token.name) {
                "q" -> {
                    requireNoOperands(operands)
                    if (pendingImagePlacement != null || imageFrameAwaitingClose) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    fillWhiteStack.addLast(fillIsWhite)
                    graphicsDepth++
                }
                "Q" -> {
                    requireNoOperands(operands)
                    if (graphicsDepth <= 0 || fillWhiteStack.isEmpty()) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    if (pendingImagePlacement != null ||
                        imageFrameAwaitingClose && pendingImageFrameDepth != graphicsDepth
                    ) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    if (imageFrameAwaitingClose) {
                        imageFrameAwaitingClose = false
                        pendingImageFrameDepth = null
                    }
                    fillIsWhite = fillWhiteStack.removeLast()
                    graphicsDepth--
                }
                "cm" -> {
                    if (graphicsDepth <= 0 || pendingImagePlacement != null ||
                        imageFrameAwaitingClose || inText
                    ) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    val values = requireNumbers(operands, 6).map(COSNumber::floatValue)
                    val width = values[0]
                    val bottomShear = values[1]
                    val sideShear = values[2]
                    val height = values[3]
                    val left = values[4]
                    val bottom = values[5]
                    if (values.any { !it.isFinite() } ||
                        abs(bottomShear) > BOX_TOLERANCE_POINTS ||
                        abs(sideShear) > BOX_TOLERANCE_POINTS ||
                        width <= 0f || height <= 0f ||
                        left < -BOX_TOLERANCE_POINTS || bottom < -BOX_TOLERANCE_POINTS ||
                        left + width > page.mediaBox.width + BOX_TOLERANCE_POINTS ||
                        bottom + height > page.mediaBox.height + BOX_TOLERANCE_POINTS
                    ) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    pendingImagePlacement = PdfImagePlacement(
                        leftPoints = left.coerceAtLeast(0f),
                        bottomPoints = bottom.coerceAtLeast(0f),
                        widthPoints = width,
                        heightPoints = height,
                    )
                    pendingImageFrameDepth = graphicsDepth
                }
                "Do" -> {
                    if (imageFrameAwaitingClose || pendingImageFrameDepth != graphicsDepth) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    val name = operands.singleOrNull() as? COSName
                        ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    if (name !in imageNames) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    val placement = pendingImagePlacement
                        ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    invokedImages += name
                    imagePlacements += ImagePlacement(name, placement)
                    pendingImagePlacement = null
                    imageFrameAwaitingClose = true
                }
                "rg" -> {
                    fillIsWhite = requireNumbers(operands, 3).all(::isOne)
                    if (!fillIsWhite) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                }
                "g" -> {
                    fillIsWhite = isOne(requireNumbers(operands, 1).single())
                    if (!fillIsWhite) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                }
                "re" -> {
                    val values = requireNumbers(operands, 4)
                    pendingFullPageRectangle =
                        abs(values[0].floatValue()) <= BOX_TOLERANCE_POINTS &&
                        abs(values[1].floatValue()) <= BOX_TOLERANCE_POINTS &&
                        abs(values[2].floatValue() - page.mediaBox.width) <= BOX_TOLERANCE_POINTS &&
                        abs(values[3].floatValue() - page.mediaBox.height) <= BOX_TOLERANCE_POINTS
                    if (!pendingFullPageRectangle) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                }
                "f", "F", "f*" -> {
                    requireNoOperands(operands)
                    if (!fillIsWhite || !pendingFullPageRectangle || whiteBackgroundFills != 0 ||
                        invokedImages.isNotEmpty()
                    ) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    pendingFullPageRectangle = false
                    whiteBackgroundFills++
                }
                "BT" -> {
                    requireTextAllowed(expectedTextPresent, operands)
                    if (inText) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    inText = true
                    invisibleText = false
                    currentFont = null
                }
                "ET" -> {
                    requireTextAllowed(expectedTextPresent, operands)
                    if (!inText) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    inText = false
                    invisibleText = false
                    currentFont = null
                }
                "Tr" -> {
                    requireTextAllowed(expectedTextPresent, emptyList())
                    val mode = (operands.singleOrNull() as? COSNumber)?.intValue()
                    if (!inText || mode != INVISIBLE_TEXT_RENDERING_MODE) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    invisibleText = true
                }
                "Tf" -> {
                    requireTextAllowed(expectedTextPresent, emptyList())
                    if (!inText || operands.size != 2 || operands[0] !is COSName ||
                        operands[1] !is COSNumber
                    ) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    val fontName = operands[0] as COSName
                    val fontSize = (operands[1] as COSNumber).floatValue()
                    if (fontName !in fontsByName || !fontSize.isFinite() || fontSize <= 0f) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                    currentFont = fontName
                    invokedFonts += fontName
                }
                "Tz", "Td", "Tm", "Tc", "Tw", "TL", "Ts" -> {
                    requireTextAllowed(expectedTextPresent, emptyList())
                    if (!inText || operands.any { it !is COSNumber }) {
                        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    }
                }
                "Tj" -> {
                    requireTextAllowed(expectedTextPresent, emptyList())
                    val encoded = operands.singleOrNull() as? COSString
                        ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    val fontName = currentFont
                        ?: fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                    val lastCharacter = fontsByName.getValue(fontName).cosObject
                        .getInt(COSName.LAST_CHAR)
                    if (!inText || !invisibleText || encoded.bytes.isEmpty() ||
                        encoded.bytes.any { byte ->
                            val code = byte.toInt() and 0xFF
                            code !in 1..lastCharacter
                        }
                    ) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
                }
                else -> fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
            }
            operands.clear()
        }
        if (operands.isNotEmpty() || graphicsDepth != 0 || inText ||
            pendingImagePlacement != null || pendingImageFrameDepth != null ||
            imageFrameAwaitingClose ||
            invokedImages.size != imageNames.size || invokedImages.toSet() != imageNames
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        if (imagePlacements.indices.any { first ->
                (first + 1 until imagePlacements.size).any { second ->
                    imagePlacements[first].placement.overlaps(imagePlacements[second].placement)
                }
            }
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        return OperatorAudit(imagePlacements, invokedFonts)
    }

    /**
     * Accept only the canonical lexical surface emitted by PDPageContentStream. PDF parsers ignore
     * comments and tolerate arbitrary whitespace; allowing either would leave an inert payload
     * channel even when the parsed operator sequence is safe.
     */
    private fun verifyCanonicalPageContent(stream: COSStream) {
        val decoded = canonicalDecodedStreamBytes(stream)
        if (decoded.isEmpty() || decoded.last() != '\n'.code.toByte() ||
            decoded.any { byte ->
                val value = byte.toInt() and 0xFF
                value != '\n'.code && value !in 0x20..0x7E
            } || decoded.any { it == '%'.code.toByte() }
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
        val lines = decoded.toString(StandardCharsets.US_ASCII).split('\n').dropLast(1)
        if (lines.isEmpty() || lines.any { line ->
                line.isEmpty() || line.startsWith(' ') || line.endsWith(' ') || "  " in line
            }
        ) {
            fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
        }
    }

    /**
     * Reject alternate filters, decode parameters, concatenated streams, and trailing compressed
     * payloads. The remaining raw representation is either the decoded bytes themselves or the
     * deterministic zlib encoding produced by the same runtime used by the fresh generator.
     */
    private fun canonicalDecodedStreamBytes(stream: COSStream): ByteArray {
        val filter = stream.getDictionaryObject(COSName.FILTER)
        val allowedKeys = when (filter) {
            null -> setOf(COSName.LENGTH.name)
            COSName.FLATE_DECODE -> setOf(COSName.LENGTH.name, COSName.FILTER.name)
            else -> fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        if (stream.keySet().map(COSName::getName).toSet() != allowedKeys) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        val raw = stream.createRawInputStream().use(::readBoundedNonImageStream)
        if (stream.getInt(COSName.LENGTH) != raw.size) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        val decoded = stream.createInputStream().use(::readBoundedNonImageStream)
        val canonicalRaw = if (filter == null) {
            decoded
        } else {
            ByteArrayOutputStream().use { output ->
                DeflaterOutputStream(output).use { compressed -> compressed.write(decoded) }
                output.toByteArray()
            }
        }
        if (!raw.contentEquals(canonicalRaw)) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        return decoded
    }

    private fun readBoundedNonImageStream(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_DECODED_NON_IMAGE_BYTES) {
                fail(RedactedPdfVerificationFailure.RESOURCE_LIMIT_EXCEEDED)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun numberArrayEquals(value: COSBase?, expected: FloatArray): Boolean {
        val array = value?.dereference() as? COSArray ?: return false
        if (array.size() != expected.size) return false
        return expected.indices.all { index ->
            val actual = (array[index].dereference() as? COSNumber)?.floatValue() ?: return@all false
            actual.isFinite() && abs(actual - expected[index]) <= NUMBER_TOLERANCE
        }
    }

    private fun expectedInvisibleFonts(
        policy: RedactedPdfVerificationPolicy,
    ): Map<String, ExpectedInvisibleFont> {
        val distinctCodePoints = linkedSetOf<Int>()
        policy.pages.forEach { page ->
            page.expectedFontTexts.forEach { text ->
                text.securePdfCodePoints().forEach(distinctCodePoints::add)
            }
        }
        return distinctCodePoints.toList().chunked(MAX_CODES_PER_TYPE3_FONT).associate { codePoints ->
            val cMapBytes = canonicalToUnicodeCMap(codePoints)
            val digest = sha256(cMapBytes)
            digest to ExpectedInvisibleFont(codePoints.size, cMapBytes)
        }
    }

    private fun canonicalToUnicodeCMap(codePoints: List<Int>): ByteArray {
        val mapping = codePoints.mapIndexed { index, codePoint -> codePoint to index + 1 }
        return buildString {
            append("/CIDInit /ProcSet findresource begin\n")
            append("12 dict begin\nbegincmap\n")
            append("/CIDSystemInfo << /Registry (RME) /Ordering (Unicode) /Supplement 0 >> def\n")
            append("/CMapName /RMEInvisibleUnicode def\n/CMapType 2 def\n")
            append("1 begincodespacerange\n<01> <FF>\nendcodespacerange\n")
            mapping.chunked(MAX_BFCHAR_ENTRIES).forEach { entries ->
                append(entries.size).append(" beginbfchar\n")
                entries.forEach { (codePoint, code) ->
                    append('<').append(code.toString(16).padStart(2, '0').uppercase()).append("> <")
                    append(
                        String(Character.toChars(codePoint))
                            .toByteArray(StandardCharsets.UTF_16BE)
                            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) },
                    )
                    append(">\n")
                }
                append("endbfchar\n")
            }
            append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        }.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun String.securePdfCodePoints(): List<Int> = buildList {
        var index = 0
        while (index < this@securePdfCodePoints.length) {
            val original = this@securePdfCodePoints.codePointAt(index)
            index += Character.charCount(original)
            add(
                when {
                    original in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code ||
                        !Character.isValidCodePoint(original) -> REPLACEMENT_CHARACTER
                    Character.isISOControl(original) -> SPACE_CHARACTER
                    else -> original
                },
            )
        }
    }

    private suspend fun auditAllObjects(
        document: PDDocument,
        approvedStreams: Set<COSStream>,
        referencedImages: Set<COSStream>,
        sentinelPatterns: List<ByteArray>,
    ) {
        val allObjectStreams = identitySet<COSStream>()
        val reachableObjects = identitySet<COSBase>()
        val allowedStrings = identitySet<COSString>()
        (document.document.trailer.getDictionaryObject(COSName.ID) as? COSArray)
            ?.forEach { value ->
                (value.dereference() as? COSString)?.let(allowedStrings::add)
            }
        collectStreamsAndScanStrings(
            base = document.document.trailer,
            streams = allObjectStreams,
            sentinelPatterns = sentinelPatterns,
            allowedStrings = allowedStrings,
            visited = reachableObjects,
        )
        document.document.objects.forEach { objectValue ->
            coroutineContext.ensureActive()
            if (objectValue.dereference() !in reachableObjects) {
                fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
            }
        }
        if (allObjectStreams.size != approvedStreams.size ||
            allObjectStreams.any { it !in approvedStreams } ||
            approvedStreams.any { it !in allObjectStreams }
        ) {
            fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
        }
        val globalImages = allObjectStreams.filterTo(identitySet()) { stream ->
            stream.getCOSName(COSName.SUBTYPE) == COSName.IMAGE
        }
        if (globalImages.size != referencedImages.size ||
            globalImages.any { it !in referencedImages }
        ) {
            fail(RedactedPdfVerificationFailure.UNSAFE_IMAGE_OBJECT)
        }

        var decodedBytes = 0L
        approvedStreams.filter { it !in referencedImages }.forEach { stream ->
            coroutineContext.ensureActive()
            val decoded = canonicalDecodedStreamBytes(stream)
            decodedBytes += decoded.size
            if (decodedBytes > MAX_DECODED_NON_IMAGE_BYTES) {
                fail(RedactedPdfVerificationFailure.RESOURCE_LIMIT_EXCEEDED)
            }
            if (containsPattern(decoded, sentinelPatterns)) {
                fail(RedactedPdfVerificationFailure.FORBIDDEN_SENTINEL_SURVIVED)
            }
        }
    }

    private fun collectStreamsAndScanStrings(
        base: COSBase?,
        streams: MutableSet<COSStream>,
        sentinelPatterns: List<ByteArray>,
        allowedStrings: Set<COSString>,
        visited: MutableSet<COSBase>,
    ) {
        val value = base?.dereference() ?: return
        if (!visited.add(value)) return
        when (value) {
            is COSString -> {
                if (containsPattern(value.bytes, sentinelPatterns)) {
                    fail(RedactedPdfVerificationFailure.FORBIDDEN_SENTINEL_SURVIVED)
                }
                if (value !in allowedStrings) {
                    fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
                }
            }
            is COSName -> if (
                containsPattern(value.name.toByteArray(StandardCharsets.UTF_8), sentinelPatterns)
            ) {
                fail(RedactedPdfVerificationFailure.FORBIDDEN_SENTINEL_SURVIVED)
            }
            is COSArray -> value.forEach { item ->
                collectStreamsAndScanStrings(
                    item,
                    streams,
                    sentinelPatterns,
                    allowedStrings,
                    visited,
                )
            }
            is COSDictionary -> {
                if (value is COSStream) streams += value
                val type = value.getCOSName(COSName.TYPE)?.name
                val subtype = value.getCOSName(COSName.SUBTYPE)?.name
                if (type in FORBIDDEN_OBJECT_TYPES || subtype in FORBIDDEN_OBJECT_TYPES ||
                    value.keySet().any { it.name in FORBIDDEN_OBJECT_KEYS }
                ) {
                    fail(RedactedPdfVerificationFailure.FORBIDDEN_DOCUMENT_STRUCTURE)
                }
                value.entrySet().forEach { (key, child) ->
                    if (containsPattern(key.name.toByteArray(StandardCharsets.UTF_8), sentinelPatterns)) {
                        fail(RedactedPdfVerificationFailure.FORBIDDEN_SENTINEL_SURVIVED)
                    }
                    collectStreamsAndScanStrings(
                        child,
                        streams,
                        sentinelPatterns,
                        allowedStrings,
                        visited,
                    )
                }
            }
        }
    }

    private suspend fun renderAndVerify(
        descriptor: ParcelFileDescriptor,
        policy: RedactedPdfVerificationPolicy,
    ) {
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount != policy.pages.size) {
                fail(RedactedPdfVerificationFailure.PAGE_COUNT_MISMATCH)
            }
            policy.pages.forEachIndexed { pageIndex, expectation ->
                coroutineContext.ensureActive()
                renderer.openPage(pageIndex).use { page ->
                    val scale = (RENDER_LONG_EDGE_PX.toFloat() / max(page.width, page.height))
                        .coerceAtMost(MAX_RENDER_SCALE)
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = createBitmap(width, height)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        expectation.requiredOpaqueBlackRegions.forEach { region ->
                            if (!isOpaqueBlackCore(bitmap, region)) {
                                fail(RedactedPdfVerificationFailure.BLACK_REGION_NOT_OPAQUE)
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun isOpaqueBlackCore(bitmap: Bitmap, region: RedactionBounds): Boolean {
        var left = floor(region.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        var top = floor(region.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        var right = ceil(region.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        var bottom = ceil(region.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        // The compositor expands before JPEG encoding. Ignore one rendered boundary pixel where
        // resampling/DCT noise can blend permitted neighboring content into the black edge.
        if (right - left > 2) {
            left++
            right--
        }
        if (bottom - top > 2) {
            top++
            bottom--
        }
        val row = IntArray(right - left)
        for (y in top until bottom) {
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            if (row.any { pixel ->
                    Color.alpha(pixel) != 255 || Color.red(pixel) > MAX_BLACK_CHANNEL ||
                        Color.green(pixel) > MAX_BLACK_CHANNEL ||
                        Color.blue(pixel) > MAX_BLACK_CHANNEL
                }
            ) return false
        }
        return true
    }

    private fun RedactedPdfImageExpectation.contains(
        region: RedactionBounds,
        pageWidth: Float,
        pageHeight: Float,
    ): Boolean {
        val regionLeft = region.left * pageWidth
        val regionRight = region.right * pageWidth
        val regionTop = region.top * pageHeight
        val regionBottom = region.bottom * pageHeight
        val imageTop = pageHeight - bottomPoints - heightPoints
        val imageBottom = pageHeight - bottomPoints
        return regionLeft >= leftPoints - BOX_TOLERANCE_POINTS &&
            regionRight <= leftPoints + widthPoints + BOX_TOLERANCE_POINTS &&
            regionTop >= imageTop - BOX_TOLERANCE_POINTS &&
            regionBottom <= imageBottom + BOX_TOLERANCE_POINTS
    }

    private fun Bitmap.hasOpaqueBlackRegion(
        image: RedactedPdfImageExpectation,
        page: RedactedPdfPageExpectation,
        region: RedactionBounds,
    ): Boolean {
        val imageTop = page.heightPoints - image.bottomPoints - image.heightPoints
        val localLeft = (region.left * page.widthPoints - image.leftPoints) / image.widthPoints
        val localRight = (region.right * page.widthPoints - image.leftPoints) / image.widthPoints
        val localTop = (region.top * page.heightPoints - imageTop) / image.heightPoints
        val localBottom = (region.bottom * page.heightPoints - imageTop) / image.heightPoints
        val left = floor(localLeft * width).toInt().coerceIn(0, width - 1)
        val top = floor(localTop * height).toInt().coerceIn(0, height - 1)
        val right = ceil(localRight * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(localBottom * height).toInt().coerceIn(top + 1, height)
        val row = IntArray(right - left)
        for (y in top until bottom) {
            getPixels(row, 0, row.size, left, y, row.size, 1)
            if (row.any { pixel ->
                    Color.alpha(pixel) != 255 || Color.red(pixel) > MAX_BLACK_CHANNEL ||
                        Color.green(pixel) > MAX_BLACK_CHANNEL ||
                        Color.blue(pixel) > MAX_BLACK_CHANNEL
                }
            ) return false
        }
        return true
    }

    private fun fingerprint(
        openInput: () -> InputStream?,
        sentinelPatterns: List<ByteArray>,
    ): Fingerprint? {
        val input = openInput() ?: return null
        return try {
            input.use { source ->
                val digest = MessageDigest.getInstance("SHA-256")
                val scanner = StreamingPatternScanner(sentinelPatterns)
                val envelopeScanner = PdfEnvelopeScanner()
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var bytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    bytes += count
                    if (bytes > MAX_PDF_BYTES) return null
                    digest.update(buffer, 0, count)
                    scanner.consume(buffer, count)
                    envelopeScanner.consume(buffer, count)
                }
                Fingerprint(
                    sha256 = digest.digest().toHex(),
                    bytes = bytes,
                    sentinelFound = scanner.found,
                    pdfEnvelopeValid = envelopeScanner.isValid(),
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun RedactedPdfVerificationPolicy.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(byteArrayOf(0))
            digest.update(bytes)
        }
        pages.forEach { page ->
            add(page.widthPoints.toBits().toString())
            add(page.heightPoints.toBits().toString())
            add(page.expectedImageCount.toString())
            page.expectedImages.forEach { image ->
                add(image.leftPoints.toBits().toString())
                add(image.bottomPoints.toBits().toString())
                add(image.widthPoints.toBits().toString())
                add(image.heightPoints.toBits().toString())
                add(image.pixelWidth.toString())
                add(image.pixelHeight.toString())
                add(image.encodedSha256)
            }
            add(page.expectedExtractedText.normalizedPdfText())
            page.expectedFontTexts.forEach(::add)
            page.requiredOpaqueBlackRegions.forEach { bounds ->
                add(bounds.left.toBits().toString())
                add(bounds.top.toBits().toString())
                add(bounds.right.toBits().toString())
                add(bounds.bottom.toBits().toString())
            }
        }
        forbiddenSentinels.sorted().forEach(::add)
        return digest.digest().toHex()
    }

    private fun rejected(reason: RedactedPdfVerificationFailure) =
        RedactedPdfVerificationResult.Rejected(reason)

    private data class VerificationSource(
        val openInput: () -> InputStream?,
        val openDescriptor: () -> ParcelFileDescriptor?,
    )

    private data class Fingerprint(
        val sha256: String,
        val bytes: Long,
        val sentinelFound: Boolean,
        val pdfEnvelopeValid: Boolean,
    )

    private data class DocumentAudit(
        val imageObjectCount: Int,
        val searchableTextPageCount: Int,
    )

    private data class PageAudit(val imageCount: Int)

    private data class OperatorAudit(
        val imagePlacements: List<ImagePlacement>,
        val invokedFonts: Set<COSName>,
    )

    private data class ExpectedInvisibleFont(
        val codePointCount: Int,
        val cMapBytes: ByteArray,
    )

    private data class ImagePlacement(
        val name: COSName,
        val placement: PdfImagePlacement,
    )

    private data class PdfImagePlacement(
        val leftPoints: Float,
        val bottomPoints: Float,
        val widthPoints: Float,
        val heightPoints: Float,
    )

    private fun List<ImagePlacement>.matchExpected(
        expected: List<RedactedPdfImageExpectation>,
    ): Map<COSName, RedactedPdfImageExpectation>? {
        if (size != expected.size) return null
        val unmatched = expected.toMutableList()
        val matches = linkedMapOf<COSName, RedactedPdfImageExpectation>()
        forEach { actual ->
            val matchIndex = unmatched.indexOfFirst { candidate ->
                abs(actual.placement.leftPoints - candidate.leftPoints) <= BOX_TOLERANCE_POINTS &&
                    abs(actual.placement.bottomPoints - candidate.bottomPoints) <=
                    BOX_TOLERANCE_POINTS &&
                    abs(actual.placement.widthPoints - candidate.widthPoints) <=
                    BOX_TOLERANCE_POINTS &&
                    abs(actual.placement.heightPoints - candidate.heightPoints) <=
                    BOX_TOLERANCE_POINTS
            }
            if (matchIndex < 0) return null
            matches[actual.name] = unmatched.removeAt(matchIndex)
        }
        return matches.takeIf { unmatched.isEmpty() }
    }

    private fun PdfImagePlacement.overlaps(other: PdfImagePlacement): Boolean =
        leftPoints < other.leftPoints + other.widthPoints &&
            leftPoints + widthPoints > other.leftPoints &&
            bottomPoints < other.bottomPoints + other.heightPoints &&
            bottomPoints + heightPoints > other.bottomPoints

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1_024
        const val MAX_PDF_BYTES = 256L * 1_024L * 1_024L
        const val MAX_PDFBOX_MAIN_MEMORY_BYTES = 8L * 1_024L * 1_024L
        const val MAX_DECODED_NON_IMAGE_BYTES = 32L * 1_024L * 1_024L
        const val MAX_IMAGE_PIXELS = 50_000_000L
        const val RENDER_LONG_EDGE_PX = 1_024
        const val MAX_RENDER_SCALE = 2f
        const val MAX_BLACK_CHANNEL = 16
        const val BOX_TOLERANCE_POINTS = 0.25f
        const val NUMBER_TOLERANCE = 0.000_001f
        const val INVISIBLE_TEXT_RENDERING_MODE = 3
        const val PDF_FILE_ID_BYTES = 16
        const val TYPE3_GLYPH_WIDTH = 1_000f
        const val MAX_CODES_PER_TYPE3_FONT = 255
        const val MAX_BFCHAR_ENTRIES = 100
        const val REPLACEMENT_CHARACTER = 0xFFFD
        const val SPACE_CHARACTER = 0x20

        val ALLOWED_TRAILER_KEYS = setOf("Root", "ID", "Size")
        val ALLOWED_CATALOG_KEYS = setOf("Type", "Version", "Pages")
        val ALLOWED_PAGES_KEYS = setOf("Type", "Kids", "Count")

        val ALLOWED_PAGE_KEYS = setOf(
            "Type", "Parent", "Resources", "MediaBox", "Contents",
            "CropBox", "BleedBox", "TrimBox", "ArtBox", "Rotate", "UserUnit",
        )
        val ALLOWED_IMAGE_KEYS = setOf(
            "Type", "Subtype", "Length", "Filter", "Width", "Height", "ColorSpace",
            "BitsPerComponent",
        )
        val DEVICE_RGB: COSName = COSName.getPDFName("DeviceRGB")
        val PDF_VERSION_1_4: COSName = COSName.getPDFName("1.4")
        val ALLOWED_TYPE3_FONT_KEYS = setOf(
            "Type", "Subtype", "FontBBox", "FontMatrix", "FirstChar", "LastChar",
            "Resources", "Widths", "CharProcs", "Encoding", "ToUnicode",
        )
        val ALLOWED_TYPE3_ENCODING_KEYS = setOf("Type", "Differences")
        val FORBIDDEN_OBJECT_KEYS = setOf(
            "AF", "EmbeddedFiles", "JavaScript", "JS", "OpenAction", "AA", "OCProperties",
            "OC", "StructTreeRoot", "ActualText", "Alt", "PieceInfo", "Collection", "Metadata",
            "Alternates", "Thumb",
        )
        val FORBIDDEN_OBJECT_TYPES = setOf("Filespec", "EmbeddedFile", "Metadata")
        val TYPE3_BLANK_GLYPH = "1000 0 d0\n".toByteArray(StandardCharsets.US_ASCII)
    }
}

internal fun deleteStaleRedactedPdfVerifierFiles(
    cacheDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    minimumAgeMillis: Long = REDACTED_PDF_VERIFIER_MAX_AGE_MILLIS,
) {
    require(minimumAgeMillis >= 0L)
    File(cacheDirectory, REDACTED_PDF_VERIFIER_DIRECTORY).listFiles()?.forEach { file ->
        if (file.lastModified() <= nowMillis &&
            nowMillis - file.lastModified() >= minimumAgeMillis
        ) {
            file.deleteRecursivelySafely()
        }
    }
}

private fun RedactedPdfImageExpectation.overlaps(other: RedactedPdfImageExpectation): Boolean =
    leftPoints < other.leftPoints + other.widthPoints &&
        leftPoints + widthPoints > other.leftPoints &&
        bottomPoints < other.bottomPoints + other.heightPoints &&
        bottomPoints + heightPoints > other.bottomPoints

private fun File.deleteRecursivelySafely() {
    try {
        if (exists()) deleteRecursively()
    } catch (_: SecurityException) {
        // Private cleanup is best effort and never exposes sensitive paths through logging.
    }
}

private const val REDACTED_PDF_VERIFIER_DIRECTORY = "redacted-pdf-verifier"
private const val REDACTED_PDF_VERIFIER_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L

/** Rejects incremental/trailing revisions and payload bytes outside the freshly written PDF. */
private class PdfEnvelopeScanner {
    private val prefix = ByteArrayOutputStream(PDF_PREFIX_CAPTURE_BYTES)
    private var tail = ByteArray(0)
    private var eofMarkers = 0

    fun consume(buffer: ByteArray, count: Int) {
        if (count <= 0) return
        if (prefix.size() < PDF_PREFIX_CAPTURE_BYTES) {
            prefix.write(buffer, 0, minOf(count, PDF_PREFIX_CAPTURE_BYTES - prefix.size()))
        }
        val combined = ByteArray(tail.size + count)
        tail.copyInto(combined)
        buffer.copyInto(combined, tail.size, 0, count)
        for (offset in 0..(combined.size - PDF_EOF_MARKER.size).coerceAtLeast(-1)) {
            if (offset + PDF_EOF_MARKER.size > tail.size &&
                PDF_EOF_MARKER.indices.all { index ->
                    combined[offset + index] == PDF_EOF_MARKER[index]
                }
            ) {
                eofMarkers++
            }
        }
        val retained = minOf(PDF_ENVELOPE_TAIL_BYTES, combined.size)
        tail = combined.copyOfRange(combined.size - retained, combined.size)
    }

    fun isValid(): Boolean {
        val startsCorrectly = prefix.toByteArray().let { bytes ->
            bytes.size >= PDF_HEADER.size && PDF_HEADER.indices.all { index ->
                bytes[index] == PDF_HEADER[index]
            }
        }
        val endsCorrectly = tail.endsWith(PDF_EOF_LF) || tail.endsWith(PDF_EOF_CRLF)
        return startsCorrectly && endsCorrectly && eofMarkers == 1
    }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && suffix.indices.all { index ->
            this[size - suffix.size + index] == suffix[index]
        }

    private companion object {
        const val PDF_PREFIX_CAPTURE_BYTES = 16
        const val PDF_ENVELOPE_TAIL_BYTES = 16
        val PDF_HEADER = byteArrayOf(
            '%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(),
            '-'.code.toByte(), '1'.code.toByte(), '.'.code.toByte(), '4'.code.toByte(),
            '\n'.code.toByte(), '%'.code.toByte(),
            0xF6.toByte(), 0xE4.toByte(), 0xFC.toByte(), 0xDF.toByte(), '\n'.code.toByte(),
        )
        val PDF_EOF_MARKER = "%%EOF".toByteArray(StandardCharsets.US_ASCII)
        val PDF_EOF_LF = "%%EOF\n".toByteArray(StandardCharsets.US_ASCII)
        val PDF_EOF_CRLF = "%%EOF\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
}

private class StreamingPatternScanner(private val patterns: List<ByteArray>) {
    private val maximumPatternBytes = patterns.maxOfOrNull(ByteArray::size) ?: 0
    private var tail = ByteArray(0)
    var found: Boolean = false
        private set

    fun consume(buffer: ByteArray, count: Int) {
        if (found || patterns.isEmpty() || count <= 0) return
        val combined = ByteArray(tail.size + count)
        tail.copyInto(combined)
        buffer.copyInto(combined, tail.size, 0, count)
        found = containsPattern(combined, patterns)
        val retained = (maximumPatternBytes - 1).coerceAtLeast(0).coerceAtMost(combined.size)
        tail = combined.copyOfRange(combined.size - retained, combined.size)
    }
}

private fun sentinelPatterns(sentinels: Set<String>): List<ByteArray> = sentinels
    .flatMap { sentinel ->
        listOf(
            sentinel.toByteArray(StandardCharsets.UTF_8),
            sentinel.toByteArray(StandardCharsets.UTF_16BE),
            sentinel.toByteArray(StandardCharsets.UTF_16LE),
        )
    }
    .filter(ByteArray::isNotEmpty)
    .fold(mutableListOf()) { unique, candidate ->
        if (unique.none { it.contentEquals(candidate) }) unique += candidate
        unique
    }

private fun sha256(input: InputStream): String = input.use { source ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1_024)
    while (true) {
        val count = source.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toHex()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun containsPattern(source: ByteArray, patterns: List<ByteArray>): Boolean =
    patterns.any { pattern ->
        if (pattern.isEmpty() || pattern.size > source.size) return@any false
        (0..source.size - pattern.size).any { offset ->
            pattern.indices.all { index -> source[offset + index] == pattern[index] }
        }
    }

private fun requireNumbers(operands: List<Any>, count: Int): List<COSNumber> {
    if (operands.size != count || operands.any { it !is COSNumber }) {
        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
    }
    return operands.filterIsInstance<COSNumber>()
}

private fun requireNoOperands(operands: List<Any>) {
    if (operands.isNotEmpty()) fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
}

private fun requireTextAllowed(allowed: Boolean, operands: List<Any>) {
    if (!allowed || operands.isNotEmpty()) {
        fail(RedactedPdfVerificationFailure.UNEXPECTED_CONTENT)
    }
}

private fun isOne(value: COSNumber): Boolean = abs(value.floatValue() - 1f) <= 0.001f

private fun sameBox(left: PDRectangle, right: PDRectangle): Boolean =
    abs(left.lowerLeftX - right.lowerLeftX) <= 0.25f &&
        abs(left.lowerLeftY - right.lowerLeftY) <= 0.25f &&
        abs(left.upperRightX - right.upperRightX) <= 0.25f &&
        abs(left.upperRightY - right.upperRightY) <= 0.25f

private fun COSBase.dereference(): COSBase = if (this is COSObject) getObject() else this

private fun <T : Any> identitySet(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

private fun String.normalizedPdfText(): String = trim().replace(Regex("\\s+"), " ")

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private class RedactedPdfVerificationException(
    val reason: RedactedPdfVerificationFailure,
) : RuntimeException()

private fun fail(reason: RedactedPdfVerificationFailure): Nothing =
    throw RedactedPdfVerificationException(reason)
