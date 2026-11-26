package org.synapseworks.pageharbor.document.curvature

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.booklayout.*
import java.io.File
import kotlin.math.*

class CurvatureBaselineTest {
    private fun scenes()=CurvatureCorpus.scenes(javaClass.getResourceAsStream("/m12/corpus.csv")!!)
    @Test fun corpusHasIndependentReferenceAndHeldOutGeometry() {
        val all=scenes();assertEquals(40,all.size);assertEquals(24,all.count{it.positive});assertEquals(8,all.count{it.positive&&it.partition=="heldout"})
        assertEquals(16,all.count{!it.positive});assertEquals(4,all.count{it.kind=="FLAT"})
        all.filter{it.positive}.forEach{s->val im=CurvatureCorpus.distorted(s);assertEquals(480*640,im.pixels.size);assertTrue(CurvatureCorpus.protected(s).size>1000)
            CurvatureCorpus.landmarks(s).forEach{(x,y)->assertTrue(s.sourceY(x,y)in 0.0..1.0)}}
    }
    @Test fun unchangedAndEndpointLinearBaselinesRemainVisible() {
        val rows=scenes().map{s->
            val errors=CurvatureCorpus.landmarks(s).map{(x,y)->abs(s.sourceY(x,y)-s.referenceY(y))}
            val linear=CurvatureCorpus.landmarks(s).map{(x,y)->
                val a=s.offset(0.0)*(1-x)+s.offset(1.0)*x;val b=s.scale(0.0)*(1-x)+s.scale(1.0)*x
                abs(s.referenceY((s.sourceY(x,y)-a)/b)-s.referenceY(y))}
            listOf(s.id,s.kind,s.partition,errors.average(),errors.max(),linear.average()).joinToString(",")
        }
        val report=File("build/reports/m12-quality");report.mkdirs();File(report,"baseline.csv").writeText("id,kind,partition,unchangedMeanError,unchangedMaxError,endpointLinearMeanError\n"+rows.joinToString("\n")+"\n")
        assertEquals(40,rows.size)
    }

    @Test fun horizontalStretchHasFixedEdgesAndCannotFold() {
        for (stretch in listOf(-.40f, -.1f, 0f, .1f, .40f)) {
            val field = CurvatureField.identity().copy(horizontalStretch = stretch)
            val samples = (0..1000).map { field.sourceX(it / 1000f) }
            org.junit.Assert.assertEquals(0f, samples.first(), 0f)
            org.junit.Assert.assertEquals(1f, samples.last(), 0f)
            org.junit.Assert.assertTrue(samples.zipWithNext().all { (a, b) -> b > a })
            org.junit.Assert.assertEquals(stretch == 0f, field.isIdentity())
        }
    }

}
