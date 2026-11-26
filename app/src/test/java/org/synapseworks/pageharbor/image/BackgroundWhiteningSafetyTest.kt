package org.synapseworks.pageharbor.image

import java.util.concurrent.CancellationException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

class BackgroundWhiteningSafetyTest {
    private val filters=DocumentFilter.entries.filter{it.whiteningRecipe!=null}
    @Test fun rowEngineManualOnceDeterminismAndBoundedIntensity() {
        val settings=ManualImageAdjustments(20,-25)
        for(c in BackgroundWhiteningCorpus.cases())for(f in filters) {
            val out=DocumentImageFilterEngine.apply(c.source,f)
            val manual=DocumentImageFilterEngine.apply(c.source,f,settings)
            assertArrayEquals(out.pixels.map{settings.apply(it)}.toIntArray(),manual.pixels)
            val streamed=IntArray(out.pixels.size);val buffers=Collections.newSetFromMap(IdentityHashMap<IntArray,Boolean>())
            DocumentImageFilterEngine.processSpatialRows(f,c.source.width,c.source.height,
                {y,row->buffers.add(row);c.source.pixels.copyInto(row,0,y*c.source.width,(y+1)*c.source.width)},
                {y,row->row.copyInto(streamed,y*c.source.width)})
            assertArrayEquals(out.pixels,streamed);assertTrue(buffers.size<=6)
            val strong=if(f.whiteningRecipe!!.paper==WhiteningPaper.COLOUR)DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1 else DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1
            val full=DocumentImageFilterEngine.apply(c.source,strong)
            for(i in out.pixels.indices)for(shift in listOf(0,8,16)) {
                val original=c.source.pixels[i] ushr shift and 255;val max=full.pixels[i] ushr shift and 255;val v=out.pixels[i] ushr shift and 255
                assertTrue(v in original..max)
            }
        }
    }
    @Test fun cancellationWhileAcceptingLastRowCannotReturnACompletedResult() {
        val c=BackgroundWhiteningCorpus.document("gray");var cancelled=false
        try {
            BackgroundWhiteningFilter.process(c.source.width,c.source.height,
                requireNotNull(DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1.whiteningRecipe),
                {y,row->c.source.pixels.copyInto(row,0,y*c.source.width,(y+1)*c.source.width)},
                {y,_->if(y==c.source.height-1)cancelled=true},
                {if(cancelled)throw CancellationException()})
            fail()
        }catch(_:CancellationException){}
        assertTrue(cancelled)
    }
    @Test fun cancellationInAllPassesAndReadWriteFailurePropagateWithoutCompletedOutput() {
        val c=BackgroundWhiteningCorpus.document("uneven")
        val recipe=requireNotNull(DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1.whiteningRecipe)
        val before=c.source.pixels.copyOf()
        for(pass in 0..2) {
            var reads=0;var writes=0
            try {
                BackgroundWhiteningFilter.process(c.source.width,c.source.height,recipe,
                    {y,row->reads++;c.source.pixels.copyInto(row,0,y*c.source.width,(y+1)*c.source.width)},
                    {_,_->writes++},
                    {if((pass==0&&reads==3)||(pass==1&&reads==c.source.height+3)||(pass==2&&writes==3))throw CancellationException()})
                fail()
            }catch(_:CancellationException){}
            assertEquals(if(pass==2)3 else 0,writes)
        }
        assertArrayEquals(before,c.source.pixels)
        for(failRead in listOf(true,false))try {
            BackgroundWhiteningFilter.process(64,64,recipe,{_,row->if(failRead)throw IOException()else row.fill(0xff808080.toInt())},{_,_->throw IOException()});fail()
        }catch(_:IOException){}
        for((w,h)in listOf(1 to 1,1 to 37,43 to 1))for(p in listOf(0,-1,0xffa0a0a0.toInt())) {
            val source=ArgbImage(w,h,IntArray(w*h){p})
            for(f in filters)assertArrayEquals(source.pixels,DocumentImageFilterEngine.apply(source,f).pixels)
        }
    }
}
