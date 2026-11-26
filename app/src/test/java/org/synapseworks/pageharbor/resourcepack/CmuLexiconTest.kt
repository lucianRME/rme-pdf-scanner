package org.synapseworks.pageharbor.resourcepack
import org.junit.Assert.*
import org.junit.Test
class CmuLexiconTest {
 @Test fun caseVariantsAndAbsent(){CmuLexicon.index("z Z\nhello HH AH0\nhello(2) HH EH0\na AH0\n".toByteArray()).use {assertEquals(listOf("hello HH AH0","hello(2) HH EH0"),it.lookup("HELLO"));assertTrue(it.lookup("absent").isEmpty())}}
 @Test fun malformed(){for(x in listOf("no newline","bad\n","a \n","a X\u0000\n")){try{CmuLexicon.index(x.toByteArray());fail()}catch(_:IllegalArgumentException){}}}
 @Test fun cancel(){try{CmuLexicon.index("hello HH\n".toByteArray()){throw java.util.concurrent.CancellationException()};fail()}catch(_:java.util.concurrent.CancellationException){}}
 @Test fun boundsAndClose(){val x=CmuLexicon.index("hello HH\n".toByteArray());try{x.lookup("a".repeat(65));fail()}catch(_:IllegalArgumentException){};x.close();try{x.lookup("hello");fail()}catch(_:IllegalStateException){}}
}
