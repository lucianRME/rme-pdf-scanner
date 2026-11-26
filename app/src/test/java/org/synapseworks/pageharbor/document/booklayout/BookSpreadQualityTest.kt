package org.synapseworks.pageharbor.document.booklayout

import java.io.File
import kotlin.math.*
import org.junit.Test
import org.junit.Assert.*

class BookSpreadQualityTest {
    private fun cases()=BookSpreadCorpus.read(javaClass.getResourceAsStream("/m11/corpus.csv")!!.reader())
    @Test fun frozenAutomaticAccuracyRetentionOrderingAndFalseSplitGates() {
        val rows=mutableListOf("id,kind,status,expectedTop,expectedBottom,detectedTop,detectedBottom,maxEndpointError,lineRmse,protectedRetention,midpointMeanEndpointError")
        var positive=0;var falseSplit=0;var maxError=0.0;var maxRmse=0.0;var minimumRetention=1.0;var errorSum=0.0;var baselineSum=0.0
        for(c in cases()){
            val truth=BookSpreadCorpus.render(c);val raw=BookSpreadCorpus.rotate(truth,c.rawTurns)
            val input=BookAnalysisImage(raw.width,raw.height,raw.pixels).rotated(4-c.rawTurns)
            val d=BookSpreadDetector.detect(input);val line=d.line
            var error:Double?=null;var rmse:Double?=null;var retention:Double?=null
            val baseline=(abs(.5-c.top)+abs(.5-c.bottom))/2
            if(c.kind=="SPREAD"&&d.status==BookDetectionStatus.CONFIDENT){
                positive++;val a=(line!!.top-c.top).toDouble();val b=(line.bottom-c.bottom).toDouble();error=max(abs(a),abs(b));rmse=sqrt((a*a+a*b+b*b)/3)
                errorSum+=(abs(a)+abs(b))/2;baselineSum+=baseline;maxError=max(maxError,error);maxRmse=max(maxRmse,rmse)
                retention=truth.protectedPixels.count{p->(p.x<line.cut(input.width,input.height,p.y))==p.left}.toDouble()/truth.protectedPixels.size;minimumRetention=min(minimumRetention,retention)
                for(order in BookReadingOrder.entries){val halves=line.halves(input.width,input.height,order);assertEquals(order==BookReadingOrder.LEFT_TO_RIGHT,halves.first().left);assertEquals(!halves.first().left,halves.last().left)}
            } else if(c.kind!="SPREAD"&&d.status==BookDetectionStatus.CONFIDENT)falseSplit++
            rows.add("${c.id},${c.kind},${d.status},${c.top},${c.bottom},${line?.top?:""},${line?.bottom?:""},${error?:""},${rmse?:""},${retention?:""},$baseline")
        }
        File("build/reports/m11-quality").mkdirs();File("build/reports/m11-quality/candidate.csv").writeText(rows.joinToString("\n")+"\n")
        assertTrue("confident positives=$positive",positive>=22);assertEquals("confident negative/ambiguous controls",0,falseSplit)
        assertTrue("max endpoint error=$maxError",maxError<=.01);assertTrue("line RMSE=$maxRmse",maxRmse<=.008)
        assertEquals("protected pixels on their original half",1.0,minimumRetention,0.0);assertTrue(errorSum<=baselineSum*.5)
    }
    @Test fun manualSlantedPartitionAssignsEverySourcePixelOnceInBothReadingOrders() {
        for(line in listOf(BookSplitLine(.1f,.9f),BookSplitLine(.63f,.58f),BookSplitLine(.5f,.5f)))for(order in BookReadingOrder.entries){
            val halves=line.halves(481,319,order);assertEquals(2,halves.size)
            val assignments=IntArray(481*319)
            for(half in halves)for(y in 0 until 319)for(localX in 0 until half.width){
                val x=localX+half.sourceOffset;val cut=line.cut(481,319,y)
                if((half.left&&x<cut)||(!half.left&&x>=cut))assignments[y*481+x]++
            }
            assertTrue("Every source pixel belongs to exactly one half",assignments.all{it==1})
        }
    }
    @Test fun cancellationAndInvalidGeometryAreConservative() {
        val c=cases().first();val s=BookSpreadCorpus.render(c);var canceled=false
        try{BookSpreadDetector.detect(BookAnalysisImage(s.width,s.height,s.pixels)){throw java.util.concurrent.CancellationException()}}catch(_:java.util.concurrent.CancellationException){canceled=true}
        assertTrue(canceled)
        for(n in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,1f)){var rejected=false;try{BookSplitLine(n,.5f)}catch(_:IllegalArgumentException){rejected=true};assertTrue(rejected)}
    }
    @Test fun invisibleRgbCannotBecomeEvidenceOfAFacingPageSpread() {
        val scene=BookSpreadCorpus.render(cases().first());val invisible=scene.pixels.map{it and 0x00ffffff}.toIntArray()
        assertNotEquals(BookDetectionStatus.CONFIDENT,BookSpreadDetector.detect(BookAnalysisImage(scene.width,scene.height,invisible)).status)
    }

}
