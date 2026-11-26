package org.synapseworks.pageharbor.ui.home

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.image.*

class ManualPreviewOwnershipTest {
    @get:Rule val compose=createComposeRule()
    @Test fun lateManualTransformCannotPublishAndEveryBitmapHasExactlyOneOwner() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val shown=mutableStateOf(true);val settings=mutableStateOf(ManualImageAdjustments(10))
        val allocated=Collections.synchronizedList(mutableListOf<Bitmap>())
        val recycled=Collections.synchronizedMap(IdentityHashMap<Bitmap,Int>())
        val observed=Collections.synchronizedList(mutableListOf<Int>())
        val last=AtomicInteger(-1)
        compose.setContent {
            if(shown.value) {
                val captured=settings.value
                val state by rememberManagedDocumentPreview(
                    requestKey=captured,isCurrent={captured==settings.value},
                    decode={Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff404040.toInt());allocated.add(this)}},
                    transform={source ->
                        check(Looper.myLooper()!=Looper.getMainLooper())
                        if(captured.brightness==10) withContext(NonCancellable) {entered.complete(Unit);release.await()}
                        val image=DocumentImageFilterEngine.apply(ArgbImage(2,2,IntArray(4){source.getPixel(0,0)}),DocumentFilter.ORIGINAL,captured)
                        Bitmap.createBitmap(image.pixels,2,2,Bitmap.Config.ARGB_8888).also {allocated.add(it)}
                    },
                    recycler={bitmap ->synchronized(recycled){recycled[bitmap]=(recycled[bitmap]?:0)+1};bitmap.recycle()},
                )
                val ready=state as? ManagedDocumentPreviewState.Ready
                if(ready!=null) {
                    SideEffect {val pixel=ready.owner.bitmap.getPixel(0,0) and 255;observed.add(pixel);last.set(pixel)}
                    Box(Modifier.testTag("m06-ready"))
                }
            }
        }
        try {
            compose.waitUntil(30_000){entered.isCompleted}
            compose.runOnIdle{settings.value=ManualImageAdjustments(20)}
            compose.waitUntil(30_000){last.get()==115}
            compose.onNodeWithTag("m06-ready").assertExists()
            release.complete(Unit)
            compose.waitUntil(30_000){synchronized(allocated){allocated.size==4}}
            compose.waitForIdle()
            assertTrue(synchronized(observed){observed.all{it==115}})
        } finally {
            release.complete(Unit)
            compose.runOnIdle{shown.value=false}
        }
        compose.waitUntil(30_000){synchronized(allocated){synchronized(recycled){allocated.all{recycled[it]==1}}}}
        assertEquals(4,allocated.size)
        assertTrue(allocated.all{it.isRecycled})
    }
}
