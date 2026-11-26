package org.synapseworks.pageharbor.qr

import org.junit.Assert.*
import org.junit.Test

class QrPayloadTest {
    @Test fun exactTextHasSafeDisplayAndRedactedDiagnostics(){
        val text="Synthetic\u202E QR\u0000 content\tend\n😀"
        val p=QrPayload.accept(text)!!
        assertEquals(text,p.text);assertEquals("Synthetic\\u{202E} QR\\u{0} content\\u{9}end\n😀",p.display)
        assertNull(p.destination);assertFalse(p.toString().contains("Synthetic"))
        assertFalse(QrState("owner",1,result=p).toString().contains("Synthetic"))
    }
    @Test fun textAdmissionBoundsMalformedSurrogatesAndCapacity(){
        assertNull(QrPayload.accept(""));assertNull(QrPayload.accept("x".repeat(8193)))
        assertNull(QrPayload.accept("\uD800"));assertNull(QrPayload.accept("\uDC00"));assertNull(QrPayload.accept("\uD800x"))
        assertEquals(7000,QrPayload.accept("7".repeat(7000))!!.text.length)
        assertNotNull(QrPayload.accept("😀".repeat(4096)))
    }
    @Test fun onlyUnambiguousWebDestinationsHaveAnAction(){
        for(url in listOf("https://example.invalid/path?q=%20#fragment","http://127.0.0.1:8080/","https://[::1]/","HTTPS://Example.invalid:443/%E6%97%A5")){
            val d=QrDestination.parse(url)!!;assertTrue(d.value.startsWith("http"));assertFalse(d.toString().contains("example",true))
            assertEquals(url.replaceRange(0,url.indexOf(':'),url.substringBefore(':').lowercase()),d.value)
        }
    }
    @Test fun schemesUserinfoWhitespaceUnicodeControlsAndParserAmbiguityStayTextOnly(){
        val rejected=listOf("javascript:alert(1)","intent://example/#Intent;end","file:///etc/passwd","content://example/","mailto:synthetic@example.invalid","tel:123","WIFI:T:WPA;S:Synthetic;;",
            "https:example.invalid","https:///example.invalid","//example.invalid"," https://example.invalid","https://example.invalid\n","https://example.invalid/a b",
            "https://trusted.invalid@untrusted.invalid/","https://example.invalid:/","https://example.invalid:0/","https://example.invalid:65536/","https://example.invalid\\@untrusted.invalid/",
            "https://exämple.invalid/","https://example.invalid/\u202E","https://example.invalid/%0aX","https://example.invalid/%00","https://example.invalid/%7f","https://%65xample.invalid/","https://example.invalid/%QQ")
        for(url in rejected)assertNull(QrDestination.parse(url))
    }
}
