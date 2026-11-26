package org.synapseworks.pageharbor.document.capture

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata

class ContinuousCaptureStateTest {
    private fun page()=CapturedPage(File("synthetic"),DocumentImageMetadata(3,2,1))
    @Test fun modeSwitchBeforeCapturePinsTheTicketAndKeepsBookSinglePage() {
        val controller = ContinuousCaptureController()
        assertTrue(controller.open(5, singlePage = true))
        assertEquals(CaptureMode.DOCUMENT, controller.state!!.mode)
        controller.selectMode(CaptureMode.BATCH)
        val failed = controller.request()!!
        assertFalse(failed.singlePage)
        controller.selectMode(CaptureMode.BOOK)
        assertEquals(CaptureMode.BATCH, controller.state!!.mode)
        assertEquals(CaptureDisposition.DISCARD, controller.complete(failed, null))
        controller.selectMode(CaptureMode.BOOK)
        val spread = controller.request()!!
        assertTrue(spread.singlePage)
        val original = page()
        controller.complete(spread, original)
        assertEquals(CapturePhase.REVIEW, controller.state!!.phase)
        controller.selectMode(CaptureMode.DOCUMENT)
        controller.replace(0)
        controller.selectMode(CaptureMode.BATCH)
        assertEquals(CaptureMode.BOOK, controller.state!!.mode)
        controller.resume()
        controller.complete(controller.request()!!, null)
        assertSame(original, controller.state!!.pages.single())
        controller.cancel()
        assertTrue(controller.open(6, singlePage = true))
        assertEquals(CaptureMode.DOCUMENT, controller.state!!.mode)
    }

    @Test fun stagedBatchRemainsBatchUntilItIsUsedOrCancelled() {
        val controller = ContinuousCaptureController()
        controller.open(8, singlePage = true)
        controller.selectMode(CaptureMode.BATCH)
        val first = page()
        controller.complete(controller.request()!!, first)
        controller.selectMode(CaptureMode.DOCUMENT)
        assertEquals(CaptureMode.BATCH, controller.state!!.mode)
        assertEquals(CapturePhase.CAMERA, controller.state!!.phase)
        val second = page()
        controller.complete(controller.request()!!, second)
        controller.finish()
        assertEquals(listOf(first, second), controller.release())
    }

