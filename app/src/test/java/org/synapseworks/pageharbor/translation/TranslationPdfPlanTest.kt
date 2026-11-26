package org.synapseworks.pageharbor.translation
import org.junit.Test
import org.junit.Assert.*
class TranslationPdfPlanTest {
 @Test fun reflowRetainsAllCharactersAndBlankLines(){val s="Școală şi Țară. μ µ.\n\n"+("Words and numbers 123 ").repeat(200);val p=TranslationPdfPlan.pages(s){it.length*15f};assertTrue(p.size>1);assertEquals(s.replace("\n",""),p.flatMap{it.lines}.joinToString(""));assertTrue(p.flatMap{it.lines}.any{it.isEmpty()})}
 @Test fun unsupportedNotSubstituted(){for(s in listOf("😀","a\u0306","\t","bad\r")){try{TranslationPdfPlan.validate(s);fail()}catch(_:IllegalArgumentException){}}}
 @Test fun pageAndWordBounds(){try{TranslationPdfPlan.pages("x\n".repeat(500)){it.length*15f};fail()}catch(_:IllegalArgumentException){};try{TranslationPdfPlan.pages("x".repeat(100)){it.length*20f};fail()}catch(_:IllegalArgumentException){}}
}
