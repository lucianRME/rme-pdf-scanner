package org.synapseworks.pageharbor.image

import org.junit.Assert.*
import org.junit.Test

class BitmapDisplayPolicyTest {
    @Test fun invalidAndUnrepresentableRequestsDoNotAllocate() {
        assertNull(bitmapDisplaySize(0,100))
        assertNull(displayBitmapSampleSize(0,100,BitmapDisplaySize(100,100)))
        assertNull(displayBitmapSampleSize(100,100,BitmapDisplaySize(0,100)))
        assertNull(displayBitmapSampleSize(100,100,BitmapDisplaySize(100,100),3))
        assertNull(displayBitmapSampleSize(Int.MAX_VALUE,Int.MAX_VALUE,BitmapDisplaySize(1,1),4))
    }
    @Test fun measuredSizeIsQuantizedAndInspectionRemainsBounded() {
        assertEquals(BitmapDisplaySize(192,192),bitmapDisplaySize(144,144))
        assertEquals(bitmapDisplaySize(145,145),bitmapDisplaySize(150,150))
        assertEquals(BitmapDisplaySize(1600,1600),bitmapDisplaySize(Int.MAX_VALUE,Int.MAX_VALUE,4))
        assertEquals(BitmapDisplaySize(512,512),bitmapDisplaySize(128,128,4))
    }
    @Test fun photographAndThumbnailSamplingIsResolutionAware() {
        assertEquals(4,displayBitmapSampleSize(480,360,BitmapDisplaySize(144,144)))
        assertEquals(32,displayBitmapSampleSize(4032,3024,BitmapDisplaySize(144,144)))
        assertEquals(64,displayBitmapSampleSize(8000,6000,BitmapDisplaySize(144,144)))
        assertEquals(8,displayBitmapSampleSize(4032,3024,BitmapDisplaySize(600,800)))
        assertEquals(4,displayBitmapSampleSize(4032,3024,BitmapDisplaySize(1600,1600)))
    }
    @Test fun lowBitmapBudgetAndExtremeDimensionsStayWithinBounds() {
        assertEquals(32,displayBitmapSampleSize(4032,3024,BitmapDisplaySize(1600,1600),65536))
        val sample=requireNotNull(displayBitmapSampleSize(Int.MAX_VALUE,1,BitmapDisplaySize(1600,1600)))
        assertTrue(sample>0 && sample and (sample-1)==0)
        assertTrue((Int.MAX_VALUE.toLong()+sample-1)/sample<=1600)
    }
}
