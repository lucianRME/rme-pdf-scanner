package org.synapseworks.pageharbor.translation
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
object TranslationUtf8 {
 fun encode(text:String):ByteArray {require(text.length<=32768);val b=Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));require(b.remaining()<=32768);return ByteArray(b.remaining()).also{b.get(it)}}
}
