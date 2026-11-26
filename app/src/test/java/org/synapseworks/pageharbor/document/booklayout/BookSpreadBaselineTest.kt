package org.synapseworks.pageharbor.document.booklayout

import java.io.File
import java.security.MessageDigest
import org.junit.Test
import org.junit.Assert.*

class BookSpreadBaselineTest {
    private fun corpus()=BookSpreadCorpus.read(javaClass.getResourceAsStream("/m11/corpus.csv")!!.reader())
    @Test fun independentSceneParametersRemainFrozen() {
        val bytes=javaClass.getResourceAsStream("/m11/corpus.csv")!!.readBytes()
        assertEquals("0eabad9e6c3d1a4c0f1b1aaab4e76e449c5a841db81a35ff59aff99fb3847133",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        assertEquals(40,corpus().size);assertEquals(24,corpus().count{it.kind=="SPREAD"})
    }
    @Test fun knownQuarterTurnsRoundTripWithoutChangingExpectedMaskOrPixels() {
        for(c in corpus()){
            val scene=BookSpreadCorpus.render(c);val restored=BookSpreadCorpus.rotate(BookSpreadCorpus.rotate(scene,c.rawTurns),4-c.rawTurns)
            assertEquals(scene.width,restored.width);assertEquals(scene.height,restored.height);assertArrayEquals(scene.pixels,restored.pixels);assertEquals(scene.protectedPixels,restored.protectedPixels)
        }
    }
    @Test fun midpointBaselineUsesOnlyIndependentMasksAndNeverLearnsTheGutter() {
        val rows=mutableListOf("id,protectedPixels,midpointRetainedPixels,midpointRetention")
        for(c in corpus().filter{it.kind=="SPREAD"}){
            val s=BookSpreadCorpus.render(c);assertTrue(s.protectedPixels.isNotEmpty())
            for(p in s.protectedPixels){val line=(c.top+(c.bottom-c.top)*p.y/(s.height-1))*s.width;assertEquals(p.left,p.x<line)}
            val kept=s.protectedPixels.count{(it.x<s.width/2)==it.left};rows.add("${c.id},${s.protectedPixels.size},$kept,${kept.toDouble()/s.protectedPixels.size}")
        }
        File("build/reports/m11-quality").mkdirs();File("build/reports/m11-quality/midpoint-retention.csv").writeText(rows.joinToString("\n")+"\n")
    }
}
