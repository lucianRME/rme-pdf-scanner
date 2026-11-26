package org.synapseworks.pageharbor.pdftext

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import org.synapseworks.pageharbor.forms.*
import java.io.*
import java.util.*

/** Explicit opaque visual cover. Original text/images are retained. Never a redaction engine. */
internal object VisualPdfEngine {
    private fun fail(reason:FormFailure=FormFailure.UNSUPPORTED):Nothing=throw FormException(reason)
    private val forbidden=setOf("A","AA","OpenAction","JS","JavaScript","AcroForm","XFA","Annots","EmbeddedFiles","AF","Collection","RichMediaContent","RichMediaSettings","3DD","ByteRange","Perms","DocMDP","UR","UR3","OCProperties","OC","Group","ExtGState","Pattern","Shading","SMask","Mask","ToUnicode","Encrypt","FontFile","FontFile2","FontFile3")
    private val ops=setOf("q","Q","cm","BT","ET","Tf","Tm","Td","TD","T*","Tj","TJ","'","\"","Tc","Tw","Tz","TL","Tr","Ts","g","G","rg","RG","k","K","w","J","j","M","d","m","l","c","v","y","h","re","S","s","f","F","f*","B","B*","b","b*","n","W","W*","Do")
    private fun inspect(pdf:PDDocument,b:FormBudget):VisualDocument {
        if(pdf.isEncrypted)fail(FormFailure.ENCRYPTED)
        if(pdf.document.xrefTable.size>10000)fail(FormFailure.LIMIT)
        val seen=Collections.newSetFromMap(IdentityHashMap<COSBase,Boolean>());val queue=ArrayDeque<COSBase>();var images=0;var pixels=0L
        fun walk(v:COSBase?,depth:Int){if(v==null)return;b.step();if(!seen.add(v))return;b.node(depth)
            when(v){is COSObject->v.`object`?.let(queue::addLast)
                is COSDictionary->{
                    if(v.keySet().any{it.name in forbidden}||v.containsKey(COSName.FT)||v.getNameAsString(COSName.TYPE) in setOf("Sig","Action","Filespec","EmbeddedFile")||v.getNameAsString(COSName.S) in setOf("JavaScript","Launch","SubmitForm","ResetForm","ImportData","GoToR","GoToE","URI","Rendition")||v is COSStream&&v.containsKey(COSName.F))fail(FormFailure.ACTIVE)
                    if(v.getNameAsString(COSName.TYPE)=="Font"||v.containsKey(COSName.BASE_FONT)||v.getNameAsString(COSName.SUBTYPE) in setOf("Type0","Type1","MMType1","Type3","TrueType","CIDFontType0","CIDFontType2")){
                        if(v.getNameAsString(COSName.SUBTYPE)!="Type1"||v.getNameAsString(COSName.BASE_FONT) !in setOf("Helvetica","Courier","Times-Roman")||v.getDictionaryObject(COSName.ENCODING)!=COSName.WIN_ANSI_ENCODING||v.keySet().any{it.name !in setOf("Type","Subtype","BaseFont","Encoding","Name")})fail()
                    }
                    if(v.getNameAsString(COSName.TYPE)=="XObject"||v is COSStream&&v.getNameAsString(COSName.SUBTYPE) in setOf("Form","Image")){
                        if(v.getNameAsString(COSName.SUBTYPE)!="Image")fail()
                        val w=v.getInt(COSName.WIDTH);val h=v.getInt(COSName.HEIGHT)
                        pixels+=w.toLong()*h;if(++images>4||w !in 1..4096||h !in 1..4096||pixels>16L*1024*1024)fail(FormFailure.LIMIT)
                        if(v.getInt(COSName.BITS_PER_COMPONENT)!=8||v.getNameAsString(COSName.COLORSPACE) !in setOf("DeviceRGB","DeviceGray")||v.containsKey(COSName.DECODE_PARMS)||v.getBoolean(COSName.IMAGE_MASK,false))fail()
                        val filter=v.getDictionaryObject(COSName.FILTER);val filters=if(filter is COSArray)filter.map{(it as?COSName)?.name?:fail()}else listOf((filter as?COSName)?.name?:fail())
                        if(filters.size !in 1..2||filters.any{it !in setOf("ASCII85Decode","FlateDecode","DCTDecode")})fail()
                    }
                    v.values.forEach{walk(it,depth+1)}
                }
                is COSArray->v.forEach{walk(it,depth+1)}
            }}
        walk(pdf.document.trailer,0);pdf.document.objects.toList().forEach(queue::addLast);while(queue.isNotEmpty()){b.step();walk(queue.removeFirst(),0)}
        val visited=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>());val pages=mutableListOf<COSDictionary>()
        fun tree(d:COSDictionary,parent:COSDictionary?,depth:Int):Int {b.node(depth);if(depth>8||!visited.add(d)||d.getDictionaryObject(COSName.PARENT)!==parent)fail()
            return when(d.getNameAsString(COSName.TYPE)){
                "Page"->{pages+=d;if(pages.size>32)fail(FormFailure.LIMIT);1}
                "Pages"->{val kids=d.getDictionaryObject(COSName.KIDS)as?COSArray?:fail();if(kids.size()>32)fail(FormFailure.LIMIT);val count=kids.sumOf{tree((if(it is COSObject)it.`object`else it)as?COSDictionary?:fail(),d,depth+1)};if(count!=d.getInt(COSName.COUNT))fail();count}
                else->fail()}}
        tree(pdf.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES)as?COSDictionary?:fail(),null,0);if(pages.isEmpty())fail()
        var decoded=0
        val model=pages.map{d->b.step();val p=PDPage(d);val box=p.cropBox
            if(p.rotation !in setOf(0,90,180,270)||p.userUnit!=1f||!listOf(box.lowerLeftX,box.lowerLeftY,box.width,box.height).all{it.isFinite()}||box.width !in 32f..14400f||box.height !in 32f..14400f)fail()
            val bytes=bounded(p.contents,minOf(1024*1024,4*1024*1024-decoded),b);decoded+=bytes.size
            val parser=PDFStreamParser(bytes);var count=0;var text=false;var graphics=0;val args=mutableListOf<COSBase>()
            fun number(v:COSBase):Boolean=v is COSNumber&&v.floatValue().isFinite()&&kotlin.math.abs(v.floatValue())<=100000
            fun numeric(n:Int){if(args.size!=n||args.any{!number(it)})fail()}
            fun string(v:COSBase)=v is COSString&&v.bytes.size<=4096
            while(true){b.step();val token=parser.parseNextToken()?:break;if(++count>20000)fail(FormFailure.LIMIT)
                if(token is Operator){
                    val op=token.name;if(op !in ops)fail()
                    when(op){
                        "BT"->{numeric(0);if(text)fail();text=true}
                        "ET"->{numeric(0);if(!text)fail();text=false}
                        "q"->{numeric(0);if(text||++graphics>16)fail()}
                        "Q"->{numeric(0);if(text||--graphics<0)fail()}
                        "Tf"->{if(!text||args.size!=2||args[0] !is COSName||!number(args[1]))fail();val fonts=p.resources?.cosObject?.getDictionaryObject(COSName.FONT)as?COSDictionary?:fail();if(fonts.getDictionaryObject(args[0]as COSName) !is COSDictionary)fail()}
                        "Tj","'"->{if(!text||args.size!=1||!string(args[0]))fail()}
                        "TJ"->{val values=args.singleOrNull()as?COSArray?:fail();if(!text||values.size()>4096||values.any{!number(it)&&!string(it)})fail()}
                        "\""->{if(!text||args.size!=3||!number(args[0])||!number(args[1])||!string(args[2]))fail()}
                        "Do"->{if(text||args.size!=1||args[0] !is COSName)fail();val objects=p.resources?.cosObject?.getDictionaryObject(COSName.XOBJECT)as?COSDictionary?:fail();val image=objects.getDictionaryObject(args[0]as COSName)as?COSStream?:fail();if(image.getNameAsString(COSName.SUBTYPE)!="Image")fail()}
                        "d"->{if(args.size!=2||args[0] !is COSArray||!number(args[1]))fail();val values=args[0]as COSArray;if(values.size()>64||values.any{!number(it)})fail()}
                        "Tm"->{if(!text)fail();numeric(6)}
                        "Td","TD"->{if(!text)fail();numeric(2)}
                        "T*"->{if(!text)fail();numeric(0)}
                        "Tc","Tw","Tz","TL","Tr","Ts"->{if(!text)fail();numeric(1)}
                        "cm","c"->{if(text)fail();numeric(6)}
                        "re","v","y"->{if(text)fail();numeric(4)}
                        "m","l"->{if(text)fail();numeric(2)}
                        "rg","RG"->numeric(3)
                        "k","K"->numeric(4)
                        "g","G","w","J","j","M"->numeric(1)
                        else->{if(text)fail();numeric(0)}
                    };args.clear()
                }else {if(token !is COSBase||args.size>=8)fail();args+=token}
            }
            if(text||graphics!=0||args.isNotEmpty())fail()
            VisualPage(box.lowerLeftX,box.lowerLeftY,box.width,box.height,p.rotation)
        }
        return VisualDocument(model)
    }
    private fun bounded(input:InputStream,max:Int,b:FormBudget):ByteArray=input.use{val out=ByteArrayOutputStream();val bytes=ByteArray(8192);while(true){b.step();val n=it.read(bytes);if(n<0)break;if(n==0||out.size()+n>max)fail(FormFailure.LIMIT);out.write(bytes,0,n)};out.toByteArray()}
    fun inspect(source:File,scratch:File,check:()->Unit={}):VisualDocument {val b=FormBudget(check);return FormPdfEngine.load(source,scratch,b).use{inspect(it,b)}}
    fun preview(file:File,page:Int,check:()->Unit={}):Bitmap {
        check();ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->renderer.openPage(page).use{p->
            val scale=1024f/maxOf(p.width,p.height);val bitmap=Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            try{bitmap.eraseColor(Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);check();return bitmap}catch(t:Throwable){bitmap.recycle();throw t}
        }}}
    }
    fun fits(model:VisualDocument,edit:VisualEdit):Boolean {
        val p=model.pages.getOrNull(edit.page)?:return false;if(!visualTextAllowed(edit.text))return false
        return try{val font=PDType1Font.HELVETICA;font.encode(edit.text);edit.region.height*p.displayHeight>=24&&font.getStringWidth(edit.text)/1000*12<=edit.region.width*p.displayWidth-8}catch(_:IllegalArgumentException){false}
    }
    private fun whiteBorder(bitmap:Bitmap,edit:VisualEdit,check:()->Unit):Boolean {
        val r=edit.region;val l=(r.left*bitmap.width).toInt().coerceIn(0,bitmap.width-1);val t=(r.top*bitmap.height).toInt().coerceIn(0,bitmap.height-1);val right=(r.right*bitmap.width).toInt().coerceIn(l,bitmap.width-1);val bottom=(r.bottom*bitmap.height).toInt().coerceIn(t,bitmap.height-1)
        fun white(x:Int,y:Int):Boolean{val c=bitmap.getPixel(x,y);return Color.red(c)>=250&&Color.green(c)>=250&&Color.blue(c)>=250}
        for(y in t..bottom){check();for(x in l..right)if((x-l<3||right-x<3||y-t<3||bottom-y<3)&&!white(x,y))return false}
        return true
    }
    fun prepare(source:File,output:File,scratch:File,expected:VisualDocument,edit:VisualEdit,check:()->Unit={}) {
        if(output.exists()||!fits(expected,edit))fail(FormFailure.FIT)
        val before=preview(source,edit.page,check)
        try{if(!whiteBorder(before,edit,check))fail(FormFailure.UNSUPPORTED)
            val b=FormBudget(check)
            FormPdfEngine.load(source,scratch,b).use{pdf->if(inspect(pdf,b)!=expected)fail(FormFailure.SOURCE_CHANGED);val p=expected.pages[edit.page];val page=pdf.getPage(edit.page)
                PDPageContentStream(pdf,page,PDPageContentStream.AppendMode.APPEND,true,true).use{c->
                    c.saveGraphicsState();val matrix=when(p.rotation){90->Matrix(0f,1f,-1f,0f,p.x+p.width,p.y);180->Matrix(-1f,0f,0f,-1f,p.x+p.width,p.y+p.height);270->Matrix(0f,-1f,1f,0f,p.x,p.y+p.height);else->Matrix(1f,0f,0f,1f,p.x,p.y)};c.transform(matrix)
                    val r=edit.region;val x=r.left*p.displayWidth;val y=(1-r.bottom)*p.displayHeight;val w=r.width*p.displayWidth;val h=r.height*p.displayHeight
                    c.addRect(x,y,w,h);c.clip();c.setNonStrokingColor(1f);c.addRect(x,y,w,h);c.fill();c.setNonStrokingColor(0f);c.beginText();c.setRenderingMode(RenderingMode.FILL);c.setFont(PDType1Font.HELVETICA,12f);c.newLineAtOffset(x+4,y+h-16);c.showText(edit.text);c.endText();c.restoreGraphicsState()
                }
                FileOutputStream(output).use{out->val limited=object:OutputStream(){var size=0L;override fun write(v:Int){b.step(1);if(++size>FormLimits.OUTPUT)fail(FormFailure.LIMIT);out.write(v)};override fun write(v:ByteArray,o:Int,n:Int){b.step(n);size+=n;if(size>FormLimits.OUTPUT)fail(FormFailure.LIMIT);out.write(v,o,n)}};pdf.save(limited)}
            }
            if(inspect(output,scratch,check)!=expected)fail();val after=preview(output,edit.page,check)
            try{if(before.sameAs(after))fail(FormFailure.UNSUPPORTED)}finally{after.recycle()}
        }finally{before.recycle()};check()
    }
}
