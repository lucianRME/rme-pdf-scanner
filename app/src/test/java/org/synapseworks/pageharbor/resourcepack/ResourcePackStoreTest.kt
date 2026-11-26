package org.synapseworks.pageharbor.resourcepack

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class ResourcePackStoreTest {
    private val hello="hello".toByteArray()
    private val hash="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    private fun meta(path:String="data/hello.txt", digest:String=hash, extra:String="", version:Int=1)="schema=1\nid=sample\nversion=$version\ncompatibility=rme-text-resource-v1\nprovenance=independent synthetic test\nlicense=CC0-1.0\nhardware=no inference claim\nfile=$path|5|$digest\n$extra"
    private fun zip(metadata:String=meta(), path:String="data/hello.txt", data:ByteArray=hello):ByteArray {val b=ByteArrayOutputStream();ZipOutputStream(b).use {z->for((name,bytes) in listOf("pack.properties" to metadata.toByteArray(),path to data)){z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}};return b.toByteArray()}
    private fun run(block:(ResourcePackStore,File)->Unit){val root=Files.createTempDirectory("pack-test").toFile();try{block(ResourcePackStore(root),root)}finally{root.deleteRecursively()}}
    private fun declined(block:()->Unit){try{block();fail("Expected refusal")}catch(_:IllegalArgumentException){}}
    @Test fun installReopenLeaseRemove()=run { s,r->val p=s.import(zip().inputStream());assertEquals("sample",p.id);assertEquals(p,ResourcePackStore(r).list().single());s.usePack("sample"){_,dir->assertEquals("hello",File(dir,"data/hello.txt").readText());assertFalse(s.remove("sample"));assertFalse(ResourcePackStore(r).remove("sample"))};assertTrue(s.remove("sample"));assertTrue(s.list().isEmpty()) }
    @Test fun malformedAndDuplicateMetadata()=run{s,_->for(m in listOf(meta(extra="schema=1\n"),meta().replace("version=1","version=no"),meta().replace("rme-text-resource-v1","future"),meta().replace("license=CC0-1.0\n","")))declined{s.import(zip(m).inputStream())};assertTrue(s.list().isEmpty())}
    @Test fun hashesPathsAndUndeclaredFiles()=run{s,_->declined{s.import(zip(meta(digest="0".repeat(64))).inputStream())};declined{s.import(zip(meta(path="../escape"),"../escape").inputStream())};declined{s.import(zip(path="data/other.txt").inputStream())};assertTrue(s.list().isEmpty())}
    @Test fun sizeAndExpansionLimits()=run{s,_->declined{s.import(zip(data=ByteArray(3*1024*1024)).inputStream())};declined{s.import(ByteArray((ResourcePackStore.MAX_ARCHIVE+1).toInt()).inputStream())};declined{s.import(zip(meta().replace("|5|","|999999999|")).inputStream())}}
    @Test fun cancellationAndInterruptedInputRollback()=run{s,r->var n=0;try{s.import(zip().inputStream()){if(++n==3)throw java.util.concurrent.CancellationException()};fail()}catch(_:java.util.concurrent.CancellationException){};try{s.import(object:InputStream(){override fun read():Int=throw IOException()});fail()}catch(_:IOException){};assertTrue(s.list().isEmpty());assertTrue(r.listFiles()!!.isEmpty())}
    @Test fun duplicateVersionConflictsPreserveOriginal()=run{s,_->val p=s.import(zip().inputStream());declined{s.import(zip().inputStream())};declined{s.import(zip(meta(version=2)).inputStream())};assertEquals(p,s.list().single())}
    @Test fun interruptedStagingRecoveredAndMissingActionable()=run{s,r->File(r,".incoming-crash").mkdir();File(r,".incoming-crash/partial").writeText("partial");val again=ResourcePackStore(r);assertTrue(again.list().isEmpty());assertFalse(File(r,".incoming-crash").exists());try{s.usePack("absent"){_,_->};fail()}catch(e:IllegalStateException){assertTrue(e.message!!.startsWith("Resource unavailable"))}}
    @Test fun storageCeilingAndTampering()=run{s,r->File(r,"owned-capacity-test").writeBytes(ByteArray(41*1024*1024));declined{s.import(zip().inputStream())};File(r,"owned-capacity-test").delete();s.import(zip().inputStream());File(r,"sample/data/hello.txt").writeText("wrong");declined{s.usePack("sample"){_,_->fail()}}}
    @Test fun lateCancellationDoesNotPublish()=run{s,r->
        try { s.import(zip().inputStream()) { if(r.walkTopDown().any { it.name=="pack.properties" }) throw java.util.concurrent.CancellationException() };fail() }
        catch(_:java.util.concurrent.CancellationException){}
        assertTrue(s.list().isEmpty());assertTrue(r.listFiles()!!.isEmpty())
    }
    @Test fun failedConsumerReleasesLease()=run{s,_->s.import(zip().inputStream());try{s.usePack("sample"){_,_->throw IOException()};fail()}catch(_:IOException){};assertTrue(s.remove("sample"))}

    @Test fun streamingUtf8PreservesBoundaryAndRejectsTruncation()=run{s,r->
        val valid=("a".repeat(8191)+"é").toByteArray(Charsets.UTF_8)
        fun archive(data:ByteArray):ByteArray{val digest=java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it)};return zip(meta(digest=digest).replace("|5|","|${data.size}|"),data=data)}
        s.import(archive(valid).inputStream());s.usePack("sample"){_,d->assertArrayEquals(valid,File(d,"data/hello.txt").readBytes())};assertTrue(s.remove("sample"))
        val invalid=ByteArray(8192){if(it==8191)0xc3.toByte() else 0x61.toByte()}
        try{s.import(archive(invalid).inputStream());fail("Malformed UTF-8 accepted")}catch(_:java.nio.charset.CharacterCodingException){}
        assertTrue(s.list().isEmpty());assertTrue(r.listFiles()!!.isEmpty())
    }

}
