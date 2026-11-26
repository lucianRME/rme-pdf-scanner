package org.synapseworks.pageharbor.integrity

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfSignatureContentTest {
    private val envelope = ContentInfo(CMSObjectIdentifiers.data, DEROctetString(byteArrayOf(7, 0)))

    @Test
    fun preservesTheEnvelopeWithAndWithoutPdfPadding() {
        for (encoding in listOf(ASN1Encoding.DER, ASN1Encoding.BER)) {
            val encoded = envelope.getEncoded(encoding)
            val padded = encoded + ByteArray(64)
            assertArrayEquals(envelope.getEncoded(ASN1Encoding.DER), readPdfSignatureContent(padded).getEncoded(ASN1Encoding.DER))
            assertArrayEquals(envelope.getEncoded(ASN1Encoding.DER), readPdfSignatureContent(encoded).getEncoded(ASN1Encoding.DER))
        }
    }

    @Test
    fun refusesAnythingOtherThanZeroPaddingAfterTheEnvelope() {
        val encoded = envelope.getEncoded(ASN1Encoding.DER)
        assertThrows(IllegalArgumentException::class.java) {
            readPdfSignatureContent(encoded + byteArrayOf(0, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            readPdfSignatureContent(encoded + encoded)
        }
    }
}
