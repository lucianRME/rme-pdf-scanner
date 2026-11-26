package org.synapseworks.pageharbor.businesscard

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.ocr.*

/** Fixed independent image bytes; no diagnostic files, logging or production inputs. */
class CompactCardEmailInstrumentedTest {
    private fun fixture(): Pair<ByteArray, String> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".phase2test"))
        val assets = instrumentation.context.assets
        val bytes = assets.open("m20/compact-original.png").use { it.readBytes() }
        val expected = assets.open("m20/compact-email.txt").bufferedReader().use { it.readText() }
        val manifest = JSONObject(assets.open("m20/compact-fixture.json").bufferedReader().use { it.readText() })
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("3a0421516fa1c63747f4be99eb3c8ce1ea0b135f91af46eec414bf9e9f04d606", digest)
        assertEquals(manifest.getString("pngSha256"), digest)
        assertEquals("jane@example.invalid", expected)
        return bytes to expected
    }
    @Test fun frozenCardSuggestsExactEmailBeforeAnyManualEdit() {
        val (bytes, expected) = fixture()
        assertEquals(listOf(expected), CardRecognition.recognize({ ByteArrayInputStream(bytes) })!!.draft.emails)
    }
    @Test fun recoveryRetainsTheOriginalOcrTextAndUsesOnlyRecognizedCharacters() {
        val (bytes, expected) = fixture()
        var raw: OcrPageResult? = null
        val delegate = MlKitOcrEngine()
        val engine = object : OcrEngine {
            override fun recognize(pages: List<OcrPage>): OcrResult = delegate.recognize(pages).also { raw = it.pages.single() }
        }
        val result = CardRecognition.recognize({ ByteArrayInputStream(bytes) }, engine = engine)!!
        assertEquals(raw!!.text, result.source)
        assertEquals(listOf(expected), result.draft.emails)
        assertTrue(raw!!.layout!!.lines.any { line -> line.elements.joinToString("") { it.text }.contains(expected) })
    }
}
