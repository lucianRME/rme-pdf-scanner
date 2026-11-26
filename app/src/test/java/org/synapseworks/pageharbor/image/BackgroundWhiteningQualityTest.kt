package org.synapseworks.pageharbor.image

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BackgroundWhiteningQualityTest {
    @Test fun frozenWhiteningAndProtectedContentGates() {
        val rows=mutableListOf("case,variant,paperMae,nearWhite,protectedMae,protectedMax,alphaChanges")
        val failures=mutableListOf<String>()
        for(c in BackgroundWhiteningCorpus.cases()) {
            val before=c.source.pixels.copyOf();val original=BackgroundWhiteningCorpus.metrics(c,before)
            for(f in DocumentFilter.entries.filter{it.whiteningRecipe!=null}) {
                val out=DocumentImageFilterEngine.apply(c.source,f);val m=BackgroundWhiteningCorpus.metrics(c,out.pixels)
                val recipe=requireNotNull(f.whiteningRecipe)
                rows.add("${c.id},$f,${m.paperMae},${m.nearWhite},${m.protectedMae},${m.protectedMax},${m.alphaChanges}")
                BackgroundWhiteningCorpus.save("${c.id}-$f",out)
                fun gate(ok:Boolean,name:String){if(!ok)failures.add("${c.id}:$f:$name")}
                gate(m.protectedMae<=1,"protected mean");gate(m.protectedMax<=4,"protected max");gate(m.alphaChanges==0,"alpha")
                if(recipe.amount==1f && original.paperMae>0 && (recipe.paper==WhiteningPaper.COLOUR||c.neutral)) {
                    gate(m.paperMae<=original.paperMae*0.45,"paper MAE");gate(m.nearWhite>=0.55,"paper coverage")
                }
                if(c.id=="white"||c.id.startsWith("photo-")||(!c.neutral&&recipe.paper==WhiteningPaper.NEUTRAL))gate(out.pixels.contentEquals(before),"exact preservation")
                for(i in before.indices)if(before[i] ushr 24!=255 || before[i]==-1)gate(before[i]==out.pixels[i],"transparent/clipped")
                assertEquals(c.source.width,out.width);assertEquals(c.source.height,out.height)
            }
            assertArrayEquals(before,c.source.pixels)
        }
        File("build/reports/m09-quality/candidate.csv").writeText(rows.joinToString("\n")+"\n")
        assertTrue(failures.joinToString(","),failures.isEmpty())
    }
}
