package org.synapseworks.pageharbor.library.duplicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.synapseworks.pageharbor.backup.format.BackupOcrArtifactRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionAlignment
import org.synapseworks.pageharbor.backup.format.BackupOcrCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrInputFingerprint
import org.synapseworks.pageharbor.backup.format.BackupOcrLineCorrectionRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrLineRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPageStateRecord
import org.synapseworks.pageharbor.backup.format.BackupOcrPoint
import org.synapseworks.pageharbor.backup.format.BackupOcrVerificationState

class OcrStateDigestTest {
    @Test
    fun canonicalDigestIsStableAcrossRestoredIdsTimestampsAndRevisionNumbers() {
        val original = fixture(pageId = "source-page", artifactRevision = 7L, timestamp = 1_000L)
        val restored = fixture(pageId = "restored-page", artifactRevision = 41L, timestamp = 9_000L)

        val first = OcrStateDigestV1.calculate("LATIN", listOf(original))
        val second = OcrStateDigestV1.calculate("LATIN", listOf(restored))

        assertEquals(OcrStateDigestV1.VERSION, first.version)
        assertEquals(first, second)
        assertEquals(64, first.sha256.length)
    }

    @Test
    fun opaqueFingerprintValueAndRevisionCountersAreNotSemanticIdentity() {
        val original = fixture()
        val changed = original.copy(
            pageState = original.pageState?.copy(
                visualRevision = 8_000,
                ocrStateRevision = 9_000,
            ),
            artifacts = original.artifacts.map { artifact ->
                artifact.copy(
                    capturedPageVisualRevision = 10_000,
                    capturedDocumentContentRevision = 11_000,
                    inputFingerprint = requireNotNull(artifact.inputFingerprint).copy(
                        value = "opaque-revision-bearing-value-changed",
                        visualRevision = 12_000,
                    ),
                    recognizedAtEpochMillis = 13_000,
                )
            },
            correction = original.correction?.copy(correctedAtEpochMillis = 14_000),
        )

        assertEquals(
            OcrStateDigestV1.calculate("LATIN", listOf(original)),
            OcrStateDigestV1.calculate("LATIN", listOf(changed)),
        )
    }

    @Test
    fun digestPinsTheCanonicalEncoding() {
        val digest = OcrStateDigestV1.calculate("LATIN", listOf(fixture()))

        assertEquals(
            "3d741b1137b6406fdb4468f36259bc62c183528ea44989e54d88b3d95eafc852",
            digest.sha256,
        )
    }

    @Test
    fun preservationSensitiveChangesProduceDifferentDigests() {
        val base = fixture()
        val digest = OcrStateDigestV1.calculate("LATIN", listOf(base))

        val variants = listOf(
            OcrStateDigestV1.calculate("JAPANESE", listOf(base)),
            OcrStateDigestV1.calculate("LATIN", listOf(base.copy(legacyText = "changed"))),
            OcrStateDigestV1.calculate("LATIN", listOf(base.copy(legacyError = "failed"))),
            OcrStateDigestV1.calculate(
                "LATIN",
                listOf(base.copy(correction = base.correction?.copy(correctedText = ""))),
            ),
            OcrStateDigestV1.calculate(
                "LATIN",
                listOf(base.copy(correction = null)),
            ),
            OcrStateDigestV1.calculate(
                "LATIN",
                listOf(
                    base.copy(
                        lines = base.lines.map { line ->
                            line.copy(cornerPoints = line.cornerPoints.toMutableList().also {
                                it[0] = BackupOcrPoint(0.11, 0.20)
                            })
                        },
                    ),
                ),
            ),
            OcrStateDigestV1.calculate(
                "LATIN",
                listOf(base.copy(artifacts = base.artifacts.map { it.copy(actualScript = "KOREAN") })),
            ),
        )

        variants.forEach { variant -> assertNotEquals(digest, variant) }
    }

