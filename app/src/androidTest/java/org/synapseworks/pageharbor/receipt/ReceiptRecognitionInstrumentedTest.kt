package org.synapseworks.pageharbor.receipt

import android.app.Application
import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.synapseworks.pageharbor.businesscard.*

/** Frozen literal text, independent optical renderer and expected values. Synthetic only. */
internal object ReceiptFixture {
    val expected = mapOf(ReceiptField.MERCHANT to "HARBOR MARKET", ReceiptField.DATE to "2026-10-24", ReceiptField.CURRENCY to "EUR",
        ReceiptField.SUBTOTAL to "10.00",ReceiptField.TAX to "2.00",ReceiptField.TOTAL to "12.00",ReceiptField.TENDER to "20.00",ReceiptField.CHANGE to "8.00")
    fun bytes(rotation: Int = 0): ByteArray {
        val lines=InstrumentationRegistry.getInstrumentation().context.assets.open("m21/optical-lines.txt").bufferedReader().use { it.readLines() }
        val base=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888)
        Canvas(base).apply { drawColor(Color.WHITE); val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=54f;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
            lines.forEachIndexed { i,t -> drawText(t,70f,100f+i*125f,paint) } }
        val image=if(rotation==0) base else Bitmap.createBitmap(base,0,0,base.width,base.height,Matrix().apply { postRotate(-rotation.toFloat()) },true)
        return try { ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() }
        finally { if(image!==base) image.recycle();base.recycle() }
    }
    fun extract(bytes: ByteArray, rotation: Int? = null): ReceiptExtraction? = CardRecognition.recognizePage({ByteArrayInputStream(bytes)},rotation)?.let { ReceiptParser.parse(it.text,it.layout) }
}

class ReceiptRecognitionInstrumentedTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as Application
    @Test fun firstOfflineBundledLatinReceiptMatchesIndependentFieldsAndEvidence() {
        check(app.packageName.endsWith(".phase2test"))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.checkSelfPermission(android.Manifest.permission.INTERNET))
        val result=ReceiptFixture.extract(ReceiptFixture.bytes())!!
        assertEquals(ReceiptFixture.expected,result.draft().normalized(ReceiptRules()))
        for(field in ReceiptFixture.expected.keys) {
            val c=result.fields.getValue(field).candidates.first(); assertTrue(c.rows.isNotEmpty());assertNotNull(c.rows.first().bounds)
        }
    }
    @Test fun rightAnglesPreserveFieldsThroughSharedBoundedOcr() {
        for(rotation in listOf(90,180,270)) assertEquals(ReceiptFixture.expected,ReceiptFixture.extract(ReceiptFixture.bytes(rotation),rotation)!!.draft().normalized(ReceiptRules()))
    }
    @Test fun inputStreamsCloseOnSuccessMalformedLimitAndCancellation() {
        val bytes=ReceiptFixture.bytes();var closed=0
        fun open(b:ByteArray) = object:ByteArrayInputStream(b) { override fun close() { closed++;super.close() } }
        assertNotNull(CardRecognition.recognizePage({open(bytes)}));assertEquals(1,closed)
        assertNull(CardRecognition.recognizePage({open(byteArrayOf(1,2,3))}));assertEquals(2,closed)
        var checks=0
        try { CardRecognition.recognizePage({open(bytes)},check={ if(++checks==3) throw kotlinx.coroutines.CancellationException() });fail() }
        catch(_:kotlinx.coroutines.CancellationException) { assertEquals(3,closed) }
        val extreme=Bitmap.createBitmap(17000,1,Bitmap.Config.ARGB_8888);val out=ByteArrayOutputStream()
        extreme.compress(Bitmap.CompressFormat.PNG,100,out);extreme.recycle();assertNull(CardRecognition.recognizePage({open(out.toByteArray())}));assertEquals(4,closed)
    }
    @Test fun canceledPhysicalWorkerClosesExactlyOnceAndSharedCardWorkerCannotOverlap() {
        val bytes=ReceiptFixture.bytes();val entered=CountDownLatch(1);val release=CountDownLatch(1);val secondEntered=CountDownLatch(1)
        val closed=AtomicInteger();lateinit var receipt:ReceiptViewModel;lateinit var card:CardViewModel
        i.runOnMainSync {
            receipt=ReceiptViewModel(app);card=CardViewModel(app);receipt.open();val s=receipt.state!!;val t=receipt.beginCapture(s.owner,s.epoch)!!
            receipt.captured(t,object:CardImage { override val rotation=0
                override fun open():InputStream { entered.countDown();check(release.await(20,TimeUnit.SECONDS));return ByteArrayInputStream(bytes) }
                override fun close() { closed.incrementAndGet() }
            })
        }
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            i.runOnMainSync { receipt.lock();receipt.setActive(true);receipt.open();card.open();val s=card.state!!;val t=card.beginCapture(s.owner,s.epoch)!!
                card.captured(t,object:CardImage { override val rotation=0
                    override fun open():InputStream { secondEntered.countDown();return ByteArrayInputStream(bytes) }
                    override fun close() { closed.incrementAndGet() }
                })
            }
            assertFalse(secondEntered.await(400,TimeUnit.MILLISECONDS));assertEquals(0,closed.get());release.countDown()
            assertTrue(secondEntered.await(15,TimeUnit.SECONDS));val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(closed.get()<2 && System.nanoTime()<deadline) Thread.sleep(25)
            assertEquals(2,closed.get());i.runOnMainSync { assertNull(receipt.state!!.extraction) }
        } finally { release.countDown();i.runOnMainSync { receipt.close();card.close() } }
    }
    @Test fun staleCaptureClosesWithoutReadingAndPermissionDenialRemainsActionable() {
        val closed=AtomicInteger()
        i.runOnMainSync {
            val model=ReceiptViewModel(app);model.open();val s=model.state!!;val t=model.beginCapture(s.owner,s.epoch)!!;model.reset()
            model.captured(t,object:CardImage { override val rotation=0;override fun open():InputStream { fail("Stale input read");throw AssertionError() };override fun close(){closed.incrementAndGet()} })
            assertEquals(1,closed.get());val next=model.state!!;assertTrue(model.beginPermission(next.owner,next.epoch));model.permissionResult(false)
            assertEquals(ReceiptPhase.PAUSED,model.state!!.phase);assertNotNull(model.state!!.problem);model.close()
        }
    }
}
