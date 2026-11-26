package org.synapseworks.pageharbor.forms

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

class FormIoInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir:File
    @Before fun setup(){dir=File(app.cacheDir,"m23-io-${java.util.UUID.randomUUID()}").apply{mkdirs()}}
    @After fun cleanup()=runBlocking{FormWork.run{};assertTrue(dir.deleteRecursively())}
    private fun file(name:String,bytes:ByteArray="SYNTHETIC ORIGINAL".toByteArray())=File(dir,name).apply{writeBytes(bytes)}
    private fun capture(f:File)=FormHandle.capture(app,Uri.fromFile(f)){}
    private fun fd(f:File)=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_WRITE)
    private fun failWith(reason:FormFailure,block:()->Unit){try{block();fail()}catch(e:FormException){assertEquals(reason,e.reason)}}
    @Test fun aliasesAndExistingDestinationAreRejectedWithoutTruncation()=runBlocking{FormWork.run{
        val original=file("original");val before=original.readBytes();val handle=capture(original)
        try{val out=handle.output().apply{writeText("COPY")};var opens=0
            failWith(FormFailure.DESTINATION){FormDestination.write(handle,out,handle.original,{opens++;fd(original)}){}};assertEquals(0,opens)
            failWith(FormFailure.DESTINATION){FormDestination.write(handle,out,Uri.parse("content://different/reference"),{opens++;fd(original)}){}};assertEquals(1,opens);assertArrayEquals(before,original.readBytes())
            val existing=file("existing");failWith(FormFailure.DESTINATION){FormDestination.write(handle,out,Uri.fromFile(existing),{fd(existing)}){}};assertArrayEquals(before,existing.readBytes())
        }finally{handle.close()}
    }}
    @Test fun emptySeparateRegularDestinationGetsExactCopyAndOriginalStaysExact()=runBlocking{FormWork.run{
        val original=file("original");val before=original.readBytes();val h=capture(original)
        try{val out=h.output().apply{writeText("SYNTHETIC COPY")};val dest=file("new",byteArrayOf());assertTrue(FormDestination.write(h,out,Uri.fromFile(dest),{fd(dest)}){});assertArrayEquals(out.readBytes(),dest.readBytes());assertArrayEquals(before,original.readBytes())}finally{h.close()}
    }}
    @Test fun changedSourceAndPreWriteCancellationNeverOpenTarget()=runBlocking{FormWork.run{
        val original=file("original");val h=capture(original);var opens=0
        try{val out=h.output().apply{writeText("COPY")};original.appendText("changed");failWith(FormFailure.SOURCE_CHANGED){FormDestination.write(h,out,Uri.parse("content://target/1"),{opens++;null}){}};assertEquals(0,opens)
            try{FormDestination.write(h,out,Uri.parse("content://target/1"),{opens++;null}){throw CancellationException()};fail()}catch(_:CancellationException){};assertEquals(0,opens)
        }finally{h.close()}
    }}
    @Test fun midWriteCancellationAndFailureLeaveOriginalAndReleaseDescriptors()=runBlocking{FormWork.run{
        val original=file("original");val bytes=original.readBytes();val h=capture(original)
        try{val out=h.output().apply{writeBytes(ByteArray(40000){7})};val dest=file("partial",byteArrayOf());var opened=false
            try{FormDestination.write(h,out,Uri.fromFile(dest),{opened=true;fd(dest)}){if(opened&&dest.length()>0)throw CancellationException()};fail()}catch(_:CancellationException){}
            assertTrue(dest.length() in 1 until out.length());assertArrayEquals(bytes,original.readBytes())
            try{FormDestination.write(h,out,Uri.parse("content://target/fail"),{throw java.io.IOException("Synthetic failure")}){};fail()}catch(_:java.io.IOException){};assertArrayEquals(bytes,original.readBytes())
        }finally{h.close()}
    }}
    @Test fun atomicReplacementOfSourceReferenceCannotSaveAnOlderSnapshot()=runBlocking{FormWork.run{
        val original=file("original");val bytes=original.readBytes();val h=capture(original);var opens=0
        try{val out=h.output().apply{writeText("COPY")};val retired=File(dir,"retired");assertTrue(original.renameTo(retired));original.writeText("REPLACEMENT")
            failWith(FormFailure.SOURCE_CHANGED){FormDestination.write(h,out,Uri.parse("content://target/alias"),{opens++;fd(original)}){}}
            assertEquals(0,opens);assertArrayEquals(bytes,retired.readBytes());assertEquals("REPLACEMENT",original.readText())
            assertTrue(original.delete());assertTrue(retired.renameTo(original))
            failWith(FormFailure.SOURCE_CHANGED){FormDestination.write(h,out,Uri.parse("content://target/racing-alias"),{opens++;assertTrue(original.renameTo(retired));original.writeBytes(byteArrayOf());fd(original)}){}}
            assertEquals(1,opens);assertEquals(0L,original.length());assertArrayEquals(bytes,retired.readBytes())
        }finally{h.close()}
    }}
    @Test fun nonRegularDescriptorsAndOversizeSourcesFailClosed()=runBlocking{FormWork.run{
        val original=file("original");val h=capture(original)
        try{val out=h.output().apply{writeText("COPY")};val pipe=ParcelFileDescriptor.createPipe();try{failWith(FormFailure.DESTINATION){FormDestination.write(h,out,Uri.parse("content://target/pipe"),{pipe[1]}){}}}finally{pipe.forEach{runCatching{it.close()}}}}finally{h.close()}
        val huge=file("huge",byteArrayOf());java.io.RandomAccessFile(huge,"rw").use{it.setLength(FormLimits.INPUT.toLong()+1)};failWith(FormFailure.LIMIT){capture(huge)}
    }}
    @Test fun leaseRetainsWorkspaceUntilPhysicalWorkerAndCleanupFinish()=runBlocking{
        lateinit var h:FormHandle;lateinit var lease:AutoCloseable
        FormWork.run{h=capture(file("original"));lease=h.lease()!!;h.close();assertTrue(h.dir.exists());failWith(FormFailure.LIMIT){capture(file("other"))}}
        assertTrue(h.dir.exists());lease.close();FormWork.run{};assertFalse(h.dir.exists());FormWork.run{val next=capture(file("next"));next.close()};FormWork.run{}
    }
    @Test fun failedCleanupCannotPublishProviderExceptionsOrBlockTheNextCleanup()=runBlocking{
        val complete=java.util.concurrent.atomic.AtomicBoolean()
        FormWork.later{throw java.io.IOException("M23_SYNTHETIC_PRIVATE_CLEANUP")}
        FormWork.later{complete.set(true)};FormWork.run{};assertTrue(complete.get())
    }
    @Test fun unknownProviderCannotBeSelectedForProductionWrites(){assertFalse(FormDestination.supports(app,Uri.parse("content://unknown.provider/file")));assertFalse(FormDestination.supports(app,Uri.parse("file:///new.pdf")))}
}
