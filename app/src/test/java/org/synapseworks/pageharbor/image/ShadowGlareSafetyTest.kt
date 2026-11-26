package org.synapseworks.pageharbor.image

import java.util.concurrent.CancellationException
import java.io.IOException
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

class ShadowGlareSafetyTest {
    private val filters=listOf(DocumentFilter.SHADOW_GLARE_LOW_V1,DocumentFilter.SHADOW_GLARE_BALANCED_V1,DocumentFilter.SHADOW_GLARE_STRONG_V1)
    @Test fun intensityIsBoundedDeterministicAndCannotChangeClippedPixelsOrSources() {
        for(c in ShadowGlareCorpus.cases()) {
            val original=c.source.pixels.copyOf();val strong=ShadowGlareFilter.apply(c.source,ShadowGlareIntensity.STRONG).pixels
            for(level in ShadowGlareIntensity.entries) {
                val out=ShadowGlareFilter.apply(c.source,level)
                assertEquals(c.source.width,out.width);assertEquals(c.source.height,out.height)
                assertArrayEquals(out.pixels,ShadowGlareFilter.apply(c.source,level).pixels)
                for(i in out.pixels.indices) {
                    assertEquals(original[i] ushr 24,out.pixels[i] ushr 24)
                    if(c.clipped[i])assertEquals(original[i],out.pixels[i])
                    for(shift in listOf(0,8,16)){val a=original[i] ushr shift and 255;val b=strong[i] ushr shift and 255;val v=out.pixels[i] ushr shift and 255;assertTrue(v in kotlin.math.min(a,b)..kotlin.math.max(a,b))}
                }
            }
            assertArrayEquals(original,c.source.pixels)
        }
    }
    @Test fun streamingAndEngineCompositionApplyManualExactlyOnceAfterEverySpatialFilter() {
        val c=ShadowGlareCorpus.document("uneven");val settings=ManualImageAdjustments(20,-25)
        for(filter in filters+DocumentFilter.DOCUMENT_CLEANUP) {
            val filtered=DocumentImageFilterEngine.apply(c.source,filter)
            val expected=filtered.pixels.map{settings.apply(it)}.toIntArray()
            val actual=DocumentImageFilterEngine.apply(c.source,filter,settings)
            assertArrayEquals(expected,actual.pixels)
            val streamed=IntArray(expected.size);val buffers=Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>())
            DocumentImageFilterEngine.processSpatialRows(filter,c.source.width,c.source.height,
                {y,row->buffers.add(row);c.source.pixels.copyInto(row,0,y*c.source.width,(y+1)*c.source.width)},
                {y,row->row.copyInto(streamed,y*c.source.width)})
            assertArrayEquals(filtered.pixels,streamed)
            assertTrue(buffers.size<=4)
        }
        assertArrayEquals(LocalDocumentCleanupFilter.apply(c.source).pixels,DocumentImageFilterEngine.apply(c.source,DocumentFilter.DOCUMENT_CLEANUP).pixels)
    }
    @Test fun alphaTransparentRgbTinyAndOneDimensionalInputsRemainSafe() {
        val c=ShadowGlareCorpus.document("uneven");val pixels=c.source.pixels.copyOf()
        for(i in pixels.indices step 17)pixels[i]=(pixels[i]and 0xffffff)or(if(i%2==0)0 else 0x7f000000)
        val src=ArgbImage(c.source.width,c.source.height,pixels);val before=pixels.copyOf()
        for(filter in filters) {
            val out=DocumentImageFilterEngine.apply(src,filter)
            for(i in pixels.indices){assertEquals(pixels[i] ushr 24,out.pixels[i] ushr 24);if(pixels[i] ushr 24==0)assertEquals(pixels[i],out.pixels[i])}
        }
        assertArrayEquals(before,pixels)
        for((w,h)in listOf(1 to 1,1 to 37,43 to 1))for(pixel in listOf(0,0xff000000.toInt(),-1)) {
            val s=ArgbImage(w,h,IntArray(w*h){pixel})
            for(f in filters)assertArrayEquals(s.pixels,DocumentImageFilterEngine.apply(s,f).pixels)
        }
    }
    @Test fun cancellationInEachPassNeverReturnsACompletedOutputOrMutatesInput() {
        val c=ShadowGlareCorpus.document("soft-shadow");val before=c.source.pixels.copyOf()
        for(pass in 0..2) {
            var reads=0;var writes=0
            try {
                ShadowGlareFilter.process(c.source.width,c.source.height,ShadowGlareIntensity.STRONG,
                    {y,row->reads++;c.source.pixels.copyInto(row,0,y*c.source.width,(y+1)*c.source.width)},
                    {_,_->writes++},
                    {if((pass==0&&reads==3)||(pass==1&&reads==c.source.height+3)||(pass==2&&writes==3))throw CancellationException()})
                fail()
            }catch(_:CancellationException){}
            assertEquals(if(pass==2)3 else 0,writes);assertArrayEquals(before,c.source.pixels)
        }
    }
    @Test fun invalidDimensionsAndReadWriteFailuresRemainFailures() {
        for((w,h)in listOf(0 to 1,1 to 0,-1 to 1,46341 to 46341))try{
            ShadowGlareFilter.process(w,h,ShadowGlareIntensity.STRONG,{_,_->fail()},{_,_->fail()});fail()
        }catch(_:IllegalArgumentException){}
        var writes=0
        try {ShadowGlareFilter.process(64,64,ShadowGlareIntensity.STRONG,{y,row->if(y==3)throw IOException()else row.fill(0xff808080.toInt())},{_,_->writes++});fail()}catch(_:IOException){}
        assertEquals(0,writes)
        try {ShadowGlareFilter.process(64,64,ShadowGlareIntensity.STRONG,{_,row->row.fill(0xff808080.toInt())},{_,_->throw IOException()});fail()}catch(_:IOException){}
    }
    @Test fun representativePreviewSamplingDifferenceIsMeasuredWithinEightBitLimits() {
        val rows=mutableListOf("case,meanRgbDifference,maxChannelDifference")
        for(id in listOf("uneven","hard-shadow","recoverable-glare")) {
            val base=ShadowGlareCorpus.document(id).source
            fun scaled(f:Int)=ArgbImage(base.width*f,base.height*f,IntArray(base.width*base.height*f*f){i->base.pixels[(i/(base.width*f)/f)*base.width+(i%(base.width*f)/f)]})
            val full=ShadowGlareFilter.apply(scaled(4),ShadowGlareIntensity.STRONG)
            val preview=ShadowGlareFilter.apply(scaled(2),ShadowGlareIntensity.STRONG)
            var error=0.0;var largest=0
            for(y in 0 until preview.height)for(x in 0 until preview.width)for(shift in listOf(0,8,16)) {
                val average=((full.pixels[(y*2)*full.width+x*2] ushr shift and 255)+(full.pixels[(y*2)*full.width+x*2+1] ushr shift and 255)+
                    (full.pixels[(y*2+1)*full.width+x*2] ushr shift and 255)+(full.pixels[(y*2+1)*full.width+x*2+1] ushr shift and 255))/4
                val delta=kotlin.math.abs(average-(preview.pixels[y*preview.width+x] ushr shift and 255));error+=delta;largest=kotlin.math.max(largest,delta)
            }
            val mean=error/(preview.pixels.size*3);rows.add("$id,$mean,$largest");assertTrue(mean<=10)
        }
        File("build/reports/m08-quality/sampling.csv").writeText(rows.joinToString("\n")+"\n")
    }
}
