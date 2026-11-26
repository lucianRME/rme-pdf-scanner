package org.synapseworks.pageharbor.translation
import org.junit.Test
import org.junit.Assert.*
class RomanianDraftCorrectionTest {
 @Test fun onlyExplicitOccurrencesChange(){val s="Quoted Şerban and Ţepeş; ştiinţă. μ µ 😀 a\u0306";val p=RomanianDraftCorrection.propose(s);val selected=p.filter{it.offset>s.indexOf(';')}.map{it.offset}.toSet();assertEquals("Quoted Şerban and Ţepeş; știință. μ µ 😀 a\u0306",RomanianDraftCorrection.apply(s,selected));assertEquals(s,RomanianDraftCorrection.apply(s,emptySet()))}
 @Test fun fourLettersOnly(){assertEquals("ȘșȚț ș ț Š μ µ",RomanianDraftCorrection.apply("ŞşŢţ ș ț Š μ µ",setOf(0,1,2,3)))}
 @Test fun staleSelectionRejected(){try{RomanianDraftCorrection.apply("Text",setOf(8));fail()}catch(_:IllegalArgumentException){}}
}
