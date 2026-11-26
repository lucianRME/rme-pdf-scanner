package org.synapseworks.pageharbor.image

import java.util.concurrent.CancellationException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

class MoireSafetyTest {
    private val filters=DocumentFilter.entries.filter{it.moireAmount!=null}
    @Test fun rowEngineManualOnceDeterminismAndBoundedIntensity() {
        val settings=ManualImageAdjustments(20,-25)
        for(id in listOf("cross-axis-512","band-48-3-512")){val c=MoireCorpus.image(id,"source");val full=DocumentImageFilterEngine.apply(c,DocumentFilter.MOIRE_STRONG_V1)
            for(f in filters){val out=DocumentImageFilterEngine.apply(c,f);val manual=DocumentImageFilterEngine.apply(c,f,settings)
                assertArrayEquals(out.pixels.map{settings.apply(it)}.toIntArray(),manual.pixels)
                val streamed=IntArray(out.pixels.size);val buffers=Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>())
                DocumentImageFilterEngine.processSpatialRows(f,c.width,c.height,{y,row->buffers.add(row);c.pixels.copyInto(row,0,y*c.width,(y+1)*c.width)},{y,row->row.copyInto(streamed,y*c.width)})
                assertArrayEquals(out.pixels,streamed);assertTrue(buffers.size<=8)
                for(i in out.pixels.indices)for(s in listOf(0,8,16)){val original=c.pixels[i] ushr s and 255;val strong=full.pixels[i] ushr s and 255;val v=out.pixels[i] ushr s and 255;assertTrue(v in minOf(original,strong)..maxOf(original,strong))}
            }
        }
    }
    @Test fun cancellationInSamplingRegressionOutputAndFinalRowDiscardsPartialResult() {
        val c=MoireCorpus.image("cross-axis-512","source");val before=c.pixels.copyOf()
        for(stage in 0..3){var reads=0;var writes=0;var pollsAfterSamples=0;var cancelled=false
            try {MoireFilter.process(c.width,c.height,1f,{y,row->reads++;c.pixels.copyInto(row,0,y*c.width,(y+1)*c.width)},{y,_->writes++;if(y==c.height-1)cancelled=true},{
                if(reads==96)pollsAfterSamples++
                if((stage==0&&reads==3)||(stage==1&&pollsAfterSamples==8)||(stage==2&&writes==3)||(stage==3&&cancelled))throw CancellationException()
            });fail()}catch(_:CancellationException){}
            assertEquals(when(stage){2->3;3->c.height;else->0},writes)
        };assertArrayEquals(before,c.pixels)
    }
    @Test fun readWriteFailureAndTinyImagesRemainSafe() {
        for(failRead in listOf(true,false))try{MoireFilter.process(64,64,1f,{_,row->if(failRead)throw IOException()else row.fill(0xff808080.toInt())},{_,_->throw IOException()});fail()}catch(_:IOException){}
        for((w,h)in listOf(1 to 1,1 to 37,43 to 1))for(p in listOf(0,-1,0xffa0a0a0.toInt())){val source=ArgbImage(w,h,IntArray(w*h){p});for(f in filters)assertArrayEquals(source.pixels,DocumentImageFilterEngine.apply(source,f).pixels)}
    }
    @Test fun invalidDimensionsAmountsAndTransparentPixelsRemainSafe() {
        for((w,h)in listOf(0 to 1,1 to 0,-1 to 1,46341 to 46341))try{MoireFilter.process(w,h,1f,{_,_->fail()},{_,_->fail()});fail()}catch(_:IllegalArgumentException){}
        for(amount in listOf(-1f,2f,Float.NaN))try{MoireFilter.process(32,32,amount,{_,_->fail()},{_,_->fail()});fail()}catch(_:IllegalArgumentException){}
        val c=MoireCorpus.image("cross-axis-512","source")
        val small=ArgbImage(256,192,IntArray(256*192){i->c.pixels[(i/256*2)*512+(i%256*2)]})
        for(f in filters)assertArrayEquals(small.pixels,DocumentImageFilterEngine.apply(small,f).pixels)
        val p=c.pixels.copyOf();for(i in p.indices step 17)p[i]=(p[i]and 0xffffff)or(if(i%2==0)0 else 0x7f000000)
        val src=ArgbImage(c.width,c.height,p);val before=p.copyOf();for(f in filters){val out=DocumentImageFilterEngine.apply(src,f);for(i in p.indices){assertEquals(p[i] ushr 24,out.pixels[i] ushr 24);if(p[i] ushr 24!=255)assertEquals(p[i],out.pixels[i])}};assertArrayEquals(before,p)
    }
    @Test fun maximumAdmittedRasterUsesBoundedRowsAndCancelsBeforePublishing() {
        val buffers=Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>());var writes=0
        val band=IntArray(5000){x->val c=(232+8*kotlin.math.cos(2*kotlin.math.PI*24*x/5000)).toInt();(255 shl 24)or(c shl 16)or(c shl 8)or c}
        try{MoireFilter.process(5000,2500,1f,{y,row->buffers.add(row);if(y<10)row.fill(0xff202020.toInt())else band.copyInto(row)},{_,_->writes++},{if(writes==4)throw CancellationException()});fail()}catch(_:CancellationException){}
        assertEquals(4,writes);assertEquals(8,buffers.size)
    }
    @Test fun quarterTurnProfilesPreserveGeometryAndSharpDetail() {
        val src=MoireCorpus.image("cross-axis-512","source")
        fun rotated(a:ArgbImage)=ArgbImage(a.height,a.width,IntArray(a.pixels.size){i->val x=i%a.height;val y=i/a.height;a.pixels[(a.height-1-x)*a.width+y]})
        var current=src;var reference=MoireCorpus.image("cross-axis-512","reference");var foreground=MoireCorpus.image("cross-axis-512","foreground");var artifact=MoireCorpus.image("cross-axis-512","artifact")
        repeat(4){
            val before=current.pixels.copyOf();val actual=DocumentImageFilterEngine.apply(current,DocumentFilter.MOIRE_STRONG_V1)
            assertEquals(current.width,actual.width);assertEquals(current.height,actual.height)
            var inputError=0L;var outputError=0L
            for(i in before.indices)for(shift in listOf(0,8,16)){
                val observed=before[i] ushr shift and 255;val result=actual.pixels[i] ushr shift and 255
                if(foreground.pixels[i]and 255>0)assertTrue(kotlin.math.abs(observed-result)<=4)
                if(artifact.pixels[i]and 255>0){val clean=reference.pixels[i] ushr shift and 255;inputError+=kotlin.math.abs(observed-clean);outputError+=kotlin.math.abs(result-clean)}
            }
            assertTrue(1-outputError.toDouble()/inputError>=.50);assertArrayEquals(before,current.pixels)
            current=rotated(current);reference=rotated(reference);foreground=rotated(foreground);artifact=rotated(artifact)
        }
    }
}
