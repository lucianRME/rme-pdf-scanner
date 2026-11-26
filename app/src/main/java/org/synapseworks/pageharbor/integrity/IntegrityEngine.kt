package org.synapseworks.pageharbor.integrity
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.text.*
import com.tom_roush.pdfbox.contentstream.operator.Operator
import org.synapseworks.pageharbor.forms.*
import java.io.*
import java.util.*
import java.security.MessageDigest

internal object IntegrityEngine{
 fun inspect(file:File,work:File,label:String,check:()->Unit):IntegrityDocument{
  require(file.length() in 1..IntegrityRules.INPUT);val source=file.readBytes();val hash=MessageDigest.getInstance("SHA-256").digest(source).joinToString(""){"%02x".format(it)};val budget=FormBudget(check)
  return FormPdfEngine.load(file,work,budget).use{pdf->require(!pdf.isEncrypted&&pdf.document.xrefTable.size<=5000)
   val seen=Collections.newSetFromMap(IdentityHashMap<COSBase,Boolean>());val sigs=mutableListOf<COSDictionary>();val sigSeen=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>());val associated=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>());var streams=0L;var pixels=0L;var active=false
   fun walk(v:COSBase?,depth:Int){if(v==null||!seen.add(v))return;budget.node(depth);when(v){is COSObject->walk(v.`object`,depth+1);is COSArray->v.forEach{walk(it,depth+1)};is COSDictionary->{
    if(v.getNameAsString(COSName.TYPE)=="Sig"&&sigSeen.add(v))sigs+=v
    if(v.keySet().any{it.name in setOf("A","AA","OpenAction","JS","JavaScript","EmbeddedFiles","XFA","AF","RichMediaContent")}||v.getNameAsString(COSName.TYPE)in setOf("Action","Filespec","EmbeddedFile"))active=true
    if(v is COSStream){if(v.containsKey(COSName.F))active=true
     else if(v.getNameAsString(COSName.SUBTYPE)=="Image"){val w=v.getInt(COSName.WIDTH);val h=v.getInt(COSName.HEIGHT);require(w>0&&h>0&&w.toLong()*h<=2000000);pixels+=w.toLong()*h;require(pixels<=4000000);if(v.getInt(COSName.BITS_PER_COMPONENT)!=8||v.getDictionaryObject(COSName.COLORSPACE) !in setOf(COSName.DEVICERGB,COSName.DEVICEGRAY))active=true}
     else{v.createInputStream().use{input->val b=ByteArray(8192);while(true){budget.step();val n=input.read(b);if(n<0)break;require(n>0);streams+=n;require(streams<=4194304)}}}}
    v.values.forEach{walk(it,depth+1)}}}}
   walk(pdf.document.trailer,0);pdf.document.objects.toList().forEach{walk(it,0)}
   val fieldSeen=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>())
   fun fields(d:COSDictionary,kind:String?,depth:Int){budget.node(depth);require(depth<=8&&fieldSeen.add(d)&&fieldSeen.size<=128);val type=d.getNameAsString(COSName.FT)?:kind
    if(type=="Sig")(d.getDictionaryObject(COSName.V)as?COSDictionary)?.let{associated.add(it);if(sigSeen.add(it))sigs.add(it)}
    (d.getDictionaryObject(COSName.KIDS)as?COSArray)?.forEach{fields((if(it is COSObject)it.`object`else it)as COSDictionary,type,depth+1)}
   }
   ((pdf.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM)as?COSDictionary)?.getDictionaryObject(COSName.FIELDS)as?COSArray)?.forEach{fields((if(it is COSObject)it.`object`else it)as COSDictionary,null,0)}
   require(sigs.size<=4)
   val visited=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>());val pages=mutableListOf<PDPage>()
   fun tree(d:COSDictionary,parent:COSDictionary?,depth:Int){budget.node(depth);require(depth<=8&&visited.add(d)&&d.getDictionaryObject(COSName.PARENT)===parent)
    when(d.getNameAsString(COSName.TYPE)){"Page"->{pages+=PDPage(d);require(pages.size<=4)};"Pages"->{val kids=d.getDictionaryObject(COSName.KIDS)as COSArray;require(kids.size()<=4);val before=pages.size;kids.forEach{tree((if(it is COSObject)it.`object`else it)as COSDictionary,d,depth+1)};require(d.getInt(COSName.COUNT)==pages.size-before)};else->error("PAGES")}}
   tree(pdf.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES)as COSDictionary,null,0);require(pages.isNotEmpty())
   val signatures=sigs.map{check();PdfSignatureInspector.inspect(pdf,it,source,associated.contains(it),check)}
   var total=0;val extracted=pages.mapIndexed{i,page->check();val box=page.cropBox;require(box.width.isFinite()&&box.height.isFinite()&&box.width in 32f..2000f&&box.height in 32f..2000f&&page.rotation in listOf(0,90,180,270)&&page.userUnit==1f)
    val text=if(active)null else try{var count=0;var chars=0;val stripper=object:PDFTextStripper(){override fun processOperator(op:Operator,args:MutableList<COSBase>){budget.step();super.processOperator(op,args)};override fun processTextPosition(p:TextPosition){budget.step();require(++count<=20000);super.processTextPosition(p)};override fun writeString(s:String,positions:MutableList<TextPosition>){chars+=s.length;require(chars<=8192);super.writeString(s,positions)}};stripper.startPage=i+1;stripper.endPage=i+1;stripper.getText(pdf).also{total+=it.length;require(it.length<=8192&&total<=32768)}}catch(_:Exception){active=true;null}
    IntegrityPage(i+1,box.width,box.height,page.rotation,text,null,null)}
   val rendered=if(active)extracted else try{ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{descriptor->PdfRenderer(descriptor).use{r->require(r.pageCount==pages.size);extracted.mapIndexed{i,p->check();r.openPage(i).use{page->val scale=480.0/maxOf(page.width,page.height);val w=maxOf(1,(page.width*scale).toInt());val h=maxOf(1,(page.height*scale).toInt());val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);try{bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);check();val pixelsOut=IntArray(w*h);bitmap.getPixels(pixelsOut,0,w,0,0,w,h);val png=File(work,"$label-${i+1}.png");png.outputStream().use{base->val bounded=object:FilterOutputStream(base){var n=0;override fun write(b:ByteArray,o:Int,l:Int){check();n+=l;require(n<=4194304);out.write(b,o,l)};override fun write(v:Int){check();require(++n<=4194304);out.write(v)}};require(bitmap.compress(Bitmap.CompressFormat.PNG,100,bounded))};p.copy(png=png,pixels=pixelsOut,rasterWidth=w,rasterHeight=h)}finally{bitmap.recycle()}}}}}}catch(_:Exception){extracted}
   IntegrityDocument(hash,file.length(),rendered,signatures,if(active)"Active/external/unsupported resources: rendering and text NOT_CHECKED; nothing executed."else"Native text can include hidden or OCR layers. Raster equality only at480px long side; no semantic authenticity verdict.")
  }
 }
 fun report(a:IntegrityDocument,b:IntegrityDocument,byteEqual:Boolean):String=buildString{
  append("RME Document integrity — captured, read-only comparison\nBaseline SHA-256: ${a.sha256}\nComparison SHA-256: ${b.sha256}\nByte equality: ${if(byteEqual)"BYTE_IDENTICAL"else"DIFFERENT_BYTES"}\nEqual bytes establish equality to the selected baseline, not truth or authenticity.\nPage-number alignment only; no semantic alignment.\n")
  for((label,d)in listOf("Baseline" to a,"Comparison" to b)){append("\n$label: ${d.bytes} bytes, ${d.pages.size} pages. ${d.limitation}\nSignature dictionaries: ${d.signatures.size}${if(d.signatures.isEmpty())" (NONE in parsed current document; not hidden-byte forensic proof)"else""}\n");d.signatures.forEachIndexed{i,s->append("Signature ${i+1}\n${s.report()}\n")}}
  IntegrityRules.compare(a,b).forEach{append("Page ${it.page}: ${it.text}; ${it.visual}; changed pixels=${it.changed?:"NOT_CHECKED"}; ${it.dimensions}\n")}
  append("\nContent Credentials: NOT_CHECKED. Local Android C2PA verification NOT_IMPLEMENTED. No marker search or absence claim.\nNo remote fetching. Trust anchors:0; trust/chain NOT_ASSESSED. Trusted timestamp/revocation/freshness/DocMDP NOT_CHECKED.\nCooperative cancellation and parser/native limits; no guaranteed forced native interruption. No genuine/fake score.\n")
 }.also{require(it.toByteArray(Charsets.UTF_8).size<=65536)}
}
