package org.synapseworks.pageharbor.document.curvature

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.booklayout.*
import java.io.File
import kotlin.math.*

class CurvatureQualityTest {
    private fun scenes()=CurvatureCorpus.scenes(javaClass.getResourceAsStream("/m12/corpus.csv")!!)
    private data class Result(val scene:CurveScene,val estimate:CurvatureEstimate,val maximum:Double,val mean:Double,val baseline:Double,val improvement:Double,val retention:Double,val ratio:Double) {
        fun csv()=listOf(scene.id,scene.partition,scene.kind,estimate.status,estimate.tracks,maximum,mean,baseline,improvement,retention,ratio,estimate.spacingVariation).joinToString(",")
    }
    private fun residual(points:List<Pair<Double,Double>>):Double {
        val mx=points.map{it.first}.average();val my=points.map{it.second}.average();val slope=points.sumOf{(it.first-mx)*(it.second-my)}/points.sumOf{(it.first-mx).pow(2)}
        return sqrt(points.sumOf{(it.second-my-slope*(it.first-mx)).pow(2)}/points.size)
    }
    private fun measure(scene:CurveScene):Result {
        val image=CurvatureCorpus.distorted(scene);val e=CurvatureEstimator.estimate(CurvatureImage(image.width,image.height,image.pixels));val f=e.field
        if(f==null)return Result(scene,e,0.0,0.0,0.0,0.0,0.0,1.0)
        val errors=CurvatureCorpus.landmarks(scene).map{(x,y)->abs(f.correctedY(x.toFloat(),scene.sourceY(x,y).toFloat())-scene.referenceY(y))}
        val base=CurvatureCorpus.landmarks(scene).map{(x,y)->val a=scene.offset(0.0)*(1-x)+scene.offset(1.0)*x;val b=scene.scale(0.0)*(1-x)+scene.scale(1.0)*x
            abs(scene.referenceY((scene.sourceY(x,y)-a)/b)-scene.referenceY(y))}.average()
        val before=CurvatureCorpus.baselines(scene).map{y->residual((0..40).map{it/40.0 to scene.sourceY(it/40.0,y)})}.average()
        val after=CurvatureCorpus.baselines(scene).map{y->residual((0..40).map{val x=it/40.0;x to f.correctedY(x.toFloat(),scene.sourceY(x,y).toFloat()).toDouble()})}.average()
        val protected=CurvatureCorpus.protected(scene)
        val retained=protected.count{(x,y)->val source=scene.sourceY(x,y);val v=f.correctedY(x.toFloat(),source.toFloat());source in 0.0..1.0&&v>=f.minimumY-1e-6&&v<=f.maximumY+1e-6}.toDouble()/protected.size
        return Result(scene,e,errors.max(),errors.average(),base,1-after/before,retained,(f.maximumY-f.minimumY).toDouble())
    }
    private fun evaluate(all:List<CurveScene>,name:String,minimum:Int,checkHeldout:Boolean=false) {
        val results=all.map(::measure);val out=File("build/reports/m12-quality");out.mkdirs();File(out,"$name.csv").writeText("id,partition,kind,status,tracks,maxErrorHeight,meanErrorHeight,endpointLinearMeanError,straightnessImprovement,protectedRetention,heightRatio,spacingVariation\n"+results.joinToString("\n"){it.csv()}+"\n")
        val accepted=results.filter{it.scene.positive&&it.estimate.status==CurvatureStatus.PROPOSED}
        assertTrue("Too few automatic positives: ${accepted.size}/$minimum",accepted.size>=minimum)
        if(checkHeldout)assertTrue("Heldout automatic positives",accepted.count{it.scene.partition=="heldout"}>=7)
        results.filter{!it.scene.positive}.forEach{assertNotEquals("False correction ${it.scene.id}",CurvatureStatus.PROPOSED,it.estimate.status)}
        accepted.forEach{r->
            assertTrue("Landmark ${r.scene.id}: ${r.maximum}",r.maximum<=.012)
            assertTrue("Straightness ${r.scene.id}: ${r.improvement}",r.improvement>=.65)
            assertTrue("Retention ${r.scene.id}",r.retention>=.995)
            assertTrue("Canvas ${r.scene.id}",r.ratio<=1.5)
            val f=r.estimate.field!!
            for(i in 0..400){val x=i/400f;assertTrue(1/f.scale(x)in .65f..1.5f);assertTrue(f.correctedY(x,0f)>=f.minimumY-1e-6f);assertTrue(f.correctedY(x,1f)<=f.maximumY+1e-6f)}
        }
        assertTrue("Median straightness",accepted.map{it.improvement}.sorted()[accepted.size/2]>=.8)
        assertTrue("Mean geometry vs endpoint baseline",accepted.map{it.mean}.average()<=accepted.map{it.baseline}.average()*.5)
    }
    @Test fun developmentAutomaticQualityGates()=evaluate(scenes().filter{it.partition=="development"},"development",15)
    @Test fun frozenAllIncludingHeldOutAutomaticGates()=evaluate(scenes(),"candidate",22,true)
    @Test fun independentFinalChallengeUsesUnchangedAutomaticGates()=evaluate(
        CurvatureCorpus.scenes(javaClass.getResourceAsStream("/m12/final-challenge.csv")!!),"final-challenge",7)
    @Test fun interpolationTransparencyBoundsAndInvalidFields() {
        val mid=CurvatureSampling.bilinear(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt(),.5f,.5f)
        assertEquals(0xff808080.toInt(),mid);assertEquals(-1,CurvatureSampling.white(0x00123456))
        val f=CurvatureField.manual(listOf(.1f,0f,-.1f,0f,.1f),.1f);assertTrue(f.dimensions(480,640).second>640)
        val taper=CurvatureField(List(21){0f},List(21){.84f+it*.016f})
        val adjusted=taper.withAnchor(2,.04f);assertEquals(.04f,adjusted.offset(.5f),0f)
        assertEquals(.84f,adjusted.scale(0f),.00001f);assertEquals(1.16f,adjusted.scale(1f),.00001f)
        assertThrows(IllegalArgumentException::class.java){CurvatureField(listOf(0f,0f,0f),listOf(1f,-1f,1f))}
        assertThrows(IllegalArgumentException::class.java){CurvatureField.manual(listOf(0f,Float.NaN,0f,0f,0f),0f)}
    }
    @Test fun cancellationInterruptsEvidenceExtraction() {
        val s=scenes().first();val im=CurvatureCorpus.distorted(s);var count=0
        assertThrows(IllegalStateException::class.java){CurvatureEstimator.estimate(CurvatureImage(im.width,im.height,im.pixels)){if(++count>3)error("cancel")}}
    }
}
