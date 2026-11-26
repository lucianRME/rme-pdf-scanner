package org.synapseworks.pageharbor.image

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BackgroundWhiteningBaselineTest {
    @Test fun freezeIndependentMasksReferenceAndOriginalM08Pixels() {
        val root=File("build/reports/m09-quality").apply{mkdirs()}
        val hashes=mutableListOf("case,source,reference,protectedMask")
        val rows=mutableListOf("case,variant,paperMae,nearWhite,protectedMae,protectedMax,alphaChanges")
        for(c in BackgroundWhiteningCorpus.cases()) {
            hashes.add("${c.id},${BackgroundWhiteningCorpus.hash(c.source.pixels)},${BackgroundWhiteningCorpus.hash(c.reference.pixels)},${BackgroundWhiteningCorpus.hash(IntArray(c.protected.size){if(c.protected[it])1 else 0})}")
            for(f in listOf(DocumentFilter.ORIGINAL,DocumentFilter.SHADOW_GLARE_STRONG_V1,DocumentFilter.BLACK_AND_WHITE)) {
                val out=DocumentImageFilterEngine.apply(c.source,f);val m=BackgroundWhiteningCorpus.metrics(c,out.pixels)
                rows.add("${c.id},$f,${m.paperMae},${m.nearWhite},${m.protectedMae},${m.protectedMax},${m.alphaChanges}")
                BackgroundWhiteningCorpus.save("${c.id}-$f",out)
            }
        }
        File(root,"corpus-sha256.csv").writeText(hashes.joinToString("\n")+"\n")
        File(root,"baseline.csv").writeText(rows.joinToString("\n")+"\n")
        val m08=ShadowGlareCorpus.cases().flatMap { c -> ShadowGlareIntensity.entries.map { level ->
            "${c.id},$level,${BackgroundWhiteningCorpus.hash(ShadowGlareFilter.apply(c.source,level).pixels)}"
        }}
        File(root,"m08-pixel-sha256.csv").writeText((listOf("case,intensity,sha256")+m08).joinToString("\n")+"\n")
        for(name in listOf("corpus-sha256.csv","m08-pixel-sha256.csv")) {
            val frozen=requireNotNull(javaClass.getResourceAsStream("/m09/$name")).bufferedReader().use{it.readText()}
            assertEquals("Frozen $name",frozen,File(root,name).readText())
        }
        assertEquals(15,BackgroundWhiteningCorpus.cases().size)
    }
}
