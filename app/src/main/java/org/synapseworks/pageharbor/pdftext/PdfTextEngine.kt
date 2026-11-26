package org.synapseworks.pageharbor.pdftext

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.synapseworks.pageharbor.forms.*
import java.io.*
import java.util.*
import android.graphics.RectF

internal data class TextRun(val page:Int,val token:Int,val text:String,val x:Float,val y:Float,val size:Float,val width:Float,val resource:String="F1") {
    val id get()="$page:$token"
    override fun toString()="TextRun(<redacted>)"
}
internal data class TextDocument(val runs:List<TextRun>,val pages:Int) {
    fun form()=FormModel(runs.map{FormField(it.id,"Page ${it.page} · occurrence ${runs.filter{r->r.page==it.page}.indexOf(it)+1}",FormKind.TEXT,listOf(it.text),false,false,listOf(it.page))},pages)
    override fun toString()="TextDocument(<redacted>)"
}
/** A deliberately small grammar: no hidden/complex text, external resources or executed actions. */
internal object PdfTextEngine {
    private fun fail(r:FormFailure=FormFailure.UNSUPPORTED):Nothing=throw FormException(r)
    private val forbidden=setOf("A","AA","OpenAction","JS","JavaScript","AcroForm","XFA","Annots","EmbeddedFiles","AF","Collection","RichMediaContent","3DD","ByteRange","Perms","DocMDP","UR","UR3","OCProperties","OC","Group","ExtGState","XObject","Pattern","Shading","SMask","ToUnicode","Encrypt")
    private fun validate(pdf:PDDocument,b:FormBudget) {
        if(pdf.isEncrypted)fail(FormFailure.ENCRYPTED)
        if(pdf.document.xrefTable.size>10000)fail(FormFailure.LIMIT)
        val seen=Collections.newSetFromMap(IdentityHashMap<COSBase,Boolean>());val queue=ArrayDeque<COSBase>()
        fun walk(v:COSBase?,depth:Int){if(v==null)return;b.step();if(!seen.add(v))return;b.node(depth)
            when(v){is COSObject->v.`object`?.let(queue::addLast)
                is COSDictionary->{if(v.keySet().any{it.name in forbidden}||v.getNameAsString(COSName.TYPE) in setOf("Sig","Action","Filespec","EmbeddedFile")||v.containsKey(COSName.FT)||v.getNameAsString(COSName.S) in setOf("JavaScript","Launch","SubmitForm","ResetForm","ImportData","GoToR","GoToE","URI","Rendition")||v is COSStream&&v.containsKey(COSName.F))fail(FormFailure.ACTIVE);v.values.forEach{walk(it,depth+1)}}
                is COSArray->v.forEach{walk(it,depth+1)}}}
        walk(pdf.document.trailer,0);pdf.document.objects.toList().forEach(queue::addLast);while(queue.isNotEmpty()){b.step();walk(queue.removeFirst(),0)}
        // Bound and validate the raw tree before PDFBox page iteration (including parent cycles).
        val visited=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>());var count=0
        fun pages(d:COSDictionary,depth:Int,parent:COSDictionary?):Int {
            b.node(depth);if(depth>8||!visited.add(d)||d.getDictionaryObject(COSName.PARENT)!==parent)fail()
            return when(d.getNameAsString(COSName.TYPE)){
                "Page"->{if(++count>32)fail(FormFailure.LIMIT);1}
                "Pages"->{val kids=d.getDictionaryObject(COSName.KIDS)as?COSArray?:fail();if(kids.size()>32)fail(FormFailure.LIMIT);val n=kids.sumOf{pages((if(it is COSObject)it.`object`else it)as?COSDictionary?:fail(),depth+1,d)};if(n!=d.getInt(COSName.COUNT))fail();n}
                else->fail()}
        }
        val root=pdf.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES)as?COSDictionary?:fail();if(pages(root,0,null)==0)fail()
    }
    private fun tokens(page:PDPage,b:FormBudget,remaining:Int):Pair<List<Any>,Int> {
        val data=page.contents.use{input->val out=ByteArrayOutputStream();val buf=ByteArray(8192);while(true){b.step();val n=input.read(buf);if(n<0)break;if(n==0||out.size()+n>minOf(1024*1024,remaining))fail(FormFailure.LIMIT);out.write(buf,0,n)};out.toByteArray()}
        val parser=PDFStreamParser(data);val result=ArrayList<Any>();while(true){b.step();val token=parser.parseNextToken()?:break;if(result.size>=20000)fail(FormFailure.LIMIT);result.add(token)};return result to data.size
    }
    private data class DrawState(val black:Boolean,val width:Float,val miter:Float,val font:COSName?,val size:Float)
    private data class Parsed(val document:TextDocument,val pages:List<List<Any>>)
    private fun parse(pdf:PDDocument,b:FormBudget):Parsed {
        validate(pdf,b);val runs=mutableListOf<TextRun>();val streams=mutableListOf<List<Any>>();var total=0
        for((pageIndex,page) in pdf.pages.withIndex()){
            b.step();val box=page.cropBox
            if(page.rotation !in listOf(0,90,180,270)||page.userUnit!=1f||!listOf(box.lowerLeftX,box.lowerLeftY,box.width,box.height).all{it.isFinite()}||box.width !in 32f..14400f||box.height !in 32f..14400f)fail()
            val (ts,decoded)=tokens(page,b,4*1024*1024-total);streams+=ts
            total+=decoded;if(total>4*1024*1024)fail(FormFailure.LIMIT)
            val args=mutableListOf<Any>();var inside=false;var shown=false;var font:COSName?=null;var size=0f;var x=Float.NaN;var y=Float.NaN;var black=true
            var depth=0;val colors=ArrayDeque<DrawState>();var lineWidth=1f;var miter=10f;var path:RectF?=null;val painted=mutableListOf<RectF>()
            fun point(px:Float,py:Float){if(kotlin.math.abs(px)>30000||kotlin.math.abs(py)>30000)fail();val r=path;if(r==null)path=RectF(px,py,px,py)else{r.left=minOf(r.left,px);r.top=minOf(r.top,py);r.right=maxOf(r.right,px);r.bottom=maxOf(r.bottom,py)}}
            fun paint(stroke:Boolean){path?.let{r->val copy=RectF(r);copy.inset(if(stroke)-maxOf(1f,lineWidth*miter) else 0f,if(stroke)-maxOf(1f,lineWidth*miter) else 0f);painted+=copy;if(painted.size>512)fail(FormFailure.LIMIT)};path=null}

            fun numbers(n:Int):List<Float>{if(args.size!=n||args.any{it !is COSNumber})fail();return args.map{(it as COSNumber).floatValue().also{v->if(!v.isFinite())fail()}}}
            for((index,t) in ts.withIndex()){
                b.step();if(t !is Operator){if(args.size>=8)fail();args+=t;continue}
                when(t.name){
                    "q"->{numbers(0);if(inside||++depth>16)fail();colors.addLast(DrawState(black,lineWidth,miter,font,size))}
                    "Q"->{numbers(0);if(inside||depth--<=0)fail();val saved=colors.removeLast();black=saved.black;lineWidth=saved.width;miter=saved.miter;font=saved.font;size=saved.size}
                    "cm"->{val a=numbers(6);if(inside||a!=listOf(1f,0f,0f,1f,0f,0f))fail()}
                    "BT"->{numbers(0);if(inside)fail();inside=true;shown=false;x=Float.NaN;y=Float.NaN}
                    "ET"->{numbers(0);if(!inside)fail();inside=false}
                    "Tf"->{if(!inside||args.size!=2)fail();font=args[0]as?COSName?:fail();size=(args[1]as?COSNumber)?.floatValue()?:fail();if(!size.isFinite()||size !in 6f..72f)fail()}
                    "TL"->{numbers(1);if(!inside)fail()}
                    "Tm"->{val a=numbers(6);if(!inside||shown||a.take(4)!=listOf(1f,0f,0f,1f))fail();x=a[4];y=a[5]}
                    "T*"->{numbers(0);if(!inside||!shown)fail()}
                    "Tj"->{if(!inside||shown||args.size!=1||!black||!x.isFinite()||!y.isFinite())fail();shown=true
                        val raw=args.single()as?COSString?:fail();val f=page.resources?.getFont(font?:fail())as?PDType1Font?:fail()
                        val enc=f.cosObject.getDictionaryObject(COSName.ENCODING)
                        if(f.name!="Helvetica"||f.isEmbedded||enc!=COSName.WIN_ANSI_ENCODING||f.cosObject.keySet().any{it.name !in setOf("Type","Subtype","BaseFont","Encoding","Name")})fail()
                        val text=raw.bytes.inputStream().use{input->buildString{while(input.available()>0){val code=f.readCode(input);append(f.toUnicode(code)?:fail())}}}
                        if(text.isEmpty()||text.length>1024||!FormLimits.text(text,false)||!f.encode(text).contentEquals(raw.bytes))fail()
                        val width=f.getStringWidth(text)/1000f*size
                        if(width<=0||x-size*.2f<box.lowerLeftX||x+width+size*.2f>box.upperRightX||y-size*.3f<box.lowerLeftY||y+size>box.upperRightY)fail()
                        runs+=TextRun(pageIndex+1,index,text,x,y,size,width,font!!.name);if(runs.size>128||runs.sumOf{it.text.length}>FormLimits.TOTAL)fail(FormFailure.LIMIT)
                    }
                    "g"->{black=numbers(1)==listOf(0f);if(inside)fail()}
                    "rg"->{black=numbers(3)==listOf(0f,0f,0f);if(inside)fail()}
                    "k"->{black=numbers(4)==listOf(0f,0f,0f,1f);if(inside)fail()}
                    "w"->{lineWidth=numbers(1).single();if(inside||lineWidth !in 0f..72f)fail()}
                    "M"->{miter=numbers(1).single();if(inside||miter !in 1f..20f)fail()}
                    "G","J","j"->{numbers(1);if(inside)fail()}
                    "RG"->{numbers(3);if(inside)fail()}
                    "K"->{numbers(4);if(inside)fail()}
                    "re"->{val a=numbers(4);if(inside)fail();point(a[0],a[1]);point(a[0]+a[2],a[1]+a[3])}
                    "m","l"->{val a=numbers(2);if(inside)fail();point(a[0],a[1])}
                    "c"->{val a=numbers(6);if(inside)fail();a.chunked(2).forEach{point(it[0],it[1])}}
                    "v","y"->{val a=numbers(4);if(inside)fail();a.chunked(2).forEach{point(it[0],it[1])}}
                    "h"->{numbers(0);if(inside)fail()}
                    "n"->{numbers(0);if(inside)fail();path=null}
                    "S","s","f","F","f*","B","B*","b","b*"->{numbers(0);if(inside)fail();paint(t.name !in setOf("f","F","f*"))}
                    else->fail()
                };args.clear()
            }
            if(inside||depth!=0||args.isNotEmpty())fail()
            // Conservative ink envelopes reject occlusion and nearby graphics/text rather than
            // claim that changing an invisible or overlapped operand is faithful visible editing.
            val pageRuns=runs.filter{it.page==pageIndex+1}
            fun envelope(r:TextRun)=RectF(r.x-r.size*.2f,r.y-r.size*.3f,r.x+r.width+r.size*.2f,r.y+r.size)
            pageRuns.forEachIndexed{i,r->val ink=envelope(r);painted.forEach{g->b.step();if(RectF.intersects(ink,g))fail()};pageRuns.take(i).forEach{other->b.step();if(RectF.intersects(ink,envelope(other)))fail()}}

        }
        if(runs.isEmpty())fail();return Parsed(TextDocument(runs.toList(),pdf.numberOfPages),streams)
    }
    fun inspect(source:File,scratch:File,check:()->Unit={}):TextDocument {val b=FormBudget(check);return FormPdfEngine.load(source,scratch,b).use{parse(it,b).document}}
    fun prepare(source:File,output:File,scratch:File,expected:TextDocument,id:String,replacement:String,check:()->Unit={}) {
        val run=expected.runs.singleOrNull{it.id==id}?:fail();if(output.exists()||replacement==run.text||replacement.isEmpty()||!FormLimits.text(replacement,false))fail(FormFailure.FIT)
        val font=PDType1Font.HELVETICA
        try{font.encode(replacement)}catch(_:IllegalArgumentException){fail(FormFailure.FIT)}
        if(font.getStringWidth(replacement)/1000f*run.size>run.width+.001f)fail(FormFailure.FIT)
        val b=FormBudget(check)
        FormPdfEngine.load(source,scratch,b).use{pdf->val parsed=parse(pdf,b);if(parsed.document!=expected)fail(FormFailure.SOURCE_CHANGED)
            val tokens=parsed.pages[run.page-1].toMutableList();if(tokens[run.token] !is Operator||tokens[run.token-1] !is COSString)fail()
            val page=pdf.getPage(run.page-1);val sourceFont=page.resources.getFont(COSName.getPDFName(run.resource));tokens[run.token-1]=COSString(sourceFont.encode(replacement));val stream=PDStream(pdf)
            stream.createOutputStream(COSName.FLATE_DECODE).use{ContentStreamWriter(it).writeTokens(tokens)};page.setContents(stream)
            FileOutputStream(output).use{out->val bounded=object:OutputStream(){var n=0L;override fun write(v:Int){b.step(1);if(++n>FormLimits.OUTPUT)fail(FormFailure.LIMIT);out.write(v)};override fun write(v:ByteArray,o:Int,len:Int){b.step(len);n+=len;if(n>FormLimits.OUTPUT)fail(FormFailure.LIMIT);out.write(v,o,len)}};pdf.save(bounded)}
        }
        val saved=inspect(output,scratch,check)
        if(saved.runs.size!=expected.runs.size||saved.pages!=expected.pages)fail()
        expected.runs.zip(saved.runs).forEach{(a,z)->if(z!=a.copy(text=if(a.id==id)replacement else a.text,width=if(a.id==id)font.getStringWidth(replacement)/1000f*a.size else a.width))fail()}
        check()
    }
}
