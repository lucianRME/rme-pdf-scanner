package org.synapseworks.pageharbor.businesscard

import android.app.Application
import android.graphics.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Independent literal expected values; renderer never calls the parser or OCR. Synthetic only. */
internal object CardFixture {
    val lines = listOf("Name: Jane Example", "Company: Harbor Studio", "Title: Design Lead", "Phone: +1 202 555 0100", "Email: jane@example.invalid", "Address: 12 Sample Road", "Dublin D02 AB12")
    fun bytes(rotation: Int = 0, ambiguous: Boolean = false, email: String = "jane@example.invalid"): ByteArray {
        val base = Bitmap.createBitmap(1600, 1100, Bitmap.Config.ARGB_8888)
        Canvas(base).apply { drawColor(Color.WHITE); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            val values = lines.map { if(it.startsWith("Email:")) "Email: $email" else it }
            val renderedLines = if(ambiguous) values.map { it.removePrefix("Name: ").removePrefix("Company: ").removePrefix("Title: ") } else values
            renderedLines.forEachIndexed { index, line -> drawText(line, 70f, 100f + index * 125f, paint) } }
        val bitmap = if(rotation == 0) base else Bitmap.createBitmap(base, 0, 0, base.width, base.height, Matrix().apply { postRotate(-rotation.toFloat()) }, true)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { if(bitmap !== base) bitmap.recycle(); base.recycle() }
    }
}

class CardRecognitionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private fun assertFields(result: ContactSuggestions?) {
        assertNotNull(result); val draft = result!!.draft
        assertEquals("Jane Example", draft.name); assertEquals("Harbor Studio", draft.organization); assertEquals("Design Lead", draft.title)
        assertEquals(listOf("+1 202 555 0100"), draft.phones); assertEquals(listOf("jane@example.invalid"), draft.emails)
        assertEquals(listOf("12 Sample Road\nDublin D02 AB12"), draft.addresses)
    }
    @Test fun freshOfflineBundledCardOcrMatchesIndependentExpectedFieldsAndClosesInput() {
        check(app.packageName.endsWith(".phase2test"))
        for(permission in listOf(Manifest.permission.INTERNET, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
            assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(permission))
        var closed = 0; val bytes = CardFixture.bytes()
        assertFields(CardRecognition.recognize({ object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } } }))
        assertEquals(1, closed)
        val assets = instrumentation.context.assets
        val compactBytes = assets.open("m20/compact-original.png").use { it.readBytes() }
        val expectedEmail = assets.open("m20/compact-email.txt").bufferedReader().use { it.readText() }
        val compactResult = CardRecognition.recognize({ ByteArrayInputStream(compactBytes) })!!
        assertEquals(listOf(expectedEmail), compactResult.draft.emails)

    }
    @Test fun cardOcrRightAngleMetadataMatchesEveryExpectedField() {
        for(rotation in listOf(90, 180, 270)) {
            val bytes = CardFixture.bytes(rotation)
            assertFields(CardRecognition.recognize({ ByteArrayInputStream(bytes) }, rotation))
        }
    }
    @Test fun importedExifRotationAndMirroringUseUprightContactText() {
        val baseBytes = CardFixture.bytes()
        val base = BitmapFactory.decodeByteArray(baseBytes, 0, baseBytes.size)
        try {
            for(orientation in 2..8) {
                // Independent inverse EXIF matrix, not the production transform helper.
                val matrix = Matrix().apply { when(orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                    6 -> setRotate(-90f)
                    7 -> setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                    8 -> setRotate(90f)
                } }
                val raw = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
                val file = File(app.cacheDir, "m20-exif-${java.util.UUID.randomUUID()}.jpg")
                try {
                    file.outputStream().use { check(raw.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
                    androidx.exifinterface.media.ExifInterface(file.absolutePath).apply { setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                    assertFields(CardRecognition.recognize({ file.inputStream() }))
                } finally { raw.recycle(); file.delete() }
            }
        } finally { base.recycle() }
    }

    @Test fun malformedOverLimitAndCanceledInputClosesWithoutOcrOrPersistence() {
        var closed = 0
        fun tracked(bytes: ByteArray) = object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
        assertNull(CardRecognition.recognize({ tracked(byteArrayOf(1, 2, 3)) }))
        val oversized = Bitmap.createBitmap(20000, 1, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream(); oversized.compress(Bitmap.CompressFormat.PNG, 100, output); oversized.recycle()
        assertNull(CardRecognition.recognize({ tracked(output.toByteArray()) }))
        var remaining = CardRecognition.MAX_BYTES + 1
        assertNull(CardRecognition.recognize({ object : InputStream() {
            override fun read() = if(remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { if(remaining <= 0) return -1; val n = minOf(len, remaining); b.fill(0, off, off+n); remaining -= n; return n }
            override fun close() { closed++ }
        } }))
        var checks = 0
        try { CardRecognition.recognize({ tracked(CardFixture.bytes()) }, check = { if(++checks > 2) throw CancellationException("Synthetic cancellation") }); fail() }
        catch(_: CancellationException) { }
        assertEquals(4, closed)
    }
    @Test fun reviewedIntentContainsEveryExactValueWithoutPhotoOcrAccountOrExecutableActions() {
        val draft = ContactDraft("Zoë O'Neill", "Étude & Sons", "R&D Lead", listOf("+44 (0)20 7946 0100 ext. 12", "+1 202-555-0100"),
            listOf("Zoe@example.invalid", "other@example.invalid"), listOf("12 Rue de l'Étude\nSuite 4", "42 Sample Way"))
        val intent = ContactEditorIntent.create(draft)!!
        assertEquals(android.content.Intent.ACTION_INSERT, intent.action); assertEquals(ContactsContract.Contacts.CONTENT_TYPE, intent.type)
        assertNull(intent.data); assertNull(intent.clipData); assertEquals(0, intent.flags)
        assertEquals(setOf(ContactsContract.Intents.Insert.DATA, ContactsContract.Intents.Insert.NAME, ContactsContract.Intents.Insert.FULL_MODE), intent.extras!!.keySet())
        @Suppress("DEPRECATION") val rows = intent.getParcelableArrayListExtra<ContentValues>(ContactsContract.Intents.Insert.DATA)!!
        fun values(mime: String, field: String) = rows.filter { it.getAsString(ContactsContract.Data.MIMETYPE) == mime }.map { it.getAsString(field) }
        assertEquals(draft.name, intent.getStringExtra(ContactsContract.Intents.Insert.NAME)); assertTrue(intent.getBooleanExtra(ContactsContract.Intents.Insert.FULL_MODE, false)); assertTrue(values(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME).isEmpty())
        assertEquals(listOf(draft.organization), values(Organization.CONTENT_ITEM_TYPE, Organization.COMPANY))
        assertEquals(listOf(draft.title), values(Organization.CONTENT_ITEM_TYPE, Organization.TITLE))
        assertEquals(draft.phones, values(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER)); assertEquals(draft.emails, values(Email.CONTENT_ITEM_TYPE, Email.ADDRESS))
        assertEquals(draft.addresses, values(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS))
        assertNull(ContactEditorIntent.create(draft.copy(name = "bad\u202E")))
    }
    @Test fun canceledCapturedWorkerClosesExactlyOnceAndCannotPublishIntoANewScan() {
        val store = ViewModelStore(); lateinit var model: CardViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CardViewModel::class.java]; model.open() }
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicInteger()
        val image = object : CardImage {
            override val rotation = 0
            override fun open(): InputStream { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)); return ByteArrayInputStream(CardFixture.bytes()) }
            override fun close() { closed.incrementAndGet() }
        }
        try {
            instrumentation.runOnMainSync { val s = model.state!!; model.captured(model.beginCapture(s.owner, s.epoch)!!, image) }
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { model.close(); model.open(); val s = model.state!!; assertNull(model.beginCapture(s.owner, s.epoch)) }
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while(closed.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(1, closed.get()); instrumentation.runOnMainSync { assertEquals(CardPhase.CAMERA, model.state!!.phase); assertNull(model.state!!.suggestions) }
        } finally { release.countDown(); instrumentation.runOnMainSync { store.clear() } }
    }
}
