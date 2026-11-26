package org.synapseworks.pageharbor.integrity
import org.junit.Test
import org.junit.Assert.*
class IntegrityRulesTest{
 @Test fun validRanges(){assertEquals(100L,IntegrityRules.range(listOf(0,20,40,60),100));assertEquals(90L,IntegrityRules.range(listOf(0,20,40,50),100))}
 @Test fun hostileRanges(){for(r in listOf(listOf(1L,20,40,60),listOf(0L,-1,40,60),listOf(0L,40,20,60),listOf(0L,20,40,Long.MAX_VALUE),listOf(0L,20,101,0),listOf(0L,20,40,-1),listOf(0L,20,40)))assertNull(IntegrityRules.range(r,100))}
 private fun doc(text:String?,pix:IntArray?=intArrayOf(1))=IntegrityDocument("x",1,listOf(IntegrityPage(1,240f,140f,0,text,null,pix,1,1)),emptyList(),"")
 @Test fun separateEquality(){val a=doc("Hello");assertEquals("EXTRACTED_TEXT_EQUAL",IntegrityRules.compare(a,doc("Hello",intArrayOf(2)))[0].text);assertEquals("RENDERED_PIXELS_DIFFERENT",IntegrityRules.compare(a,doc("Hello",intArrayOf(2)))[0].visual);assertEquals("EXTRACTED_TEXT_DIFFERENT",IntegrityRules.compare(a,doc("Changed"))[0].text)}
 @Test fun noScanVerification(){assertTrue(IntegrityRules.compare(doc(""),doc(""))[0].text.startsWith("NO_NATIVE_TEXT"));assertTrue(IntegrityRules.compare(doc("text"),doc(""))[0].text.startsWith("NO_NATIVE_TEXT"));assertEquals("NOT_CHECKED",IntegrityRules.compare(doc(null),doc(""))[0].text)}
 @Test fun pagePresence(){val a=doc("x");assertEquals("ADDED_PAGE",IntegrityRules.compare(a,a.copy(pages=a.pages+a.pages))[1].visual);assertEquals("MISSING_PAGE",IntegrityRules.compare(a.copy(pages=a.pages+a.pages),a)[1].visual)}
 @Test fun metadataInjection(){assertEquals("CN=Test\\u000aVALID",IntegrityRules.safe("CN=Test\nVALID"));assertTrue(IntegrityRules.safe("\u202eABC").startsWith("\\u202e"))}
}
