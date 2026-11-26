package org.synapseworks.pageharbor.integrity

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.cms.*
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.security.interfaces.RSAPublicKey
import java.util.Date

/** Reference-only API checkpoint: Apache PDFBox2.0.27 ShowSignature.java, Apache-2.0.
 * No CertificateVerifier, PKIX chain builder, URL, TSA, AIA or revocation client is called. */
internal object PdfSignatureInspector{
 private val provider by lazy{BouncyCastleProvider()}
 fun inspect(pdf:PDDocument,d:COSDictionary,source:ByteArray,associated:Boolean,check:()->Unit):SignatureResult{
  val declared=d.getNameAsString(COSName.SUB_FILTER)?:"UNKNOWN";val format=if(declared.length<=128)IntegrityRules.safe(declared)else"UNSUPPORTED_NAME_LIMIT";val basic=try{validateRange(pdf,d,source)}catch(_:Exception){null}
  if(basic==null)return SignatureResult(format,"NOT_CHECKED","MALFORMED_OR_UNSUPPORTED_RANGE","Cannot bind an exact uncompressed indirect Contents exclusion to captured bytes.")
  val (ranges,end)=basic;val eof=String(source,maxOf(0,end.toInt()-64),minOf(64,end.toInt()),Charsets.ISO_8859_1).trimEnd(' ','\r','\n','\t').endsWith("%%EOF")
  val coverage=if(!eof)"UNCERTAIN_SIGNED_END"else if(end==source.size.toLong())"CURRENT_FILE_BYTES_EXCEPT_CONTENTS"else"EARLIER_BYTES_ONLY; ${source.size-end} subsequent bytes not signed"
  if(!associated||format !in setOf("adbe.pkcs7.detached","ETSI.CAdES.detached"))return SignatureResult(format,"UNSUPPORTED",coverage,"${if(!associated)"Signature dictionary is not associated with a parsed signature field. "else""}Unsupported signature profile; no cryptographic success inferred.")
  return try{check();val contents=(d.getDictionaryObject(COSName.CONTENTS)as COSString).bytes;require(contents.size in 1..65536);val signed=ByteArray(ranges[1].toInt()+ranges[3].toInt());source.copyInto(signed,0,0,ranges[1].toInt());source.copyInto(signed,ranges[1].toInt(),ranges[2].toInt(),end.toInt())
   val cms=CMSSignedData(CMSProcessableByteArray(signed), readPdfSignatureContent(contents));val signers=cms.signerInfos.signers;require(signers.size==1&&cms.certificates.getMatches(null).size<=8);val signer=signers.single()
   if(signer.digestAlgOID!="2.16.840.1.101.3.4.2.1"||signer.encryptionAlgOID !in setOf("1.2.840.113549.1.1.1","1.2.840.113549.1.1.11"))return SignatureResult(format,"UNSUPPORTED",coverage,"Only detached SHA-256/RSA2048–4096 is qualified.")
   val matches=cms.certificates.getMatches(null).filter{signer.sid.match(it)};require(matches.size==1);val holder=matches.single();require(holder.encoded.size<=32768)
   if(holder.subjectPublicKeyInfo.algorithm.algorithm.id != "1.2.840.113549.1.1.1") {
    return SignatureResult(format, "UNSUPPORTED", coverage, "Signer key is outside the RSA profile.")
   }
   val cert=JcaX509CertificateConverter().setProvider(provider).getCertificate(holder);val key=cert.publicKey as?RSAPublicKey?:return SignatureResult(format,"UNSUPPORTED",coverage,"Signer key is not RSA.");if(key.modulus.bitLength() !in 2048..4096)return SignatureResult(format,"UNSUPPORTED",coverage,"RSA key size outside profile.")
   check();val verified=try{signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(key))}catch(_:CMSException){false};check()
   val now=Date();val time=try{cert.checkValidity(now);"VALID_AT_DEVICE_CLOCK ${now.toInstant()} (not trusted time)"}catch(_:java.security.cert.CertificateExpiredException){"EXPIRED_AT_DEVICE_CLOCK"}catch(_:java.security.cert.CertificateNotYetValidException){"NOT_YET_VALID_AT_DEVICE_CLOCK"}
   val info="Subject=${IntegrityRules.safe(cert.subjectX500Principal.name)}; issuer=${IntegrityRules.safe(cert.issuerX500Principal.name)}; serial=${cert.serialNumber.toString(16)}; notBefore=${cert.notBefore.toInstant()}; notAfter=${cert.notAfter.toInstant()}"
   SignatureResult(format,if(verified)"VALID"else"INVALID",coverage,"Binding concerns only selected signed bytes. It does not establish authenticity, trust or permitted later revisions. PDF claimed time=${IntegrityRules.safe(d.getString(COSName.M).orEmpty())}; not a verified timestamp.",info,time)
  }catch(_:java.util.concurrent.CancellationException){throw java.util.concurrent.CancellationException()}catch(_:Exception){SignatureResult(format,"INVALID_OR_UNSUPPORTED_CMS",coverage,"CMS verification declined or failed; no cryptographic success inferred.")}catch(_:StackOverflowError){SignatureResult(format,"NOT_CHECKED",coverage,"CMS resource limit.")}
 }
 private fun validateRange(pdf:PDDocument,d:COSDictionary,b:ByteArray):Pair<List<Long>,Long>{
  val array=d.getDictionaryObject(COSName.BYTERANGE)as?COSArray?:error("RANGE");require(array.size()==4);val r=(0..3).map{(array.getObject(it)as?COSInteger)?.longValue()?:error("RANGE")};val end=requireNotNull(IntegrityRules.range(r,b.size.toLong()));val start=r[1].toInt();val stop=r[2].toInt();require(stop-start in 4..131074&&b[start]==60.toByte()&&b[stop-1]==62.toByte())
  val contents=(d.getDictionaryObject(COSName.CONTENTS)as?COSString)?.bytes?:error("CONTENTS");val hex=b.copyOfRange(start+1,stop-1);require(hex.size%2==0&&hex.size/2==contents.size)
  fun value(c:Byte):Int=when(c.toInt()){in 48..57->c-48;in 65..70->c-55;in 97..102->c-87;else->error("HEX")}
  for(i in contents.indices)require(((value(hex[2*i])shl 4)or value(hex[2*i+1])).toByte()==contents[i])
  val key=requireNotNull(pdf.document.getKey(d));val offset=pdf.document.xrefTable[key]?:error("XREF");require(offset>=0&&offset<start);val objectEnd=String(b,stop,minOf(4096,b.size-stop),Charsets.ISO_8859_1).indexOf("endobj");require(objectEnd>=0)
  val raw=String(b,offset.toInt(),stop+objectEnd+6-offset.toInt(),Charsets.ISO_8859_1);require(raw.startsWith("${key.number} ${key.generation} obj")&&start-offset<=4096)
  val prefix=String(b,offset.toInt(),start-offset.toInt(),Charsets.ISO_8859_1);require(prefix.trimEnd().endsWith("/Contents")&&!raw.contains('#'))
  // Reparse the exact indirect object and the prefix with PDFBox, rather than infer
  // lexical position from matching bytes inside a literal string or nested value.
  val parsed=objectDictionary(raw,key.number,key.generation.toLong())
  require((parsed.getItem(COSName.CONTENTS)as?COSString)?.bytes?.contentEquals(contents)==true)
  val marker="RMEIntegrityContentsPositionCheck";require(!prefix.contains(marker))
  val probe=prefix.trimEnd().removeSuffix("/Contents")+" /$marker true >>\nendobj"
  require(objectDictionary(probe,key.number,key.generation.toLong()).getBoolean(COSName.getPDFName(marker),false))
  for(token in listOf("/Contents","/ByteRange","/SubFilter"))require(raw.indexOf(token)>=0&&raw.indexOf(token)==raw.lastIndexOf(token))
  return r to end
 }
 private fun objectDictionary(text:String,number:Long,generation:Long):COSDictionary{
  val parser=com.tom_roush.pdfbox.pdfparser.PDFStreamParser(text.toByteArray(Charsets.ISO_8859_1))
  require((parser.parseNextToken()as?COSInteger)?.longValue()==number)
  require((parser.parseNextToken()as?COSInteger)?.longValue()==generation)
  require((parser.parseNextToken()as?com.tom_roush.pdfbox.contentstream.operator.Operator)?.name=="obj")
  val dict=parser.parseNextToken()as?COSDictionary?:error("SIGNATURE_OBJECT")
  require((parser.parseNextToken()as?com.tom_roush.pdfbox.contentstream.operator.Operator)?.name=="endobj"&&parser.parseNextToken()==null)
  return dict
 }
}

/** PDF signature reservations may leave zero padding after the CMS envelope. */
internal fun readPdfSignatureContent(contents: ByteArray): ContentInfo {
    val bytes = ByteArrayInputStream(contents)
    return ASN1InputStream(bytes).use { input ->
        val envelope = requireNotNull(input.readObject())
        val end = contents.size - bytes.available()
        require((end until contents.size).all { contents[it] == 0.toByte() })
        ContentInfo.getInstance(envelope)
    }
}
