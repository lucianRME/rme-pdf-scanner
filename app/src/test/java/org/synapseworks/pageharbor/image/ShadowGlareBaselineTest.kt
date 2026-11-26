package org.synapseworks.pageharbor.image

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ShadowGlareBaselineTest {
    @Test fun fixedOriginalAndPrototypeMeasurementsAreRetainedBeforeTuning() {
        val rows=mutableListOf("case,variant,mae,inkMae,backgroundMae,falseInk,contrast,clippedChanges")
        for(c in ShadowGlareCorpus.cases()) {
            val before=c.source.pixels.copyOf()
            for((name,pixels)in listOf("original" to c.source.pixels,"prototype-v1" to LocalDocumentCleanupFilter.apply(c.source).pixels)) {
                val m=ShadowGlareCorpus.metrics(c,pixels)
                rows.add("${c.id},$name,${m.mae},${m.inkMae},${m.backgroundMae},${m.falseInk},${m.contrast},${m.clippedChanges}")
                if(c.id=="recoverable-glare") {
                    val roi=ShadowGlareCorpus.metrics(c,pixels,glareRegionOnly=true)
                    rows.add("${c.id}-roi,$name,${roi.mae},${roi.inkMae},${roi.backgroundMae},${roi.falseInk},${roi.contrast},${roi.clippedChanges}")
                }
                ShadowGlareCorpus.save("${c.id}-$name",ArgbImage(c.source.width,c.source.height,pixels))
            }
            assertArrayEquals(before,c.source.pixels)
        }
        File("build/reports/m08-quality/baseline.csv").writeText(rows.joinToString("\n")+"\n")
        assertEquals(11,ShadowGlareCorpus.cases().size)
    }
}
