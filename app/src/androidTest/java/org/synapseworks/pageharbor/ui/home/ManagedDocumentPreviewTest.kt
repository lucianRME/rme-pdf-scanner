package org.synapseworks.pageharbor.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ManagedDocumentPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displayedBitmapLivesUntilThePreviewLeavesCompositionAndRecyclesOnce() {
        val bitmap = bitmap()
        val recycler = RecordingBitmapRecycler()
        val shown = mutableStateOf(true)

        composeRule.setContent {
            if (shown.value) PreviewHarness(0, { bitmap }, recycler = recycler::recycle)
        }
        awaitReady()
        composeRule.runOnIdle {
            assertFalse(bitmap.isRecycled)
            assertEquals(0, recycler.count(bitmap))
            shown.value = false
        }
        composeRule.waitForIdle()

        assertTrue(bitmap.isRecycled)
        assertEquals(1, recycler.count(bitmap))
    }

    @Test
    fun rapidRequestReplacementReleasesOnlyThePreviousDisplayedBitmap() {
        val first = bitmap()
        val second = bitmap()
        val recycler = RecordingBitmapRecycler()
        val request = mutableStateOf(0)

        composeRule.setContent {
            val current = request.value
            PreviewHarness(
                requestKey = current,
                decode = { if (current == 0) first else second },
                recycler = recycler::recycle,
            )
        }
        awaitReady()
        composeRule.runOnIdle { request.value = 1 }
        composeRule.waitUntil { recycler.count(first) == 1 }
        composeRule.waitForIdle()

        assertTrue(first.isRecycled)
        assertFalse(second.isRecycled)
        assertEquals(0, recycler.count(second))
    }

    @Test
    fun transformReplacementReleasesSourceButKeepsDisplayedOutputUntilCompositionLeaves() {
        val source = bitmap()
        val transformed = bitmap()
        val recycler = RecordingBitmapRecycler()
        val shown = mutableStateOf(true)

        composeRule.setContent {
            if (shown.value) {
                PreviewHarness(
                    requestKey = 0,
                    decode = { source },
                    transform = { transformed },
                    recycler = recycler::recycle,
                )
            }
        }
        awaitReady()

        assertTrue(source.isRecycled)
        assertEquals(1, recycler.count(source))
        assertFalse(transformed.isRecycled)
        assertEquals(0, recycler.count(transformed))

        composeRule.runOnIdle { shown.value = false }
        composeRule.waitForIdle()

        assertTrue(transformed.isRecycled)
        assertEquals(1, recycler.count(transformed))
    }

    @Test
    fun staleDecodeResultIsRecycledWithoutReplacingTheCurrentPreview() {
        val stale = bitmap()
        val current = bitmap()
        val recycler = RecordingBitmapRecycler()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = mutableStateOf(0)

        composeRule.setContent {
            val currentRequest = request.value
            PreviewHarness(
                requestKey = currentRequest,
                decode = {
                    if (currentRequest == 0) {
                        reached.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        stale
                    } else {
                        current
                    }
                },
                recycler = recycler::recycle,
            )
        }
        assertTrue(reached.await(10, TimeUnit.SECONDS))
        composeRule.runOnIdle { request.value = 1 }
        awaitReady()
        release.countDown()
        composeRule.waitUntil { recycler.count(stale) == 1 }

        assertTrue(stale.isRecycled)
        assertFalse(current.isRecycled)
    }

    @Test
    fun identityTransformAndTransformFailureHaveDeterministicOwnership() {
        val identity = bitmap()
        val failed = bitmap()
        val recycler = RecordingBitmapRecycler()
        val request = mutableStateOf(0)

        composeRule.setContent {
            val current = request.value
            PreviewHarness(
                requestKey = current,
                decode = { if (current == 0) identity else failed },
                transform = { bitmap ->
                    if (current == 0) bitmap else error("deterministic transform failure")
                },
                recycler = recycler::recycle,
            )
        }
        awaitReady()
        assertEquals(0, recycler.count(identity))

        composeRule.runOnIdle { request.value = 1 }
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag(UnavailableTag).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(1, recycler.count(identity))
        assertEquals(1, recycler.count(failed))
    }

    @Test
    fun removalAfterReadyPublicationButBeforeCommitRecyclesExactlyOnce() {
        val bitmap = bitmap()
        val recycler = RecordingBitmapRecycler()
        val shown = mutableStateOf(true)
        val published = CountDownLatch(1)
        val continueAfterRemoval = CountDownLatch(1)

        composeRule.setContent {
            if (shown.value) {
                PreviewHarness(
                    requestKey = 0,
                    decode = { bitmap },
                    recycler = recycler::recycle,
                    onReadyPublishedForTest = {
                        published.countDown()
                        check(continueAfterRemoval.await(10, TimeUnit.SECONDS))
                    },
                )
            }
        }

        assertTrue("Ready was not published", published.await(10, TimeUnit.SECONDS))
        Snapshot.withMutableSnapshot { shown.value = false }
        continueAfterRemoval.countDown()
        composeRule.waitUntil { recycler.count(bitmap) == 1 }
        composeRule.waitForIdle()

        assertTrue(bitmap.isRecycled)
        assertEquals(1, recycler.count(bitmap))
    }

    @Composable
    private fun PreviewHarness(
        requestKey: Int,
        decode: () -> Bitmap?,
        transform: (Bitmap) -> Bitmap = { it },
        recycler: (Bitmap) -> Unit,
        onReadyPublishedForTest: (() -> Unit)? = null,
    ) {
        val state by rememberManagedDocumentPreview(
            requestKey = requestKey,
            decode = decode,
            transform = transform,
            recycler = recycler,
            onReadyPublishedForTest = onReadyPublishedForTest,
        )
        when (state) {
            is ManagedDocumentPreviewState.Ready -> Box(Modifier.testTag(ReadyTag))
            ManagedDocumentPreviewState.Unavailable -> Box(Modifier.testTag(UnavailableTag))
            ManagedDocumentPreviewState.Loading -> Unit
        }
    }

    private fun awaitReady() {
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag(ReadyTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

    private class RecordingBitmapRecycler {
        private val counts = IdentityHashMap<Bitmap, Int>()

        @Synchronized
        fun recycle(bitmap: Bitmap) {
            counts[bitmap] = (counts[bitmap] ?: 0) + 1
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        @Synchronized
        fun count(bitmap: Bitmap): Int = counts[bitmap] ?: 0
    }

    private companion object {
        const val ReadyTag = "managed-preview-ready"
        const val UnavailableTag = "managed-preview-unavailable"
    }
}