    @Test
    fun nullAndDeliberatelyEmptyCorrectionRemainDistinct() {
        val page = fixture()
        val absent = page.copy(correction = null)
        val empty = page.copy(
            correction = page.correction?.copy(
                correctedText = "",
                lineCorrections = listOf(BackupOcrLineCorrectionRecord(0, "")),
            ),
        )

        assertNotEquals(
            OcrStateDigestV1.calculate(null, listOf(absent)),
            OcrStateDigestV1.calculate(null, listOf(empty)),
        )
    }

    @Test
    fun streamingPageApiMatchesConvenienceApiWithoutReplayingRecords() {
        val page = fixture()
        val expected = OcrStateDigestV1.calculate("LATIN", listOf(page))
        val builder = OcrStateDigestV1.Builder("LATIN", expectedPageCount = 1)
        val pageBuilder = builder.beginPage(
            legacyText = page.legacyText,
            legacyError = page.legacyError,
            pageState = page.pageState,
            correction = page.correction,
            artifactCount = 1,
        )
        var lineIterationCount = 0
        pageBuilder.addArtifact(
            page.artifacts.single(),
            sequence {
                lineIterationCount += 1
                yield(page.lines.single())
            },
        )
        pageBuilder.finish()

        assertEquals(expected, builder.finish())
        assertEquals(1, lineIterationCount)
    }

    private fun fixture(
        pageId: String = "page-a",
        artifactRevision: Long = 7L,
        timestamp: Long = 1_000L,
    ): OcrDigestPage {
        val artifact = BackupOcrArtifactRecord(
            pageId = pageId,
            artifactRevision = artifactRevision,
            capturedPageVisualRevision = artifactRevision + 10,
            capturedDocumentContentRevision = artifactRevision + 20,
            verificationState = BackupOcrVerificationState.CURRENT_VERIFIED,
            inputFingerprint = BackupOcrInputFingerprint(
                version = 1,
                value = "sha256:input",
                contentSha256 = "a".repeat(64),
                visualRevision = artifactRevision + 30,
                rotationDegrees = 90,
                filterName = "GRAYSCALE",
                uprightWidth = 1_200,
                uprightHeight = 1_600,
                coordinateSystemVersion = 1,
                transformVersion = 2,
            ),
            actualScript = "LATIN",
            recognizerId = "ML_KIT_LATIN_BUNDLED",
            pipelineVersion = "pipeline-1",
            clientVersion = "16.0.1",
            delivery = "BUNDLED",
            recognizedAtEpochMillis = timestamp,
            rawText = "Bună ziua",
            lineCount = 1,
        )
        val line = BackupOcrLineRecord(
            pageId = pageId,
            artifactRevision = artifactRevision,
            lineOrdinal = 0,
            rawText = "Bună ziua",
            cornerPoints = listOf(
                BackupOcrPoint(0.10, 0.20),
                BackupOcrPoint(0.90, 0.20),
                BackupOcrPoint(0.90, 0.30),
                BackupOcrPoint(0.10, 0.30),
            ),
            baselineStart = BackupOcrPoint(0.10, 0.28),
            baselineEnd = BackupOcrPoint(0.90, 0.28),
            baselineAngleDegrees = 0.0,
            writingOrientation = "HORIZONTAL",
        )
        return OcrDigestPage(
            legacyText = "Bună ziua",
            legacyError = null,
            pageState = BackupOcrPageStateRecord(
                pageId = pageId,
                visualRevision = artifactRevision + 30,
                ocrStateRevision = artifactRevision + 40,
                activeArtifactRevision = artifactRevision,
            ),
            artifacts = listOf(artifact),
            correction = BackupOcrCorrectionRecord(
                pageId = pageId,
                baseArtifactRevision = artifactRevision,
                correctedText = "Bună seara",
                correctedAtEpochMillis = timestamp + 50,
                alignment = BackupOcrCorrectionAlignment.LINE_ALIGNED,
                lineCorrections = listOf(BackupOcrLineCorrectionRecord(0, "Bună seara")),
            ),
            lines = listOf(line),
        )
    }
}
