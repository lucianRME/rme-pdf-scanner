package org.synapseworks.pageharbor.resourcepack
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files

/** Optional pinned resource fixture, independently compared to every original record. */
class CmuRealResourceTest {
 @Test fun everyPinnedRecordRemainsQueryable(){
  val source=System.getenv("RME_CMU_SOURCE_FIXTURES");assumeTrue("Optional pinned source fixture not provisioned",source!=null)
  val dir=File(source!!);val root=Files.createTempDirectory("cmu-real-test").toFile()
  try {
   val store=ResourcePackStore(root);File(dir,"pack/cmudict-en-us.zip").inputStream().use{store.import(it)}
   val expected=File(dir,"cmudict.dict").readLines(Charsets.US_ASCII).groupBy{it.substringBefore(' ').replace(Regex("\\([0-9]+\\)$"),"")}
   assertEquals(135166,expected.values.sumOf{it.size})
   store.usePack(CmuLexicon.PACK_ID){p,d->CmuLexicon.load(p,d).use { lex->expected.forEach{(word,rows)->assertTrue("Pinned record mismatch",rows.sorted()==lex.lookup(word))} }}
   var checkpoints=0
   try { store.usePack(CmuLexicon.PACK_ID){p,d->CmuLexicon.load(p,d){if(++checkpoints==128)throw java.util.concurrent.CancellationException()}.close()};fail("Cancellation missing") }catch(_:java.util.concurrent.CancellationException){}
   assertEquals(128,checkpoints);assertTrue(ResourcePackStore(root).remove(CmuLexicon.PACK_ID))
   File(dir,"pack/cmudict-en-us.zip").inputStream().use{store.import(it)}
   val installed=store.list().single();File(root,"${CmuLexicon.PACK_ID}/data/license.txt").writeText("invalid")
   try { CmuLexicon.load(installed,File(root,CmuLexicon.PACK_ID));fail("License corruption accepted") }catch(_:IllegalArgumentException){}
   assertTrue(store.remove(CmuLexicon.PACK_ID))
  }finally{root.deleteRecursively()}
 }
}
