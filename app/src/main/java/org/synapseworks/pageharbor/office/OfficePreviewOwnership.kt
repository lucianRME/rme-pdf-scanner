package org.synapseworks.pageharbor.office

import android.graphics.Bitmap
import android.os.Looper
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.ui.home.OwnedPreviewBitmap
import org.synapseworks.pageharbor.ui.home.recyclePreviewBitmap

/** Reuse RME's two-frame graphics-layer retirement; never allocate a second
 * managed page while a previous displayed page still owns its pixels.
 */
internal object OfficePreviewOwnership {
    private val retiring = mutableListOf<Bitmap>() // main-thread ownership only
    fun own(bitmap: Bitmap): OwnedPreviewBitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        return OwnedPreviewBitmap(bitmap) {
            check(Looper.myLooper() == Looper.getMainLooper())
            retiring += it
            recyclePreviewBitmap(it)
        }
    }
    suspend fun awaitRetirement() = withContext(Dispatchers.Main.immediate) {
        try {
            withTimeout(2000) {
                while (true) {
                    retiring.removeAll { it.isRecycled }
                    if (retiring.isEmpty()) break
                    delay(16)
                }
            }
        } catch (_: TimeoutCancellationException) { throw OfficeFailure("PREVIEW_RELEASE_PENDING") }
    }
}
