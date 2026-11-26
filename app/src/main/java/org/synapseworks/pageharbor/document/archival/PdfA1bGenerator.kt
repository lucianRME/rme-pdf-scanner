package org.synapseworks.pageharbor.document.archival

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage

/** Controlled opaque sRGB image-only PDF/A-1b. The caller supplies already rendered sRGB JPEGs.
 * Host veraPDF evidence is independent; this generator is not an on-device conformance validator.
 * No imported PDF objects, font, OCR, transparency, attachment, form or encryption is copied.
 */
internal class PdfA1bGenerator(context: Context) {
    private val app=context.applicationContext ?: context
    suspend fun generate(pages:List<SearchablePdfPage>,output:File,scratch:File,allowed:()->Boolean={true}) = withContext(Dispatchers.IO) {
        require(pages.size in 1..20 && pages.all { it.ocrResult==null })
        require(!output.exists() && scratch.isDirectory && output.parentFile==scratch)
        var success=false
        val job=coroutineContext
        val check:()->Unit={job.ensureActive();check(allowed())}
        try {
            check()
            val icc=app.assets.open("archival/sRGB2014.icc").use { input ->
                val b=input.readBytesBounded(3024,check);require(b.size==3024 && sha256(b)==ICC_SHA256);b
            }
            PDFBoxResourceLoader.init(app)
            PDDocument(MemoryUsageSetting.setupMixed(8L*1024*1024,64L*1024*1024).setTempDir(scratch)).use { pdf ->
                pdf.version=1.4f
                pdf.documentInformation.creator=CREATOR
                pdf.documentInformation.producer=PRODUCER
                pdf.documentCatalog.metadata=PDMetadata(pdf).apply { importXMPMetadata(XMP.toByteArray(Charsets.UTF_8)) }
                pdf.documentCatalog.addOutputIntent(PDOutputIntent(pdf,icc.inputStream()).apply {
                    outputConditionIdentifier="sRGB2014";info="sRGB IEC61966-2.1";registryName="http://www.color.org"
                })
                var total=0L
                pages.forEach { page ->
                    check()
                    val bytes=page.openJpegStream().use { it.readBytesBounded(24*1024*1024-total.toInt(),check) }
                    total+=bytes.size;require(total<=24L*1024*1024)
                    // JPEGFactory can accept other decodable formats while marking DCTDecode.
                    // Controlled inputs must actually be JPEG, not merely a decodable bitmap.
                    require(bytes.size>=4 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() && bytes[2]==0xff.toByte() && bytes[bytes.size-2]==0xff.toByte() && bytes.last()==0xd9.toByte())
                    val image=JPEGFactory.createFromStream(pdf,bytes.inputStream())
                    require(image.colorSpace is PDDeviceRGB && image.bitsPerComponent==8 && image.width in 1..6000 && image.height in 1..6000 && image.width.toLong()*image.height<=12_500_000)
                    val w=page.paperSize?.widthPoints ?: image.width.toFloat();val h=page.paperSize?.heightPoints ?: image.height.toFloat()
                    require(w.isFinite() && h.isFinite() && w in 3f..14400f && h in 3f..14400f)
                    val sheet=PDPage(PDRectangle(w,h));pdf.addPage(sheet)
                    PDPageContentStream(pdf,sheet).use { it.drawImage(image,0f,0f,w,h) }
                }
                check()
                FileOutputStream(output).use { raw ->
                    val bounded=object:FilterOutputStream(raw) {
                        var count=0L
                        override fun write(b:Int) { check();require(++count<=MAX_PDF);out.write(b) }
                        override fun write(b:ByteArray,off:Int,len:Int) { check();count+=len;require(count<=MAX_PDF);out.write(b,off,len) }
                    }
                    pdf.save(bounded)
                }
                check();FileOutputStream(output,true).use { it.fd.sync() }
            }
            check();require(output.length() in 1..MAX_PDF);success=true
        } catch(_:OutOfMemoryError) { throw IllegalStateException("Archival resources unavailable") } finally { if(!success && output.isFile)output.delete() }
    }
    private fun java.io.InputStream.readBytesBounded(limit:Int,check:()->Unit):ByteArray {
        require(limit>0)
        val result=java.io.ByteArrayOutputStream();val b=ByteArray(16*1024)
        while(true) { check();val n=read(b);if(n<0)break;require(n>0 && result.size()+n<=limit);result.write(b,0,n) };return result.toByteArray()
    }
    companion object {
        const val ICC_SHA256="384b832de3412066743b52a75ee906b6fb9fb8d9e09e936fc2c43223815c6e0a"
        private const val MAX_PDF=32L*1024*1024
        private const val CREATOR="RME PDF Scanner"
        private const val PRODUCER="RME PDF/A-1b image generator 1 / PdfBox-Android 2.0.27.0"
        private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        private val XMP="""<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="RME PDF Scanner">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" pdfaid:part="1" pdfaid:conformance="B"/>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="$CREATOR"/>
<rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/" pdf:Producer="$PRODUCER"/>
</rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>"""
    }
}
