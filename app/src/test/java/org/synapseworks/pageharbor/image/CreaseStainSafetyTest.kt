package org.synapseworks.pageharbor.image

import java.util.concurrent.CancellationException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

class CreaseStainSafetyTest {
    private val filters=DocumentFilter.entries.filter{it.creaseStainAmount!=null}
    @Test fun rowEngineManualOnceDeterminismAndBoundedIntensity() {
        val settings=ManualImageAdjustments(20,-25)
        for(c in listOf(CreaseStainCorpus.image("combined","source"),CreaseStainCorpus.image("crossing","source")))for(f in filters) {
            val out=DocumentImageFilterEngine.apply(c,f)
            val manual=DocumentImageFilterEngine.apply(c,f,settings)
            assertArrayEquals(out.pixels.map{settings.apply(it)}.toIntArray(),manual.pixels)
            val streamed=IntArray(out.pixels.size);val buffers=Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>())
            DocumentImageFilterEngine.processSpatialRows(f,c.width,c.height,
                {y,row->buffers.add(row);c.pixels.copyInto(row,0,y*c.width,(y+1)*c.width)},
                {y,row->row.copyInto(streamed,y*c.width)})
            assertArrayEquals(out.pixels,streamed);assertTrue(buffers.size<=12)
            val strong=DocumentFilter.CREASE_STAIN_STRONG_V1
            val full=DocumentImageFilterEngine.apply(c,strong)
            for(i in out.pixels.indices)for(shift in listOf(0,8,16)) {
                val original=c.pixels[i] ushr shift and 255;val max=full.pixels[i] ushr shift and 255;val v=out.pixels[i] ushr shift and 255
                assertTrue(v in original..max)
            }
        }
    }
    @Test fun cancellationWhileAcceptingLastRowCannotReturnACompletedResult() {
        val c=CreaseStainCorpus.image("combined","source");var cancelled=false
        try {
            CreaseStainFilter.process(c.width,c.height,
                requireNotNull(DocumentFilter.CREASE_STAIN_STRONG_V1.creaseStainAmount),
                {y,row->c.pixels.copyInto(row,0,y*c.width,(y+1)*c.width)},
                {y,_->if(y==c.height-1)cancelled=true},
                {if(cancelled)throw CancellationException()})
            fail()
        }catch(_:CancellationException){}
        assertTrue(cancelled)
    }
    @Test fun cancellationInAllPassesAndReadWriteFailurePropagateWithoutCompletedOutput() {
        val c=CreaseStainCorpus.image("combined","source")
        val recipe=requireNotNull(DocumentFilter.CREASE_STAIN_STRONG_V1.creaseStainAmount)
        val before=c.pixels.copyOf()
        for(pass in 0..2) {
            var reads=0;var writes=0
            try {
                CreaseStainFilter.process(c.width,c.height,recipe,
                    {y,row->reads++;c.pixels.copyInto(row,0,y*c.width,(y+1)*c.width)},
                    {_,_->writes++},
                    {if((pass==0&&reads==3)||(pass==1&&reads==c.height+3)||(pass==2&&writes==3))throw CancellationException()})
                fail()
            }catch(_:CancellationException){}
            assertEquals(if(pass==2)3 else 0,writes)
        }
        assertArrayEquals(before,c.pixels)
        for(failRead in listOf(true,false))try {
            CreaseStainFilter.process(64,64,recipe,{_,row->if(failRead)throw IOException()else row.fill(0xff808080.toInt())},{_,_->throw IOException()});fail()
        }catch(_:IOException){}
        for((w,h)in listOf(1 to 1,1 to 37,43 to 1))for(p in listOf(0,-1,0xffa0a0a0.toInt())) {
            val source=ArgbImage(w,h,IntArray(w*h){p})
            for(f in filters)assertArrayEquals(source.pixels,DocumentImageFilterEngine.apply(source,f).pixels)
        }
    }
    @Test fun invalidDimensionsAndTransparentPixelsRemainSafe() {
        for((w,h)in listOf(0 to 1,1 to 0,-1 to 1,46341 to 46341))try{
            CreaseStainFilter.process(w,h,1f,{_,_->fail()},{_,_->fail()});fail()
        }catch(_:IllegalArgumentException){}
        val c=CreaseStainCorpus.image("combined","source");val p=c.pixels.copyOf()
        for(i in p.indices step 17)p[i]=(p[i]and 0xffffff)or(if(i%2==0)0 else 0x7f000000)
        val src=ArgbImage(c.width,c.height,p);val before=p.copyOf()
        for(f in filters){val out=DocumentImageFilterEngine.apply(src,f);for(i in p.indices){assertEquals(p[i] ushr 24,out.pixels[i] ushr 24);if(p[i] ushr 24!=255)assertEquals(p[i],out.pixels[i])}}
        assertArrayEquals(before,p)
    }
    @Test fun maximumAdmittedRasterUsesTwelveRowBuffersAndCancelsBeforePublishing() {
        val buffers=Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>());var writes=0
        try { CreaseStainFilter.process(5000,2500,1f,{y,row->buffers.add(row);row.fill(if(y<10)0xff202020.toInt() else 0xfff5f5f5.toInt())},{_,_->writes++},{if(writes==3)throw CancellationException()});fail() }catch(_:CancellationException){}
        assertEquals(3,writes);assertTrue(buffers.size<=12)
    }
    @Test fun frozenNeutralPageCorrectionCommutesWithQuarterTurnGeometry() {
        val src=CreaseStainCorpus.image("combined","source")
        fun rotated(a:ArgbImage)=ArgbImage(a.height,a.width,IntArray(a.pixels.size){i->val x=i%a.height;val y=i/a.height;a.pixels[(a.height-1-x)*a.width+y]})
        for(filter in filters)assertArrayEquals(rotated(DocumentImageFilterEngine.apply(src,filter)).pixels,DocumentImageFilterEngine.apply(rotated(src),filter).pixels)
    }

}