    @Test fun serialCaptureHasNoQueueAndDuplicateCallbacksCannotPublishTwice() {
        val c=ContinuousCaptureController();assertTrue(c.open(7));val t=c.request()!!
        repeat(30){assertNull(c.request())};assertTrue(c.state!!.busy)
        assertEquals(CaptureDisposition.ACCEPT,c.complete(t,page()));assertEquals(1,c.state!!.pages.size)
        assertEquals(CaptureDisposition.IGNORE,c.complete(t,page()));assertEquals(1,c.state!!.pages.size)
    }
    @Test fun finishWaitsForTheCurrentTicketAndPublishesOnlyAfterReview() {
        val c=ContinuousCaptureController();c.open(7);val t=c.request()!!;c.finish()
        assertEquals(CapturePhase.CAMERA,c.state!!.phase);assertNull(c.request())
        c.complete(t,page());assertEquals(CapturePhase.REVIEW,c.state!!.phase)
        assertEquals(1,c.release().size);assertNull(c.state);assertTrue(c.canOpen)
    }
    @Test fun failureAndBackgroundingKeepAcceptedPagesAndDiscardOnlyThePendingTicket() {
        val c=ContinuousCaptureController();c.open(7);c.complete(c.request()!!,page())
        c.complete(c.request()!!,null);assertEquals(1,c.state!!.pages.size)
        val pending=c.request()!!;c.pause();c.resume();assertNull(c.request())
        assertEquals(CaptureDisposition.DISCARD,c.complete(pending,page()));assertEquals(1,c.state!!.pages.size)
        assertNotNull(c.request())
    }
    @Test fun cancelKeepsDeletionOwnershipUntilLateCallbackAndCannotRetargetNewOwner() {
        val c=ContinuousCaptureController();c.open(7);val owner=c.state!!.owner;val t=c.request()!!
        c.cancel();assertFalse(c.canOpen);assertFalse(c.open(9))
        assertEquals(CaptureDisposition.DISCARD,c.complete(t,page()));assertTrue(c.open(9));assertNotEquals(owner,c.state!!.owner)
        assertEquals(CaptureDisposition.IGNORE,c.complete(t,page()));assertTrue(c.state!!.pages.isEmpty())
    }
    @Test fun intentionallyRepeatedPagesAreNotSuppressedAndRetakeKeepsTheirOrder() {
        val c=ContinuousCaptureController();c.open(7);val p=page()
        repeat(3){c.complete(c.request()!!,p)};c.finish();c.retake()
        assertTrue(c.state!!.paused);assertEquals(listOf(p,p,p),c.state!!.pages);c.resume();assertNotNull(c.request())
    }
    @Test fun fileStoreOwnsOnlyFlatPrivateFilesAndPurgesColdOrphansOnce() {
        val root=Files.createTempDirectory("m10-store").toFile()
        try {
            val old=File(root,UUID.randomUUID().toString()).apply{mkdirs()};File(old,"orphan").writeText("synthetic")
            val unrelated=File(root,"unassigned").apply{mkdirs()};val keep=File(unrelated,"keep").apply{writeText("synthetic")}
            val store=ContinuousCaptureStore(root);store.cleanupOrphans();assertFalse(old.exists());assertTrue(keep.exists())
            val ticket=CaptureTicket(UUID.randomUUID().toString(),0,1);val pending=store.create(ticket);pending.writeText("synthetic")
            store.cleanupOrphans();assertTrue(pending.exists())
            val ready=store.ready(pending);assertFalse(pending.exists());assertTrue(ready.exists());store.delete(ready);assertFalse(ready.exists());assertTrue(keep.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun symlinksAndForeignPathsAreNeverCleanedAsOwnedFiles() {
        val root=Files.createTempDirectory("m10-owned").toFile();val other=Files.createTempDirectory("m10-external").toFile()
        try {
            val keep=File(other,"keep").apply{writeText("synthetic")};val link=File(root,UUID.randomUUID().toString())
            Files.createSymbolicLink(link.toPath(),other.toPath());val store=ContinuousCaptureStore(root);store.cleanupOrphans();assertTrue(keep.exists())
            try{store.delete(keep);fail()}catch(_:Exception){}
            assertTrue(keep.exists());Files.delete(link.toPath())
        }finally{root.deleteRecursively();other.deleteRecursively()}
    }

    @Test
    fun reviewCanReorderAndReplaceWithoutDroppingTheOriginalOnFailure() {
        val capture = ContinuousCaptureController()
        assertTrue(capture.open(8))
        val first = page()
        val second = page()
        val newer = page()
        capture.complete(capture.request()!!, first)
        capture.complete(capture.request()!!, second)
        capture.finish()

        assertTrue(capture.move(0, 1))
        assertSame(second, capture.state!!.pages.first())
        assertFalse(capture.move(0, -1))
        capture.replace(1)
        capture.resume()
        assertFalse(capture.move(0, 1))
        assertEquals(CaptureDisposition.DISCARD, capture.complete(capture.request()!!, null))
        assertSame(first, capture.state!!.pages.last())
        capture.finish()
        assertNull(capture.state!!.replacing)
        assertSame(first, capture.state!!.pages.last())

        capture.replace(1)
        capture.resume()
        assertEquals(CaptureDisposition.ACCEPT, capture.complete(capture.request()!!, newer))
        assertEquals(CapturePhase.REVIEW, capture.state!!.phase)
        assertSame(second, capture.state!!.pages.first())
        assertSame(newer, capture.state!!.pages.last())
        assertEquals(2, capture.release().size)
    }


    @Test fun indexedReorderNeedsReviewAndPreservesOtherPages() {
        val controller = ContinuousCaptureController()
        assertTrue(controller.open(12))
        val input = (1..50).map { page() }
        for (page in input) controller.complete(controller.request()!!, page)
        assertFalse(controller.moveTo(49, 0))
        controller.finish()
        assertFalse(controller.moveTo(0, 50))
        assertTrue(controller.moveTo(49, 0))
        val reviewed = controller.release()
        assertSame(input.last(), reviewed.first())
        for (index in 0 until 49) assertSame(input[index], reviewed[index + 1])
    }

}
