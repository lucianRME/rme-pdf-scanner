package org.synapseworks.pageharbor.document.cardcopy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.redaction.RedactionBounds
import org.synapseworks.pageharbor.document.redaction.RedactionCoordinateSpace
import org.synapseworks.pageharbor.document.redaction.RedactionRegion
import org.synapseworks.pageharbor.document.redaction.RedactionRegionId
import org.synapseworks.pageharbor.document.redaction.RedactionTarget
import org.synapseworks.pageharbor.document.redaction.RedactionTargetKind
import org.synapseworks.pageharbor.document.targetsize.LocalTargetSizePdfEngine
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes
import org.synapseworks.pageharbor.document.targetsize.TargetSizeExistingPdfCopyRequest
import org.synapseworks.pageharbor.document.targetsize.TargetSizeInputClass
import org.synapseworks.pageharbor.document.targetsize.TargetSizePageContentProfile
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfInputInspector
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfPage
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfRequest
import org.synapseworks.pageharbor.document.targetsize.TargetSizePdfResult

class CardCopyPdfComposerInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        testRoot = File(context.cacheDir, "card-copy-test-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
        listOf(
            CARD_COPY_SOURCE_DIRECTORY,
            CARD_COPY_WORK_DIRECTORY,
            CARD_COPY_OUTPUT_DIRECTORY,
        ).forEach { root ->
            File(context.cacheDir, root).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun syntheticFixtureSetIsNonSensitiveAndDecodable() {
        val fixtures = listOf(
            fixture("landscape", 1_012, 638, Fixture.LANDSCAPE),
            fixture("portrait", 638, 1_012, Fixture.PORTRAIT),
            fixture("different-crop", 1_200, 520, Fixture.EDGE_TO_EDGE),
            fixture("high-resolution-text", 3_000, 1_900, Fixture.HIGH_RES_TEXT),
            fixture("low-resolution", 180, 110, Fixture.LOW_RES),
            fixture("rotated-front", 638, 1_012, Fixture.ROTATED_FRONT),
            fixture("rotated-back", 638, 1_012, Fixture.ROTATED_BACK),
            fixture("unicode", 1_012, 638, Fixture.UNICODE),
            fixture("photo-heavy", 1_012, 638, Fixture.PHOTO),
            fixture("edge-to-edge", 1_012, 638, Fixture.EDGE_TO_EDGE),
        )

        assertEquals(10, fixtures.size)
        fixtures.forEach { file ->
            assertTrue(file.isFile && file.length() > 0L)
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, options)
            assertTrue("${file.name}: ${options.outWidth}x${options.outHeight}", options.outWidth > 0)
            assertTrue(options.outHeight > 0)
        }
    }

    @Test
    fun fileProviderRejectsCardSourcesAndUnverifiedWorkspaces() {
        val source = File(
            File(context.cacheDir, CARD_COPY_SOURCE_DIRECTORY).apply { mkdirs() },
            "synthetic-source.jpg",
        ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val work = File(
            File(context.cacheDir, CARD_COPY_WORK_DIRECTORY).apply { mkdirs() },
            "unverified-work.pdf",
        ).apply { writeBytes(byteArrayOf(4, 5, 6)) }

        assertTrue(
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    source,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    work,
                )
            }.isFailure,
        )
    }

    @Test
    fun sourceChangeAfterStagingFailsClosedBeforeComposition() = runBlocking {
        val frontFile = fixture("changed-front", 1_012, 638, Fixture.FRONT_RED)
        val backFile = fixture("unchanged-back", 1_012, 638, Fixture.BACK_BLUE)
        val stagedFront = source(CardCopySide.FRONT, frontFile)
        val originalLength = frontFile.length()
        val changed = frontFile.readBytes().also { bytes ->
            val index = bytes.lastIndex.coerceAtLeast(0)
            bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
        }
        FileOutputStream(frontFile, false).use { it.write(changed) }
        assertEquals(originalLength, frontFile.length())

        val result = CardCopyPdfComposer(context).prepare(
            stagedFront,
            source(CardCopySide.BACK, backFile),
            CardCopySettings(),
        )

        assertEquals(
            CardCopyPrepareResult.Failure(CardCopyFailure.SOURCE_CHANGED),
            result,
        )
    }

    @Test
    fun cancellationDuringSourceStagingRemovesUnredactedWorkspace() = runBlocking {
        val enteredRead = CompletableDeferred<Unit>()
        val releaseRead = CountDownLatch(1)
        val stager = CardCopySourceStager(context) {
            object : InputStream() {
                private var emitted = false

                override fun read(): Int = -1

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (emitted) return -1
                    emitted = true
                    val count = minOf(length, 1_024)
                    buffer.fill(0x42, offset, offset + count)
                    enteredRead.complete(Unit)
                    check(releaseRead.await(5, TimeUnit.SECONDS))
                    return count
                }
            }
        }
        val job = launch {
            stager.stage(
                uri = Uri.EMPTY,
                side = CardCopySide.FRONT,
                acquisition = CardCopyAcquisition.IMPORT,
            )
        }

        enteredRead.await()
        job.cancel()
        releaseRead.countDown()
        job.join()

        assertTrue(job.isCancelled)
        val sourceRoot = File(context.cacheDir, CARD_COPY_SOURCE_DIRECTORY)
        assertTrue(sourceRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationAtPreparedResultHandoffDeletesVerifiedArtifact() = runBlocking {
        val frontFile = fixture("handoff-front", 1_012, 638, Fixture.FRONT_RED)
        val backFile = fixture("handoff-back", 1_012, 638, Fixture.BACK_BLUE)
        val workRoot = File(context.cacheDir, CARD_COPY_WORK_DIRECTORY)
        val outputRoot = File(context.cacheDir, CARD_COPY_OUTPUT_DIRECTORY)
        listOf(workRoot, outputRoot).forEach { root ->
            root.listFiles()?.forEach(File::deleteRecursively)
        }
        val handoffReady = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executor.execute {
                    block.run()
                    if (workRoot.listFiles().orEmpty().isEmpty() &&
                        outputRoot.listFiles().orEmpty().isNotEmpty()
                    ) {
                        handoffReady.countDown()
                    }
                }
            }
        }
        try {
            val composer = CardCopyPdfComposer(context, ioDispatcher = dispatcher)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                composer.prepare(
                    source(CardCopySide.FRONT, frontFile),
                    source(CardCopySide.BACK, backFile),
                    CardCopySettings(),
                )
            }

            assertTrue(handoffReady.await(30, TimeUnit.SECONDS))
            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(workRoot.listFiles().orEmpty().isEmpty())
            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun generatedMatrixIsOnePageExactVisibleAndLeavesSourcesUnchanged() = runBlocking {
        val frontFile = fixture("front-red", 1_012, 638, Fixture.FRONT_RED)
        val backFile = fixture("back-blue", 1_012, 638, Fixture.BACK_BLUE)
        val frontHash = sha256(frontFile)
        val backHash = sha256(backFile)
        val composer = CardCopyPdfComposer(context)

        CardCopyPageSize.entries.forEach { pageSize ->
            CardCopyLayout.entries.forEach { layout ->
                val result = composer.prepare(
                    source(CardCopySide.FRONT, frontFile),
                    source(CardCopySide.BACK, backFile),
                    CardCopySettings(
                        pageSize = pageSize,
                        layout = layout,
                        alignment = CardCopyAlignment.END,
                        spacing = CardCopySpacing.WIDE,
                    ),
                )
                assertTrue("$pageSize $layout result=$result", result is CardCopyPrepareResult.Success)
                val prepared = (result as CardCopyPrepareResult.Success).prepared
                try {
                    assertTrue(prepared.file.isFile && prepared.bytes == prepared.file.length())
                    assertFalse(prepared.geometry.isClipped())
                    assertTrue(prepared.geometry.aspectRatiosPreserved())
                    PDDocument.load(prepared.file).use { document ->
                        assertEquals(1, document.numberOfPages)
                        assertEquals(pageSize.widthPoints, document.getPage(0).mediaBox.width, 0.1f)
                        assertEquals(pageSize.heightPoints, document.getPage(0).mediaBox.height, 0.1f)
                        assertEquals(2, document.getPage(0).resources.xObjectNames.count())
                    }
                    assertRenderedColors(prepared.file, prepared.geometry)
                } finally {
                    composer.discard(prepared)
                }
            }
        }
        assertTrue(frontHash.contentEquals(sha256(frontFile)))
        assertTrue(backHash.contentEquals(sha256(backFile)))
    }

    @Test
    fun redactionRemainsAttachedAcrossPageLayoutRotationSpacingAndAlignmentMatrix() = runBlocking {
        val frontFile = fixture("matrix-secret-front", 1_012, 638, Fixture.SECRET_PHOTO)
        val backFile = fixture("matrix-back", 1_012, 638, Fixture.BACK_BLUE)
        val protected = RedactionBounds(0.22f, 0.25f, 0.78f, 0.55f)
        val composer = CardCopyPdfComposer(context)

        CardCopyPageSize.entries.forEach { pageSize ->
            CardCopyLayout.entries.forEach { layout ->
                DocumentPageRotation.entries.forEachIndexed { index, rotation ->
                    val plainFront = source(CardCopySide.FRONT, frontFile, rotation)
                    val front = plainFront.copy(
                        redactions = listOf(
                            RedactionRegion(
                                id = RedactionRegionId("matrix-$pageSize-$layout-$rotation"),
                                coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
                                bounds = protected,
                                target = RedactionTarget(
                                    RedactionTargetKind.CARD_SOURCE,
                                    plainFront.sourceId,
                                ),
                                confirmed = true,
                                order = 0,
                            ),
                        ),
                    )
                    val result = composer.prepare(
                        front,
                        source(
                            CardCopySide.BACK,
                            backFile,
                            DocumentPageRotation.entries.reversed()[index],
                        ),
                        CardCopySettings(
                            pageSize = pageSize,
                            layout = layout,
                            alignment = CardCopyAlignment.entries[index % CardCopyAlignment.entries.size],
                            spacing = CardCopySpacing.entries[index % CardCopySpacing.entries.size],
                        ),
                    )
                    assertTrue(
                        "$pageSize $layout $rotation result=$result",
                        result is CardCopyPrepareResult.Success,
                    )
                    val prepared = (result as CardCopyPrepareResult.Success).prepared
                    try {
                        val mapped = prepared.geometry.front.mapSourceRegion(protected)
                        assertOpaqueBlack(
                            prepared.file,
                            RedactionBounds(
                                mapped.left / prepared.geometry.pageSize.widthPoints,
                                mapped.top / prepared.geometry.pageSize.heightPoints,
                                mapped.right / prepared.geometry.pageSize.widthPoints,
                                mapped.bottom / prepared.geometry.pageSize.heightPoints,
                            ),
                            scale = 1,
                        )
                    } finally {
                        composer.discard(prepared)
                    }
                }
            }
        }
    }

    @Test
    fun rotationsDestinationVerificationAndShareUseExactPreparedPdf() = runBlocking {
        val frontFile = fixture("rotated-source-front", 638, 1_012, Fixture.SECRET_PHOTO)
        val backFile = fixture("rotated-source-back", 638, 1_012, Fixture.BACK_BLUE)
        val protectedSourceRegion = RedactionBounds(0.22f, 0.25f, 0.78f, 0.55f)
        val plainFront = source(
            CardCopySide.FRONT,
            frontFile,
            DocumentPageRotation.DEGREES_90,
        )
        val front = plainFront.copy(
            redactions = listOf(
                RedactionRegion(
                    id = RedactionRegionId("destination-redaction"),
                    coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
                    bounds = protectedSourceRegion,
                    target = RedactionTarget(
                        RedactionTargetKind.CARD_SOURCE,
                        plainFront.sourceId,
                    ),
                    confirmed = true,
                    order = 0,
                ),
            ),
        )
        val composer = CardCopyPdfComposer(context)
        val result = composer.prepare(
            front,
            source(CardCopySide.BACK, backFile, DocumentPageRotation.DEGREES_270),
            CardCopySettings(layout = CardCopyLayout.HORIZONTAL),
        ) as CardCopyPrepareResult.Success
        val prepared = result.prepared
        try {
            val destination = File(
                File(context.cacheDir, CARD_COPY_OUTPUT_DIRECTORY).apply { mkdirs() },
                "destination-${System.nanoTime()}.pdf",
            ).apply { createNewFile() }
            val destinationUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destination,
            )

            assertEquals(
                CardCopyDestinationResult.Success(prepared.bytes),
                CardCopyDestinationWriter(context).write(prepared, destinationUri),
            )
            assertTrue(prepared.file.readBytes().contentEquals(destination.readBytes()))
            assertOnePageRenders(destination)
            val mapped = prepared.geometry.front.mapSourceRegion(protectedSourceRegion)
            val finalRegion = RedactionBounds(
                mapped.left / prepared.geometry.pageSize.widthPoints,
                mapped.top / prepared.geometry.pageSize.heightPoints,
                mapped.right / prepared.geometry.pageSize.widthPoints,
                mapped.bottom / prepared.geometry.pageSize.heightPoints,
            )
            assertOpaqueBlack(destination, finalRegion, scale = 1)
            assertOpaqueBlack(destination, finalRegion, scale = 2)
            val shareUri = cardCopyShareUri(context, prepared)
            assertNotNull(shareUri)
            assertEquals("content", shareUri?.scheme)
            val sharedBytes = requireNotNull(context.contentResolver.openInputStream(shareUri!!))
                .use(InputStream::readBytes)
            assertTrue(prepared.file.readBytes().contentEquals(sharedBytes))
        } finally {
            composer.discard(prepared)
        }
    }

    @Test
    fun m6OutputIsAcceptedByM4AtOneMegabyteWithoutChangingOriginal() = runBlocking {
        val composer = CardCopyPdfComposer(context)
        val prepared = (composer.prepare(
            source(CardCopySide.FRONT, fixture("m4-front", 1_012, 638, Fixture.FRONT_RED)),
            source(CardCopySide.BACK, fixture("m4-back", 1_012, 638, Fixture.BACK_BLUE)),
            CardCopySettings(),
        ) as CardCopyPrepareResult.Success).prepared
        val originalHash = sha256(prepared.file)
        val rendered = renderToJpeg(prepared.file, "m4-rendered")
        val opener: (Uri) -> InputStream? = { uri -> uri.path?.let { File(it).inputStream() } }
        val engine = LocalTargetSizePdfEngine(
            context = context,
            pdfInspector = TargetSizePdfInputInspector(context, opener),
            openPageInputStream = opener,
            availableTemporaryBytes = { Long.MAX_VALUE },
        )
        val page = TargetSizePdfPage(
            pageId = 1L,
            sourceUri = Uri.fromFile(rendered),
            sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE,
            contentType = "image/jpeg",
            imageMetadata = DocumentImageMetadata(rendered.length(), 595, 842),
            contentProfile = TargetSizePageContentProfile.MIXED_TEXT_PHOTO,
        )
        val result = engine.prepareExistingPdfCopy(
            TargetSizeExistingPdfCopyRequest(
                sourcePdfUri = Uri.fromFile(prepared.file),
                documentRequest = TargetSizePdfRequest(
                    pages = listOf(page),
                    originalPdfUris = listOf(Uri.fromFile(prepared.file)),
                    targetBytes = TargetSizeBytes.MB_1,
                ),
            ),
        )

        assertTrue("result=$result", result is TargetSizePdfResult.TargetReached)
        val m4Prepared = (result as TargetSizePdfResult.TargetReached).prepared
        try {
            assertTrue(
                m4Prepared.plan.inputClass == TargetSizeInputClass.IMAGE_BASED_PDF_SUPPORTED ||
                    m4Prepared.plan.inputClass == TargetSizeInputClass.MIXED_PDF_SUPPORTED_WITH_RECONSTRUCTION,
            )
            assertTrue(m4Prepared.finalBytes <= TargetSizeBytes.MB_1)
            assertOnePageRenders(m4Prepared.file)
            assertRenderedColors(m4Prepared.file, prepared.geometry)
            assertTrue(originalHash.contentEquals(sha256(prepared.file)))
        } finally {
            engine.discard(m4Prepared)
            composer.discard(prepared)
        }
    }

    @Test
    fun redactedPixelsRemainAbsentAfterForcedM4Reconstruction() = runBlocking {
        val frontFile = fixture("m7-m4-front", 3_000, 1_900, Fixture.SECRET_PHOTO)
        val backFile = fixture("m7-m4-back", 3_000, 1_900, Fixture.PHOTO)
        val frontHash = sha256(frontFile)
        val backHash = sha256(backFile)
        val plainFront = source(CardCopySide.FRONT, frontFile)
        val protectedSourceRegion = RedactionBounds(0.22f, 0.25f, 0.78f, 0.55f)
        assertSourceRegionContainsVisibleData(frontFile, protectedSourceRegion)
        val front = plainFront.copy(
            redactions = listOf(
                RedactionRegion(
                    id = RedactionRegionId("m7-m4-redaction"),
                    coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
                    bounds = protectedSourceRegion,
                    target = RedactionTarget(
                        RedactionTargetKind.CARD_SOURCE,
                        plainFront.sourceId,
                    ),
                    confirmed = true,
                    order = 0,
                ),
            ),
        )
        val composer = CardCopyPdfComposer(context)
        val composed = composer.prepare(
            front,
            source(CardCopySide.BACK, backFile),
            CardCopySettings(layout = CardCopyLayout.VERTICAL),
        )
        assertTrue("compose result=$composed", composed is CardCopyPrepareResult.Success)
        val prepared = (composed as CardCopyPrepareResult.Success).prepared
        val mapped = prepared.geometry.front.mapSourceRegion(protectedSourceRegion)
        val finalRegion = RedactionBounds(
            mapped.left / prepared.geometry.pageSize.widthPoints,
            mapped.top / prepared.geometry.pageSize.heightPoints,
            mapped.right / prepared.geometry.pageSize.widthPoints,
            mapped.bottom / prepared.geometry.pageSize.heightPoints,
        )
        try {
            assertTrue(prepared.securelyVerified)
            assertEquals(1, prepared.redactionCount)
            assertOpaqueBlack(prepared.file, finalRegion, scale = 1)
            assertOpaqueBlack(prepared.file, finalRegion, scale = 2)
            PDDocument.load(prepared.file).use { document ->
                assertFalse(PDFTextStripper().getText(document).contains(M7_SENTINEL))
                assertEquals(2, document.getPage(0).resources.xObjectNames.count())
            }

            val rendered = renderToJpeg(prepared.file, "m7-m4-sanitized-raster")
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(rendered.path, options)
            val opener: (Uri) -> InputStream? = { uri ->
                uri.path?.let { path -> File(path).inputStream() }
            }
            val engine = LocalTargetSizePdfEngine(
                context = context,
                pdfInspector = TargetSizePdfInputInspector(context, opener),
                openPageInputStream = opener,
                availableTemporaryBytes = { Long.MAX_VALUE },
            )
            val target = (prepared.bytes * 9L / 10L).coerceAtLeast(300_000L)
            assertTrue("prepared=${prepared.bytes} target=$target", target < prepared.bytes)
            val result = engine.prepare(
                TargetSizePdfRequest(
                    pages = listOf(
                        TargetSizePdfPage(
                            pageId = 7L,
                            sourceUri = Uri.fromFile(rendered),
                            sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE,
                            contentType = "image/jpeg",
                            imageMetadata = DocumentImageMetadata(
                                rendered.length(),
                                options.outWidth,
                                options.outHeight,
                            ),
                            effectiveOcr = null,
                            contentProfile = TargetSizePageContentProfile.PHOTO_HEAVY,
                        ),
                    ),
                    originalPdfUris = listOf(Uri.fromFile(prepared.file)),
                    targetBytes = target,
                ),
            )
            assertTrue("M4 result=$result", result is TargetSizePdfResult.TargetReached)
            val recompressed = (result as TargetSizePdfResult.TargetReached).prepared
            try {
                assertTrue(recompressed.performance.fullDocumentPassCount >= 1)
                assertTrue(recompressed.finalBytes <= target)
                assertFalse(sha256(prepared.file).contentEquals(sha256(recompressed.file)))
                assertOpaqueBlack(recompressed.file, finalRegion, scale = 1)
                assertOpaqueBlack(recompressed.file, finalRegion, scale = 2)
                PDDocument.load(recompressed.file).use { document ->
                    assertEquals(1, document.numberOfPages)
                    assertFalse(PDFTextStripper().getText(document).contains(M7_SENTINEL))
                    assertEquals(1, document.getPage(0).resources.xObjectNames.count())
                }
                assertSingleExtractedImageOpaqueBlack(recompressed.file, finalRegion)
            } finally {
                engine.discard(recompressed)
            }
        } finally {
            composer.discard(prepared)
        }
        assertTrue(frontHash.contentEquals(sha256(frontFile)))
        assertTrue(backHash.contentEquals(sha256(backFile)))
    }

    @Test
    fun swappedFrontAndBackKeepBothPhysicalSecretsRedactedAfterIndependentRotations() = runBlocking {
        val originalFrontFile = fixture("swap-secret-front", 1_012, 638, Fixture.SECRET_PHOTO)
        val originalBackFile = fixture("swap-secret-back", 638, 1_012, Fixture.SECRET_PHOTO)
        val frontHash = sha256(originalFrontFile)
        val backHash = sha256(originalBackFile)
        val protected = RedactionBounds(0.22f, 0.25f, 0.78f, 0.55f)

        fun redacted(source: CardCopySource, id: String) = source.copy(
            redactions = listOf(
                RedactionRegion(
                    id = RedactionRegionId(id),
                    coordinateSpace = RedactionCoordinateSpace.SOURCE_NORMALIZED,
                    bounds = protected,
                    target = RedactionTarget(
                        RedactionTargetKind.CARD_SOURCE,
                        source.sourceId,
                    ),
                    confirmed = true,
                    order = 0,
                ),
            ),
        )

        val physicalFront = redacted(
            source(
                CardCopySide.FRONT,
                originalFrontFile,
                DocumentPageRotation.DEGREES_90,
            ),
            "physical-front-secret",
        )
        val physicalBack = redacted(
            source(
                CardCopySide.BACK,
                originalBackFile,
                DocumentPageRotation.DEGREES_270,
            ),
            "physical-back-secret",
        )
        // This is the exact identity-preserving transformation used by CardCopyWorkflowViewModel.swap().
        val swappedFront = physicalBack.copy(side = CardCopySide.FRONT)
        val swappedBack = physicalFront.copy(side = CardCopySide.BACK)
        val composer = CardCopyPdfComposer(context)
        val result = composer.prepare(
            swappedFront,
            swappedBack,
            CardCopySettings(
                pageSize = CardCopyPageSize.LETTER,
                layout = CardCopyLayout.HORIZONTAL,
                alignment = CardCopyAlignment.END,
                spacing = CardCopySpacing.WIDE,
            ),
        )

        assertTrue("result=$result", result is CardCopyPrepareResult.Success)
        val prepared = (result as CardCopyPrepareResult.Success).prepared
        try {
            val frontMapped = prepared.geometry.front.mapSourceRegion(protected)
            val backMapped = prepared.geometry.back.mapSourceRegion(protected)
            val frontFinal = RedactionBounds(
                frontMapped.left / prepared.geometry.pageSize.widthPoints,
                frontMapped.top / prepared.geometry.pageSize.heightPoints,
                frontMapped.right / prepared.geometry.pageSize.widthPoints,
                frontMapped.bottom / prepared.geometry.pageSize.heightPoints,
            )
            val backFinal = RedactionBounds(
                backMapped.left / prepared.geometry.pageSize.widthPoints,
                backMapped.top / prepared.geometry.pageSize.heightPoints,
                backMapped.right / prepared.geometry.pageSize.widthPoints,
                backMapped.bottom / prepared.geometry.pageSize.heightPoints,
            )
            assertOpaqueBlack(prepared.file, frontFinal, scale = 1)
            assertOpaqueBlack(prepared.file, frontFinal, scale = 2)
            assertOpaqueBlack(prepared.file, backFinal, scale = 1)
            assertOpaqueBlack(prepared.file, backFinal, scale = 2)
            PDDocument.load(prepared.file).use { document ->
                assertEquals(2, document.getPage(0).resources.xObjectNames.count())
                assertFalse(PDFTextStripper().getText(document).contains(M7_SENTINEL))
            }
        } finally {
            composer.discard(prepared)
        }
        assertTrue(frontHash.contentEquals(sha256(originalFrontFile)))
        assertTrue(backHash.contentEquals(sha256(originalBackFile)))
    }

    @Test
    fun highResolutionPreparationReportsBoundedPerformanceAndCleansUp() = runBlocking {
        val composer = CardCopyPdfComposer(context)
        val baselinePss = Debug.getPss().toLong() * 1_024L
        val result = composer.prepare(
            source(CardCopySide.FRONT, fixture("perf-front", 3_000, 1_900, Fixture.HIGH_RES_TEXT)),
            source(CardCopySide.BACK, fixture("perf-back", 3_000, 1_900, Fixture.PHOTO), DocumentPageRotation.DEGREES_180),
            CardCopySettings(layout = CardCopyLayout.VERTICAL),
        ) as CardCopyPrepareResult.Success
        val prepared = result.prepared
        val retainedGrowth = (Debug.getPss().toLong() * 1_024L - baselinePss).coerceAtLeast(0L)
        Log.i(
            "RME_M6_METRICS",
            "prepare_ms=${prepared.preparationMillis} estimated_peak_bitmap_bytes=${prepared.estimatedPeakBitmapBytes} " +
                "temp_peak_bytes=${prepared.peakTemporaryBytes} final_bytes=${prepared.bytes} " +
                "retained_pss_growth_bytes=$retainedGrowth",
        )
        assertTrue(prepared.preparationMillis >= 0L)
        assertTrue(prepared.estimatedPeakBitmapBytes in 1 until 64L * 1_024L * 1_024L)
        assertTrue(prepared.peakTemporaryBytes >= prepared.bytes)
        assertTrue(prepared.file.isFile)
        val directory = prepared.workingDirectory
        composer.discard(prepared)
        assertFalse(directory.exists())
    }

    private fun source(
        side: CardCopySide,
        file: File,
        rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    ): CardCopySource {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, options)
        return CardCopySource(
            side = side,
            acquisition = CardCopyAcquisition.IMPORT,
            file = file,
            ownedDirectory = testRoot,
            metadata = DocumentImageMetadata(file.length(), options.outWidth, options.outHeight),
            rotation = rotation,
        )
    }

    private fun fixture(name: String, width: Int, height: Int, kind: Fixture): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (kind) {
                Fixture.FRONT_RED -> canvas.drawColor(Color.rgb(220, 35, 35))
                Fixture.BACK_BLUE -> canvas.drawColor(Color.rgb(35, 65, 220))
                Fixture.PHOTO, Fixture.SECRET_PHOTO -> {
                    for (y in 0 until height step 12) for (x in 0 until width step 12) {
                        paint.color = Color.rgb((x * 7 + y * 3) and 255, (x + y * 5) and 255, (x * 3 + y) and 255)
                        canvas.drawRect(x.toFloat(), y.toFloat(), (x + 12).toFloat(), (y + 12).toFloat(), paint)
                    }
                    if (kind == Fixture.SECRET_PHOTO) {
                        val protectedLeft = width * 0.22f
                        val protectedTop = height * 0.25f
                        val protectedRight = width * 0.78f
                        val protectedBottom = height * 0.55f
                        paint.style = Paint.Style.FILL
                        paint.color = Color.WHITE
                        canvas.drawRect(
                            protectedLeft,
                            protectedTop,
                            protectedRight,
                            protectedBottom,
                            paint,
                        )
                        paint.color = Color.rgb(180, 0, 40)
                        paint.textSize = width * 0.055f
                        paint.isAntiAlias = true
                        val availableWidth = (protectedRight - protectedLeft) * 0.9f
                        val measuredWidth = paint.measureText(M7_SENTINEL)
                        if (measuredWidth > availableWidth) {
                            paint.textSize *= availableWidth / measuredWidth
                        }
                        val textWidth = paint.measureText(M7_SENTINEL)
                        val metrics = paint.fontMetrics
                        val textHeight = metrics.descent - metrics.ascent
                        val textLeft = protectedLeft + (protectedRight - protectedLeft - textWidth) / 2f
                        val baseline = protectedTop +
                            (protectedBottom - protectedTop - textHeight) / 2f - metrics.ascent
                        assertTrue(textLeft >= protectedLeft)
                        assertTrue(textLeft + textWidth <= protectedRight)
                        assertTrue(baseline + metrics.top >= protectedTop)
                        assertTrue(baseline + metrics.bottom <= protectedBottom)
                        canvas.drawText(M7_SENTINEL, textLeft, baseline, paint)
                    }
                }
                Fixture.EDGE_TO_EDGE -> {
                    canvas.drawColor(Color.BLACK)
                    paint.color = Color.YELLOW
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 12f
                    canvas.drawRect(6f, 6f, width - 6f, height - 6f, paint)
                    paint.style = Paint.Style.FILL
                }
                else -> {
                    canvas.drawColor(if (kind == Fixture.LOW_RES) Color.LTGRAY else Color.WHITE)
                    paint.color = Color.rgb(30, 50, 90)
                    paint.textSize = if (kind == Fixture.HIGH_RES_TEXT) 28f else 42f
                    val text = when (kind) {
                        Fixture.UNICODE -> "Synthetic card - RME - ecole - 123"
                        Fixture.PORTRAIT -> "PORTRAIT CARD"
                        Fixture.ROTATED_FRONT -> "ROTATED FRONT"
                        Fixture.ROTATED_BACK -> "ROTATED BACK"
                        else -> "SYNTHETIC CARD"
                    }
                    repeat(12) { line ->
                        canvas.drawText("$text $line", 24f, 54f + line * paint.textSize * 1.3f, paint)
                    }
                }
            }
            val file = File(testRoot, "$name.jpg")
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
                output.flush()
                output.fd.sync()
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertRenderedColors(file: File, geometry: CardCopyGeometry) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertCardColor(bitmap, geometry.front.bounds, Color.RED)
                        assertCardColor(bitmap, geometry.back.bounds, Color.BLUE)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun assertCardColor(bitmap: Bitmap, bounds: CardCopyRect, expected: Int) {
        val x = ((bounds.left + bounds.right) / 2f).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((bounds.top + bounds.bottom) / 2f).toInt().coerceIn(0, bitmap.height - 1)
        val actual = bitmap.getPixel(x, y)
        if (expected == Color.RED) assertTrue(Color.red(actual) > Color.blue(actual) + 80)
        else assertTrue(Color.blue(actual) > Color.red(actual) + 80)
    }

    private fun assertOnePageRenders(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun assertOpaqueBlack(file: File, region: RedactionBounds, scale: Int) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(
                        page.width * scale,
                        page.height * scale,
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val left = (region.left * bitmap.width).toInt() + 3
                        val top = (region.top * bitmap.height).toInt() + 3
                        val right = (region.right * bitmap.width).toInt() - 3
                        val bottom = (region.bottom * bitmap.height).toInt() - 3
                        assertTrue(right > left && bottom > top)
                        val xs = listOf(left, (left + right) / 2, right - 1)
                        val ys = listOf(top, (top + bottom) / 2, bottom - 1)
                        xs.forEach { x ->
                            ys.forEach { y ->
                                val pixel = bitmap.getPixel(x, y)
                                assertEquals(255, Color.alpha(pixel))
                                assertTrue(Color.red(pixel) <= 20)
                                assertTrue(Color.green(pixel) <= 20)
                                assertTrue(Color.blue(pixel) <= 20)
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun assertSourceRegionContainsVisibleData(file: File, region: RedactionBounds) {
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
        try {
            val left = (region.left * bitmap.width).toInt()
            val top = (region.top * bitmap.height).toInt()
            val right = (region.right * bitmap.width).toInt()
            val bottom = (region.bottom * bitmap.height).toInt()
            var nonBlackPixels = 0
            for (y in top until bottom step 8) for (x in left until right step 8) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 32 || Color.green(pixel) > 32 || Color.blue(pixel) > 32) {
                    nonBlackPixels++
                }
            }
            assertTrue(nonBlackPixels > 100)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertSingleExtractedImageOpaqueBlack(
        file: File,
        region: RedactionBounds,
    ) {
        PDDocument.load(file).use { document ->
            val image = document.getPage(0).resources.xObjectNames
                .map(document.getPage(0).resources::getXObject)
                .filterIsInstance<com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject>()
                .single().image
            try {
                val left = kotlin.math.floor(region.left * image.width).toInt()
                    .coerceIn(0, image.width - 1)
                val top = kotlin.math.floor(region.top * image.height).toInt()
                    .coerceIn(0, image.height - 1)
                val right = kotlin.math.ceil(region.right * image.width).toInt()
                    .coerceIn(left + 1, image.width)
                val bottom = kotlin.math.ceil(region.bottom * image.height).toInt()
                    .coerceIn(top + 1, image.height)
                val row = IntArray(right - left)
                for (y in top until bottom) {
                    image.getPixels(row, 0, row.size, left, y, row.size, 1)
                    assertTrue(row.all { pixel ->
                        Color.alpha(pixel) == 255 && Color.red(pixel) <= 24 &&
                            Color.green(pixel) <= 24 && Color.blue(pixel) <= 24
                    })
                }
            } finally {
                image.recycle()
            }
        }
    }

    private fun renderToJpeg(pdf: File, name: String): File {
        val output = File(testRoot, "$name.jpg")
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(output).use { stream ->
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        return output
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())

    private companion object {
        const val M7_SENTINEL = "RME_SECRET_7F42A9"
    }

    private enum class Fixture {
        LANDSCAPE,
        PORTRAIT,
        HIGH_RES_TEXT,
        LOW_RES,
        ROTATED_FRONT,
        ROTATED_BACK,
        UNICODE,
        PHOTO,
        SECRET_PHOTO,
        EDGE_TO_EDGE,
        FRONT_RED,
        BACK_BLUE,
    }
}
