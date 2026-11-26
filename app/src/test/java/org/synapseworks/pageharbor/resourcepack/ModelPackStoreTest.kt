package org.synapseworks.pageharbor.resourcepack

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ModelPackStoreTest {
    private fun root()=Files.createTempDirectory("owned-model-test").toFile()
    @Test fun unknownArchiveLeavesNoPublication(){val r=root();try{val s=ModelPackStore(r);try{s.import("not a model ZIP".byteInputStream());fail()}catch(_:IllegalArgumentException){};assertFalse(s.installed());assertTrue(r.listFiles()!!.isEmpty())}finally{r.deleteRecursively()}}
    @Test fun cancelledCopyCleansStaging(){val r=root();try{val s=ModelPackStore(r);try{s.import(byteArrayOf(1).inputStream()){throw CancellationException()};fail()}catch(_:CancellationException){};assertTrue(r.listFiles()!!.isEmpty())}finally{r.deleteRecursively()}}
    @Test fun ownedRestartCleansStaging(){val r=root();try{File(r,".incoming-owned").mkdir();File(r,".incoming-owned/a").writeText("synthetic");ModelPackStore(r);assertTrue(r.listFiles()!!.isEmpty())}finally{r.deleteRecursively()}}
    @Test fun exactRealPackLeasesExceptionsAndDuplicate(){val fixture=System.getenv("RME_M36_MODEL_PACK");org.junit.Assume.assumeNotNull(fixture);val r=root();try{
        val s=ModelPackStore(r);File(checkNotNull(fixture)).inputStream().use{s.import(it)};assertTrue(s.installed());val other=ModelPackStore(r)
        val expected=java.io.IOException("intentional")
        try{s.usePack{assertFalse(other.remove());throw expected};fail()}catch(e:java.io.IOException){assertSame(expected,e)}
        try{File(checkNotNull(fixture)).inputStream().use{s.import(it)};fail()}catch(_:IllegalStateException){}
        assertTrue(s.installed());s.usePack{assertTrue(File(it,"vocab.json").isFile)};assertTrue(other.remove());assertFalse(s.installed())
    }finally{r.deleteRecursively()}}
    @Test fun cancellationAfterCopiedChunksRollsBack(){val r=root();try{val s=ModelPackStore(r);var calls=0;try{s.import(java.io.ByteArrayInputStream(ByteArray(200000))){calls++;if(calls==4)throw CancellationException()};fail()}catch(_:CancellationException){};assertEquals(4,calls);assertTrue(r.listFiles()!!.isEmpty())}finally{r.deleteRecursively()}}
    @Test fun unsafeUnknownZipRefusedBeforePublication(){val r=root();try{val bytes=java.io.ByteArrayOutputStream();java.util.zip.ZipOutputStream(bytes).use{it.putNextEntry(java.util.zip.ZipEntry("../escape"));it.write(byteArrayOf(1));it.closeEntry()};val s=ModelPackStore(r);try{s.import(bytes.toByteArray().inputStream());fail()}catch(_:IllegalArgumentException){};assertTrue(r.listFiles()!!.isEmpty())}finally{r.deleteRecursively()}}
    @Test fun excessiveInputStopsAtArchiveBound(){val r=root();try{val s=ModelPackStore(r);var read=0L;val input=object:java.io.InputStream(){override fun read():Int=0;override fun read(b:ByteArray,off:Int,len:Int):Int{java.util.Arrays.fill(b,off,off+len,0.toByte());read+=len;return len}};try{s.import(input);fail()}catch(_:IllegalArgumentException){};assertTrue(read>ModelPackStore.ARCHIVE_LIMIT && read<=ModelPackStore.ARCHIVE_LIMIT+32768);assertTrue(r.listFiles()!!.isEmpty())}finally{r.deleteRecursively()}}
    @Test fun interruptedExpansionPreservesSourceAndRollsBack(){val fixture=System.getenv("RME_M36_MODEL_PACK");org.junit.Assume.assumeNotNull(fixture);val r=root();try{val s=ModelPackStore(r);val expected=java.io.IOException("intentional");try{File(checkNotNull(fixture)).inputStream().use{input->s.import(input){if(r.walkTopDown().any{it.name=="encoder_model_quantized.onnx" && it.length()>0})throw expected}};fail()}catch(e:java.io.IOException){assertSame(expected,e)};assertFalse(s.installed());assertTrue(r.listFiles()!!.isEmpty());assertEquals(ModelPackStore.ARCHIVE_SHA,ModelPackStore.sha(File(checkNotNull(fixture))))}finally{r.deleteRecursively()}}
    @Test fun corruptInstalledResourceRefused(){val fixture=System.getenv("RME_M36_MODEL_PACK");org.junit.Assume.assumeNotNull(fixture);val r=root();try{val s=ModelPackStore(r);File(checkNotNull(fixture)).inputStream().use{s.import(it)};File(r,"${ModelPackStore.ID}/vocab.json").appendText("x");try{s.usePack{fail()};fail()}catch(_:IllegalArgumentException){};assertTrue(s.remove())}finally{r.deleteRecursively()}}
}
