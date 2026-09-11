package org.synapseworks.pageharbor.ui.home

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface ManagedDocumentPreviewState {
    data object Loading : ManagedDocumentPreviewState
    data object Unavailable : ManagedDocumentPreviewState
    data class Ready(val owner: OwnedPreviewBitmap) : ManagedDocumentPreviewState
}

/** Exactly-once ownership for one decoded or transformed preview bitmap. */
internal class OwnedPreviewBitmap(
    val bitmap: Bitmap,
    private val recycler: (Bitmap) -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) recycler(bitmap)
    }
}

/**
 * Owns the bitmap committed to Compose and any newly published bitmap awaiting its commit. The
 * slot's disposal callback exists before asynchronous decoding begins.
 */
internal class PreviewBitmapOwnerSlot {
    private var disposed = false
    private var committed: OwnedPreviewBitmap? = null
    private var pending: OwnedPreviewBitmap? = null

    @Synchronized
    fun publish(owner: OwnedPreviewBitmap): Boolean {
        if (disposed) {
            owner.release()
            return false
        }
        pending?.takeUnless { it === owner }?.release()
        pending = owner
        return true
    }

    @Synchronized
    fun commit(owner: OwnedPreviewBitmap?) {
        if (disposed) {
            owner?.release()
            return
        }
        if (pending === owner) {
            pending = null
        } else if (owner == null) {
            pending?.release()
            pending = null
        }
        committed?.takeUnless { it === owner }?.release()
        committed = owner
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        pending?.release()
        pending = null
        committed?.release()
        committed = null
    }
}

internal fun recyclePreviewBitmap(bitmap: Bitmap) {
    if (!bitmap.isRecycled) bitmap.recycle()
}

/**
 * Runs decode/transform work away from the main thread and transfers each bitmap through one
 * explicit owner. Atomic hand-off slots also recover results discarded by coroutine cancellation.
 */
@Composable
internal fun rememberManagedDocumentPreview(
    requestKey: Any?,
    isCurrent: () -> Boolean = { true },
    decode: () -> Bitmap?,
    transform: (Bitmap) -> Bitmap = { it },
    recycler: (Bitmap) -> Unit = ::recyclePreviewBitmap,
    onReadyPublishedForTest: (() -> Unit)? = null,
): State<ManagedDocumentPreviewState> {
    val state = remember { mutableStateOf<ManagedDocumentPreviewState>(ManagedDocumentPreviewState.Loading) }
    val currentCheck = rememberUpdatedState(isCurrent)
    val ownerSlot = remember { PreviewBitmapOwnerSlot() }

    DisposableEffect(ownerSlot) {
        onDispose(ownerSlot::dispose)
    }

    LaunchedEffect(requestKey) {
        state.value = ManagedDocumentPreviewState.Loading
        val crossingDispatcher = AtomicReference<OwnedPreviewBitmap?>()
        var owned: OwnedPreviewBitmap? = null
        try {
            val decoded = withContext(Dispatchers.IO) {
                decode()?.let { bitmap -> OwnedPreviewBitmap(bitmap, recycler) }
                    .also(crossingDispatcher::set)
            }
            crossingDispatcher.compareAndSet(decoded, null)
            owned = decoded
            currentCoroutineContext().ensureActive()
            if (!currentCheck.value()) return@LaunchedEffect
            if (decoded == null) {
                state.value = ManagedDocumentPreviewState.Unavailable
                return@LaunchedEffect
            }

            val transformed = withContext(Dispatchers.Default) {
                val output = transform(decoded.bitmap)
                if (output === decoded.bitmap) {
                    decoded
                } else {
                    OwnedPreviewBitmap(output, recycler).also(crossingDispatcher::set)
                }
            }
            if (transformed !== decoded) {
                crossingDispatcher.compareAndSet(transformed, null)
                decoded.release()
                owned = transformed
            }
            currentCoroutineContext().ensureActive()
            if (!currentCheck.value()) return@LaunchedEffect

            val readyOwner = requireNotNull(owned)
            if (ownerSlot.publish(readyOwner)) {
                state.value = ManagedDocumentPreviewState.Ready(readyOwner)
                owned = null
                onReadyPublishedForTest?.invoke()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            state.value = ManagedDocumentPreviewState.Unavailable
        } finally {
            crossingDispatcher.getAndSet(null)?.release()
            owned?.release()
        }
    }

    val displayedOwner = (state.value as? ManagedDocumentPreviewState.Ready)?.owner
    SideEffect {
        ownerSlot.commit(displayedOwner)
    }
    return state
}
