package org.synapseworks.pageharbor.qr

import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class QrFrameProcessorTest {
    private class Frame(val before:()->Unit={}):QrFrame {
        val closed=AtomicInteger()
        override val buffer get():ByteBuffer {before();return ByteBuffer.wrap(ByteArray(64){(-1).toByte()})}
        override val width=8;override val height=8;override val rowStride=8;override val pixelStride=1
        override val left=0;override val top=0;override val cropWidth=8;override val cropHeight=8;override val rotation=0
        override fun close(){closed.incrementAndGet()}
    }
    @Test fun busyRejectedFailedCancelledAndSuccessfulCompletionPathsCloseEveryFrameExactlyOnce(){
        val c=QrController();c.open();val mutex=Mutex();val processor=QrFrameProcessor(mutex,c,c::completeFrame)
        fun process(f:QrFrame){val s=c.state;processor.analyze(f,s?.owner?:"closed",s?.generation?:-1)}
        mutex.tryLock();val busy=Frame();process(busy);mutex.unlock();assertEquals(1,busy.closed.get())
        val bad=Frame{throw IllegalStateException("Synthetic frame failure")};process(bad);assertEquals(1,bad.closed.get());assertNotNull(c.frame());c.rescan()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val slow=Frame{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val worker=Executors.newSingleThreadExecutor()
        try {
            val job=worker.submit{process(slow)};assertTrue(entered.await(5,TimeUnit.SECONDS))
            val dropped=Frame();process(dropped);assertEquals(1,dropped.closed.get())
            c.close();release.countDown();job.get(5,TimeUnit.SECONDS);assertEquals(1,slow.closed.get());assertNull(c.state)
        }finally{release.countDown();worker.shutdownNow()}
        val ignored=Frame();process(ignored);assertEquals(1,ignored.closed.get())
        c.open();val producer=c.state!!;c.rescan();val stale=Frame{throw IllegalStateException("Old frame must not be read")}
        processor.analyze(stale,producer.owner,producer.generation);assertEquals(1,stale.closed.get());assertNull(c.state!!.result)
        val completed=Frame();process(completed);assertEquals(1,completed.closed.get());assertNotNull(c.frame())
    }
    @Test fun callbackFailureStillReleasesMutexAndClosesFrame(){
        val c=QrController();c.open();val mutex=Mutex();val frame=Frame()
        try{QrFrameProcessor(mutex,c,{_,_->throw IllegalStateException("Synthetic callback failure")}).analyze(frame,c.state!!.owner,c.state!!.generation);fail()}catch(_:IllegalStateException){}
        assertEquals(1,frame.closed.get());assertFalse(mutex.isLocked)
    }
}
