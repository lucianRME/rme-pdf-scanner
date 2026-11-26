package org.synapseworks.pageharbor.image

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ShadowGlareQualityTest {
    @Test fun fixedCorpusMeetsUsefulImprovementAndPreservationGates() {
        val rows=mutableListOf("case,variant,mae,inkMae,backgroundMae,falseInk,contrast,clippedChanges")
        val failures=mutableListOf<String>()
        for(c in ShadowGlareCorpus.cases()) {
            val before=c.source.pixels.copyOf();val original=ShadowGlareCorpus.metrics(c,before)
            val prototype=ShadowGlareCorpus.metrics(c,LocalDocumentCleanupFilter.apply(c.source).pixels)
            val output=ShadowGlareFilter.apply(c.source,ShadowGlareIntensity.STRONG)
            val m=ShadowGlareCorpus.metrics(c,output.pixels)
            rows.add("${c.id},strong,${m.mae},${m.inkMae},${m.backgroundMae},${m.falseInk},${m.contrast},${m.clippedChanges}")
            ShadowGlareCorpus.save("${c.id}-strong",output)
            fun gate(ok:Boolean,name:String){if(!ok)failures.add("${c.id}:$name")}
            when(c.id){
                "uneven"->{gate(m.mae<=original.mae*0.60,"original MAE");gate(m.mae<=prototype.mae*0.85,"prototype MAE");gate(m.inkMae<=24,"ink")}
                "soft-shadow","hard-shadow","small-text"->{gate(m.mae<=original.mae*0.65,"original MAE");gate(m.mae<=prototype.mae*0.90,"prototype MAE");gate(m.inkMae<=24,"ink");gate(m.falseInk<=0.03,"false ink")}
                "recoverable-glare"->{
                    gate(m.mae<=original.mae*0.65,"original MAE");gate(m.mae<=prototype.mae*0.80,"prototype MAE");gate(m.inkMae<=24,"ink")
                    val beforeRoi=ShadowGlareCorpus.metrics(c,before,true);val roi=ShadowGlareCorpus.metrics(c,output.pixels,true)
                    rows.add("${c.id}-roi,strong,${roi.mae},${roi.inkMae},${roi.backgroundMae},${roi.falseInk},${roi.contrast},${roi.clippedChanges}")
                    gate(roi.contrast>=beforeRoi.contrast*1.6,"ROI contrast");gate(roi.inkMae<=24,"additional ROI ink preservation")
                }
                "coloured-shadow"->{gate(m.mae<=original.mae*0.65,"original MAE");gate(m.backgroundMae<=16,"colour")
                    for(shift in listOf(0,8,16)) {
                        var error=0.0;var count=0
                        for(i in output.pixels.indices)if(!c.ink[i]){error+=kotlin.math.abs((output.pixels[i] ushr shift and 255)-(c.reference.pixels[i] ushr shift and 255));count++}
                        gate(error/kotlin.math.max(1,count)<=16,"per-channel colour")
                    }}
                "clean-warm","clean-coloured","photo-crater","photo-earthrise"->gate(m.mae<=1,"preservation")
                "clipped"->gate(m.clippedChanges==0,"clipped pixels")
            }
            assertEquals(c.source.width,output.width);assertEquals(c.source.height,output.height)
            assertArrayEquals(before,c.source.pixels)
        }
        File("build/reports/m08-quality/candidate.csv").writeText(rows.joinToString("\n")+"\n")
        assertTrue(failures.joinToString(","),failures.isEmpty())
    }
}
