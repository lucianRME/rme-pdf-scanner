package org.synapseworks.pageharbor.table

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.*
import org.synapseworks.pageharbor.businesscard.*

class TableRecognitionInstrumentedTest {
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext.applicationContext as Application
    @Test fun firstOfflineGridIsExactBeforeEditingWithRawAndCropEvidence(){
        check(app.packageName.endsWith(".phase2test"));assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.checkSelfPermission(android.Manifest.permission.INTERNET))
        var closed=0;val bytes=TableTestImage.bytes(FrozenTables.optical)
        val result=TableRecognition.recognize({object:ByteArrayInputStream(bytes){override fun close(){closed++;super.close()}}})!!
        TableOpticalEvidence.record(app,"ordinary-upright",result.grid,FrozenTables.optical)
        assertEquals(FrozenTables.optical,result.grid.values());assertEquals(1,closed)
        assertEquals("BLUE B0OK",result.units.single{it.id==result.grid.rows[1][1].evidence.single()}.text)
        assertEquals(listOf("BLUE BOOK","BLUE BOOK"),result.grid.rows[1][1].readings.map{it.text});assertFalse(result.grid.rows.flatten().any{it.corrected})
        assertArrayEquals(i.context.assets.open("m22/optical-original.png").use{it.readBytes()},bytes)
        assertTrue(result.units.all{it.bounds!=null});assertEquals(result.units.size,result.grid.rows.flatten().sumOf{it.evidence.size})
    }
    @Test fun rotatedInputsUseSharedUprightGeometryWithoutChangingGridOrder(){
        for(rotation in listOf(90,180,270)){val bytes=TableTestImage.bytes(FrozenTables.optical,rotation)
            val result=TableRecognition.recognize({ByteArrayInputStream(bytes)},rotation)!!
            TableOpticalEvidence.record(app,"ordinary-rotation-$rotation",result.grid,FrozenTables.optical)
            assertEquals(FrozenTables.optical,result.grid.values());assertFalse(result.grid.rows.flatten().any{it.corrected})}
    }
    @Test fun genuineZeroLetterOControlsPreserveEveryInitiallyExactLiteralAndRecordKnownRawMisses(){
        val bytes=TableTestImage.bytes(FrozenLiteralTables.grid);val page=CardRecognition.recognizePage({ByteArrayInputStream(bytes)})!!
        val raw=TableParser.parse(page.text,page.layout)!!;val result=TableRecognition.recognize({ByteArrayInputStream(bytes)})!!
        TableOpticalEvidence.record(app,"ordinary-literals-raw",raw.grid,FrozenLiteralTables.grid)
        TableOpticalEvidence.record(app,"ordinary-literals-refined",result.grid,FrozenLiteralTables.grid)
        for(r in FrozenLiteralTables.grid.indices)for(c in FrozenLiteralTables.grid[r].indices){
            if(raw.grid.values()[r][c]==FrozenLiteralTables.grid[r][c])assertEquals(FrozenLiteralTables.grid[r][c],result.grid.values()[r][c])
            if(c!=1||r!=1)assertEquals(raw.grid.values()[r][c],result.grid.values()[r][c])
        }
        assertEquals(raw.source,result.source);assertEquals(raw.units,result.units)
    }
    @Test fun sourceRereadCancellationAndFailureCloseTheInputAndBoundAdditionalOcr(){
        val bytes=TableTestImage.bytes(FrozenTables.optical)
        val base=CardRecognition.recognizePage({ByteArrayInputStream(bytes)})!!
        var calls=0;var closed=0
        val cancel=object:org.synapseworks.pageharbor.ocr.OcrEngine{override fun recognize(pages:List<org.synapseworks.pageharbor.ocr.OcrPage>):org.synapseworks.pageharbor.ocr.OcrResult{
            calls++;pages.single().openJpegStream().use{assertTrue(it.read()>=0)}
            if(calls>1)throw kotlinx.coroutines.CancellationException()
            return org.synapseworks.pageharbor.ocr.OcrResult(listOf(base))
        }}
        try{TableRecognition.recognize({object:ByteArrayInputStream(bytes){override fun close(){closed++;super.close()}}},engine=cancel);fail()}catch(_:kotlinx.coroutines.CancellationException){assertEquals(2,calls);assertEquals(1,closed)}
        calls=0
        val failed=object:org.synapseworks.pageharbor.ocr.OcrEngine{override fun recognize(pages:List<org.synapseworks.pageharbor.ocr.OcrPage>):org.synapseworks.pageharbor.ocr.OcrResult{
            calls++;return org.synapseworks.pageharbor.ocr.OcrResult(listOf(if(calls==1)base else org.synapseworks.pageharbor.ocr.OcrPageResult(0,"",org.synapseworks.pageharbor.ocr.OcrPageError.RECOGNITION_FAILED)))
        }}
        val fallback=TableRecognition.recognize({ByteArrayInputStream(bytes)},engine=failed)!!
        assertEquals(TableParser.parse(base.text,base.layout)!!.grid.values(),fallback.grid.values());assertEquals(3,calls)
    }
    @Test fun allIndependentGeometryAndCsvFixturesAlsoMatchOnAndroid(){
        for(f in FrozenTables.cases){val r=TableParser.parse(f.text,f.layout())!!;assertEquals(f.name,f.expected,r.grid.values());assertEquals(f.name,f.uncertain,r.concerns.isNotEmpty())}
        val bytes=TableCsv.encode(TableGrid.from(FrozenTables.csvInput))!!
        File(app.cacheDir,"m22-contract.csv").writeBytes(bytes)
        File(app.cacheDir,"m22-contract-expected.json").writeText(org.json.JSONArray(FrozenTables.csvExpected).toString(),Charsets.UTF_8)
    }
    @Test fun malformedCanceledAndStaleInputClosesBeforeAnyResultCanReplaceSource(){
        var closed=0;assertNull(CardRecognition.recognizePage({object:ByteArrayInputStream(byteArrayOf(1,2,3)){override fun close(){closed++;super.close()}}}));assertEquals(1,closed)
        i.runOnMainSync{val m=TableViewModel(app);m.open();val s=m.state!!;val t=m.beginCapture(s.owner,s.epoch)!!;m.reset()
            m.captured(t,object:CardImage{override val rotation=0;override fun open():InputStream{fail("Stale source read");throw AssertionError()};override fun close(){closed++}})
            assertEquals(2,closed);assertNull(m.state!!.extraction);m.close()}
    }
    @Test fun canceledTableWorkerHoldsSharedOcrMutexUntilImageCloses(){
        val bytes=TableTestImage.bytes(FrozenTables.optical);val entered=CountDownLatch(1);val release=CountDownLatch(1);val second=CountDownLatch(1);val closes=java.util.concurrent.atomic.AtomicInteger()
        lateinit var first:TableViewModel;lateinit var card:CardViewModel
        i.runOnMainSync{first=TableViewModel(app);first.open();val s=first.state!!;val t=first.beginCapture(s.owner,s.epoch)!!
            first.captured(t,object:CardImage{override val rotation=0;override fun open():InputStream{entered.countDown();check(release.await(20,TimeUnit.SECONDS));return ByteArrayInputStream(bytes)};override fun close(){closes.incrementAndGet()}})}
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));i.runOnMainSync{first.close();card=CardViewModel(app);card.open();val s=card.state!!;val t=card.beginCapture(s.owner,s.epoch)!!
            card.captured(t,object:CardImage{override val rotation=0;override fun open():InputStream{second.countDown();return ByteArrayInputStream(bytes)};override fun close(){closes.incrementAndGet()}})}
            assertFalse(second.await(300,TimeUnit.MILLISECONDS));assertEquals(0,closes.get());release.countDown();assertTrue(second.await(15,TimeUnit.SECONDS));val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
            while(closes.get()<2&&System.nanoTime()<end)Thread.sleep(20);assertEquals(2,closes.get());i.runOnMainSync{assertNull(first.state)}
        }finally{release.countDown();i.runOnMainSync{first.close();card.close()}}
    }
}
