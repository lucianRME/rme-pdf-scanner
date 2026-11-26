package org.synapseworks.pageharbor.translation
import org.junit.Test
import org.junit.Assert.*
class TranslationPlanTest {
 @Test fun utf8ByteLimitNotCharacterLimit(){try{TranslationUtf8.encode("ă".repeat(16385));fail()}catch(_:IllegalArgumentException){};assertEquals(32768,TranslationUtf8.encode("ă".repeat(16384)).size)}
 @Test fun utf8RoundTripWithoutBom(){val s="țară școală μ 😀\r\n";val b=TranslationUtf8.encode(s);assertEquals(s,String(b,Charsets.UTF_8));assertFalse(b.take(3)==listOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()))}
 @Test fun utf8RejectsMalformedDraft(){try{TranslationUtf8.encode("bad\uD800");fail()}catch(_:java.nio.charset.CharacterCodingException){}}
 @Test fun exactRangesParagraphsAndPages(){val input=listOf(TranslationPage("p2","Hello world.\r\n\r\nAna has 12 books.  "),TranslationPage("p1","No."));val p=TranslationPlan.plan(input);input.forEachIndexed{i,v->val parts=p.filter{it.page==i};assertEquals(v.text,parts.joinToString(""){it.source});assertTrue(parts.all{it.pageId==v.id&&v.text.substring(it.start,it.end)==it.source});assertEquals(0,parts.first().start);assertEquals(v.text.length,parts.last().end)}}
 @Test fun unicodeNeverSplitsSurrogates(){val s=("word 😀 țară ").repeat(25);val p=TranslationPlan.plan(listOf(TranslationPage("p",s)));assertEquals(s,p.joinToString(""){it.source});assertTrue(p.filter{!it.separator}.all{it.source.toByteArray().size<=256});assertTrue(p.all{!it.source.startsWith('\uDE00')&&!it.source.endsWith('\uD83D')})}
 @Test fun adaptiveSplitRetainsOffsetsAndIdentity(){val s="abcdefghijk țară xyz";val p=TranslationPiece(2,50,50+s.length,s,false,"page-id");val a=TranslationPlan.split(p);assertEquals(s,a.joinToString(""){it.source});assertTrue(a.all{it.page==2&&it.pageId=="page-id"});assertEquals(50,a.first().start);assertEquals(50+s.length,a.last().end)}
 @Test fun excessivePagesDeclined(){try{TranslationPlan.plan(List(4){TranslationPage("p","word")});fail()}catch(_:IllegalArgumentException){}}
 @Test fun excessiveBytesDeclined(){try{TranslationPlan.plan(listOf(TranslationPage("p","x".repeat(8193))));fail()}catch(_:IllegalArgumentException){}}
 @Test fun excessiveSegmentsDeclined(){try{TranslationPlan.plan(listOf(TranslationPage("p","No. ".repeat(33))));fail()}catch(_:IllegalArgumentException){}}
 @Test fun malformedUnicodeDeclined(){try{TranslationPlan.plan(listOf(TranslationPage("p","bad\uD800")));fail()}catch(_:IllegalArgumentException){}}
 @Test fun identityTranslationSourceIsNotDropped(){val p=TranslationPlan.plan(listOf(TranslationPage("p","Ana 2026-11-13.")));assertEquals("Ana 2026-11-13.",p.joinToString(""){it.source})}
}
