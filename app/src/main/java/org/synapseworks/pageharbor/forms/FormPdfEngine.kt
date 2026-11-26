package org.synapseworks.pageharbor.forms

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.*
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import java.io.*
import java.util.*

/** No scripting handler, flattening, external content, original-file writes or logged exceptions. */
internal object FormPdfEngine {
    private fun name(s:String)=COSName.getPDFName(s)
    private fun fail(r:FormFailure=FormFailure.MALFORMED):Nothing=throw FormException(r)
    private class CheckedRead(private val d:RandomAccessRead,private val b:FormBudget):RandomAccessRead by d {
        override fun read():Int{b.step(1);return d.read()}
        override fun read(x:ByteArray):Int=read(x,0,x.size)
        override fun read(x:ByteArray,o:Int,n:Int):Int{b.step(n);return d.read(x,o,n)}
        override fun seek(p:Long){b.step();d.seek(p)}
        override fun getPosition():Long{b.step();return d.position}
        override fun length():Long{b.step();return d.length()}
        override fun peek():Int{b.step(1);return d.peek()}
        override fun rewind(n:Int){b.step();d.rewind(n)}
        override fun readFully(n:Int):ByteArray{b.step(n);return d.readFully(n)}
        override fun isEOF():Boolean{b.step();return d.isEOF}
        override fun available():Int{b.step();return d.available()}
    }
    internal fun load(source:File,scratch:File,b:FormBudget):PDDocument {
        if(!source.isFile||source.length() !in 1..FormLimits.OUTPUT)fail(FormFailure.LIMIT)
        source.inputStream().use{val head=ByteArray(8);if(it.read(head)!=8||!String(head,Charsets.ISO_8859_1).matches(Regex("%PDF-1\\.[0-7]")))fail()}
        val storage=ScratchFile(MemoryUsageSetting.setupMixed(4L*1024*1024,FormLimits.SCRATCH).setTempDir(scratch))
        val input=CheckedRead(RandomAccessBufferedFileInputStream(source),b)
        try {val parser=PDFParser(input,storage);parser.isLenient=false;parser.parse();b.step();return parser.pdDocument}
        catch(e:Throwable){input.close();storage.close();throw e}
    }
    fun inspect(source:File,scratch:File,check:()->Unit={}):FormModel {
        val budget=FormBudget(check);return load(source,scratch,budget).use{model(it,budget).first}
    }
    private val forbidden=setOf("A","AA","OpenAction","JS","JavaScript","XFA","EmbeddedFiles","AF","Collection","RichMediaContent","RichMediaSettings","3DD","ByteRange","Perms","DocMDP","UR","UR3")
    private fun model(pdf:PDDocument,b:FormBudget):Pair<FormModel,List<PDField>> {
        if(pdf.isEncrypted)fail(FormFailure.ENCRYPTED)
        if(pdf.document.xrefTable.size>10000)fail(FormFailure.LIMIT)
        val seen=Collections.newSetFromMap(IdentityHashMap<COSBase,Boolean>())
        val indirect=ArrayDeque<COSBase>()
        fun walk(value:COSBase?,depth:Int){
            if(value==null)return;b.step();if(!seen.add(value))return;b.node(depth)
            when(value){
                is COSObject->value.`object`?.let{indirect.add(it)}
                is COSDictionary->{if(value.keySet().any{it.name in forbidden}||value.getNameAsString(COSName.TYPE) in setOf("Sig","EmbeddedFile","Action","Filespec")||value.getNameAsString(COSName.S) in setOf("JavaScript","Launch","SubmitForm","ResetForm","ImportData","GoTo","GoToR","GoToE","URI","Named","Hide","Sound","Movie","SetOCGState","Rendition","Trans","Thread")||(value is COSStream&&value.containsKey(COSName.F)))fail(FormFailure.ACTIVE)
                    if(value.containsKey(COSName.FT)&&value.getNameAsString(COSName.FT)!in setOf("Tx","Btn","Ch"))fail(if(value.getNameAsString(COSName.FT)=="Sig")FormFailure.ACTIVE else FormFailure.UNSUPPORTED)
                    if(value.getNameAsString(COSName.FT)=="Btn"&&value.getInt(COSName.FF,0)and (1 shl 16)!=0)fail(FormFailure.UNSUPPORTED)
                    value.values.forEach{walk(it,depth+1)}}
                is COSArray->value.forEach{walk(it,depth+1)}
            }
        }
        walk(pdf.document.trailer,0);pdf.document.objects.toList().forEach{indirect.add(it)}
        while(indirect.isNotEmpty()){b.step();walk(indirect.removeFirst(),0)}
        val catalog=pdf.documentCatalog.cosObject
        val pageRoot=catalog.getDictionaryObject(COSName.PAGES)as?COSDictionary?:fail()
        val pageNodes=mutableListOf<COSDictionary>();val pageSeen=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>())
        fun pages(d:COSDictionary,depth:Int,parent:COSDictionary?){
            b.node(depth);if(depth>8||!pageSeen.add(d))fail()
            if(parent!=null&&d.getDictionaryObject(COSName.PARENT)!==parent)fail()
            when(d.getNameAsString(COSName.TYPE)){
                "Pages"->{val kids=d.getDictionaryObject(COSName.KIDS)as?COSArray?:fail();if(kids.size()>32)fail(FormFailure.LIMIT);val before=pageNodes.size;kids.forEach{pages((if(it is COSObject)it.`object`else it)as?COSDictionary?:fail(),depth+1,d)};if(d.getInt(COSName.COUNT)!=pageNodes.size-before)fail()}
                "Page"->{pageNodes+=d;if(pageNodes.size>32)fail(FormFailure.LIMIT)}
                else->fail()
            }
        }
        pages(pageRoot,0,null);if(pageNodes.isEmpty())fail()
        val pageWidgets=IdentityHashMap<COSDictionary,Int>()
        val pageObjects=pageNodes.mapIndexed{index,d->val p=PDPage(d);val box=p.cropBox
            if(!listOf(box.lowerLeftX,box.lowerLeftY,box.upperRightX,box.upperRightY).all{it.isFinite()}||box.width !in 32f..14400f||box.height !in 32f..14400f||p.rotation !in listOf(0,90,180,270))fail(FormFailure.UNSUPPORTED)
            val annotations=d.getDictionaryObject(COSName.ANNOTS)as?COSArray
            annotations?.forEach{ref->b.step();val w=(if(ref is COSObject)ref.`object`else ref)as?COSDictionary?:fail()
                if(w.getNameAsString(COSName.SUBTYPE)!="Widget"||pageWidgets.put(w,index+1)!=null)fail(FormFailure.UNSUPPORTED)
                if(w.getInt(COSName.F,0)and 4.inv()!=0)fail(FormFailure.UNSUPPORTED)
                if(w.getDictionaryObject(COSName.P)?.let{it!==d}==true)fail()
            };p
        }
        if(pageWidgets.size !in 1..FormLimits.WIDGETS)fail(FormFailure.UNSUPPORTED)
        val dict=catalog.getDictionaryObject(COSName.ACRO_FORM)as?COSDictionary?:fail(FormFailure.UNSUPPORTED)
        if(dict.getInt(COSName.SIG_FLAGS,0)!=0||dict.containsKey(name("CO")))fail(FormFailure.ACTIVE)
        val roots=dict.getDictionaryObject(COSName.FIELDS)as?COSArray?:fail()
        val leafNodes=mutableListOf<COSDictionary>();val allFields=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>());val ids=mutableSetOf<String>()
        fun field(d:COSDictionary,parent:COSDictionary?,prefix:String,depth:Int){
            b.node(depth);if(depth>8||!allFields.add(d)||allFields.size>128)fail()
            if(d.getDictionaryObject(COSName.PARENT)!==parent)fail()
            val local=d.getString(COSName.T)?:fail();if(local.isEmpty()||'.' in local||!FormLimits.label(local,80))fail(FormFailure.UNSUPPORTED)
            val id=if(prefix.isEmpty())local else "$prefix.$local";if(!FormLimits.label(id)||!ids.add(id))fail()
            val kids=d.getDictionaryObject(COSName.KIDS)as?COSArray
            val child=kids?.map{(if(it is COSObject)it.`object`else it)as?COSDictionary?:fail()}.orEmpty()
            val leaf=child.isEmpty()||child.all{it.getNameAsString(COSName.SUBTYPE)=="Widget"&&!it.containsKey(COSName.T)&&!it.containsKey(COSName.KIDS)}
            if(leaf){leafNodes+=d;if(leafNodes.size>FormLimits.FIELDS)fail(FormFailure.LIMIT)
                if(child.isEmpty()){if(!pageWidgets.containsKey(d))fail()}else child.forEach{if(it.getDictionaryObject(COSName.PARENT)!==d||!pageWidgets.containsKey(it)||it.containsKey(COSName.FT)&&it.getNameAsString(COSName.FT)!=d.getNameAsString(COSName.FT)||it.containsKey(COSName.V)||it.containsKey(COSName.FF))fail()}}
            else child.forEach{field(it,d,id,depth+1)}
        }
        roots.forEach{field((if(it is COSObject)it.`object`else it)as?COSDictionary?:fail(),null,"",0)}
        if(leafNodes.isEmpty())fail(FormFailure.UNSUPPORTED)
        // The raw graph has been bounded/validated before any PDFBox field-tree traversal.
        val acro=PDAcroForm(pdf,dict);val typed=acro.fieldTree.filter{it !is PDNonTerminalField}.toList()
        if(typed.size!=leafNodes.size||typed.map{it.cosObject}.toSet()!=leafNodes.toSet())fail()
        val used=Collections.newSetFromMap(IdentityHashMap<COSDictionary,Boolean>())
        val fields=typed.map{f->b.step();val id=f.fullyQualifiedName;val label=f.alternateFieldName?.takeIf{it.isNotEmpty()}?:id
            if(!FormLimits.label(label)||!FormLimits.label(id))fail(FormFailure.UNSUPPORTED)
            val widgets=f.widgets;if(widgets.isEmpty())fail(FormFailure.UNSUPPORTED)
            val numbers=widgets.map{w->if(!used.add(w.cosObject))fail();val page=pageWidgets[w.cosObject]?:fail()
                val r=w.rectangle?:fail();val box=pageObjects[page-1].cropBox
                if(!listOf(r.lowerLeftX,r.lowerLeftY,r.upperRightX,r.upperRightY).all{it.isFinite()}||r.width !in 12f..1024f||r.height !in 16f..1024f||r.lowerLeftX<box.lowerLeftX||r.lowerLeftY<box.lowerLeftY||r.upperRightX>box.upperRightX||r.upperRightY>box.upperRightY||w.appearanceCharacteristics?.rotation?.let{it!=0}==true)fail(FormFailure.UNSUPPORTED)
                page
            }.distinct().sorted()
            var options=listOf<FormOption>();var multi=false;var max=FormLimits.VALUE;var noToggle=false
            val pair=when(f){
                is PDTextField->{if(f.isPassword||f.isFileSelect||f.isComb||f.isRichText||f.cosObject.containsKey(COSName.RV))fail(FormFailure.UNSUPPORTED);multi=f.isMultiline;max=f.maxLen.let{if(it<0)fail()else if(it==0)FormLimits.VALUE else minOf(it,FormLimits.VALUE)};FormKind.TEXT to listOf(f.value)}
                is PDCheckBox,is PDRadioButton->{val button=f as PDButton;if(button.exportValues.isNotEmpty())fail(FormFailure.UNSUPPORTED)
                    val values=widgets.map{w->val normal=w.appearance?.normalAppearance?.subDictionary?:fail(FormFailure.UNSUPPORTED);val on=normal.keys.map{it.name}.filter{it!="Off"};if(on.size!=1||!normal.containsKey(COSName.Off))fail(FormFailure.UNSUPPORTED);on.single()}
                    if(values.any{it.isEmpty()||!FormLimits.label(it,80)})fail(FormFailure.UNSUPPORTED)
                    if(f is PDCheckBox&&values.distinct().size!=1)fail(FormFailure.UNSUPPORTED)
                    if(f is PDRadioButton&&values.distinct().size!=values.size&&!f.isRadiosInUnison)fail(FormFailure.UNSUPPORTED)
                    options=values.distinct().map{FormOption(it,it)};noToggle=f is PDRadioButton&&f.fieldFlags and (1 shl 14)!=0
                    (if(f is PDCheckBox)FormKind.CHECK else FormKind.RADIO)to listOf(button.value)
                }
                is PDChoice->{if(f is PDComboBox&&f.isEdit||f.isCombo&&f.isMultiSelect)fail(FormFailure.UNSUPPORTED)
                    val exports=f.optionsExportValues;val labels=f.optionsDisplayValues
                    if(exports.size !in 1..FormLimits.OPTIONS||labels.size!=exports.size||exports.distinct().size!=exports.size)fail(FormFailure.UNSUPPORTED)
                    if(exports.any{!FormLimits.label(it)}||labels.any{!FormLimits.label(it)||!FormLimits.text(it,false)})fail(FormFailure.UNSUPPORTED)
                    options=exports.zip(labels).map{FormOption(it.first,it.second)}
                    (if(f.isMultiSelect)FormKind.MULTI else FormKind.CHOICE)to f.value.toList()
                }
                else->fail(FormFailure.UNSUPPORTED)
            }
            FormField(id,label,pair.first,pair.second,f.isReadOnly,f.isRequired,numbers,options,multi,max,noToggle,f.fieldFlags,f is PDListBox).also{if(!fits(it,it.initial,widgets))fail(FormFailure.UNSUPPORTED)}
        }
        if(used.size!=pageWidgets.size)fail()
        val result=FormModel(fields,pageNodes.size);if(!result.valid(result.initial()))fail(FormFailure.UNSUPPORTED)
        return result to typed
    }
    private fun fits(f:FormField,value:List<String>,widgets:List<PDAnnotationWidget>):Boolean {
        if(f.kind !in listOf(FormKind.TEXT,FormKind.CHOICE,FormKind.MULTI))return true
        val lines=if(f.listBox)f.options.map{it.label}else when(f.kind){FormKind.TEXT->value.singleOrNull()?.split(Regex("\\r\\n|\\r|\\n"))?:return false;FormKind.MULTI->f.options.map{it.label};else->value.map{v->f.options.singleOrNull{it.value==v}?.label?:return false}}
        if(lines.any{!FormLimits.text(it,false)})return false
        return widgets.all{w->val r=w.rectangle;lines.size*12f+8<=r.height&&lines.all{PDType1Font.HELVETICA.getStringWidth(it)/1000f*10f<=r.width-8}}
    }
    fun prepare(source:File,output:File,scratch:File,expected:FormModel,review:FormReview,check:()->Unit={}) {
        if(!expected.valid(review.values)||output.exists())fail(FormFailure.FIT)
        val budget=FormBudget(check)
        load(source,scratch,budget).use{pdf->val (actual,fields)=model(pdf,budget);if(actual!=expected)fail()
            val acro=PDAcroForm(pdf,pdf.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM)as COSDictionary)
            val resources=PDResources(COSDictionary(acro.defaultResources?.cosObject?:COSDictionary()))
            val fonts=COSDictionary(resources.cosObject.getDictionaryObject(COSName.FONT)as?COSDictionary?:COSDictionary());resources.cosObject.setItem(COSName.FONT,fonts)
            var index=0;var key=name("RMEFormFont");while(fonts.containsKey(key)){if(++index>100)fail(FormFailure.LIMIT);key=name("RMEFormFont$index")}
            resources.put(key,PDType1Font.HELVETICA);acro.defaultResources=resources;acro.defaultAppearance="/${key.name} 10 Tf 0 g";acro.needAppearances=false
            fields.forEachIndexed{i,f->budget.step();val spec=expected.fields[i];val v=review.values[spec.id]?:fail()
                if(!fits(spec,v,f.widgets))fail(FormFailure.FIT)
                when(spec.kind){
                    FormKind.TEXT->f.cosObject.setString(COSName.V,v.single())
                    FormKind.CHECK,FormKind.RADIO->f.cosObject.setItem(COSName.V,name(v.single()))
                    FormKind.CHOICE,FormKind.MULTI->{if(v.isEmpty()){f.cosObject.removeItem(COSName.V);f.cosObject.removeItem(COSName.I)}else{
                        f.cosObject.setItem(COSName.V,if(v.size==1)COSString(v.single())else COSArray().apply{v.forEach{add(COSString(it))}})
                        f.cosObject.setItem(COSName.I,COSArray().apply{v.map{value->spec.options.indexOfFirst{it.value==value}}.sorted().forEach{add(COSInteger.get(it.toLong()))}})
                    };f.cosObject.setInt(name("TI"),0)}
                }
                if(f is PDVariableText){f.defaultAppearance=acro.defaultAppearance;f.q=0}
                f.widgets.forEach{w->budget.step();w.cosObject.removeItem(COSName.DA)
                    val ap=PDAppearanceDictionary()
                    if(spec.kind==FormKind.CHECK||spec.kind==FormKind.RADIO){
                        val on=w.appearance!!.normalAppearance.subDictionary.keys.single{it!=COSName.Off}
                        val states=COSDictionary();states.setItem(COSName.Off,appearance(pdf,w,spec,emptyList(),false));states.setItem(on,appearance(pdf,w,spec,emptyList(),true));ap.cosObject.setItem(COSName.N,states)
                        w.setAppearanceState(if(v.single()==on.name)on.name else "Off")
                    }else ap.setNormalAppearance(appearance(pdf,w,spec,v,false))
                    w.appearance=ap
                    w.appearanceCharacteristics=
                        PDAppearanceCharacteristicsDictionary(COSDictionary()).apply{
                            background=com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(floatArrayOf(1f,1f,1f),com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE)
                            borderColour=com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(floatArrayOf(.35f,.35f,.35f),com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE)
                        }
                }
            }
            check();FileOutputStream(output).use{target->val bounded=object:OutputStream(){var bytes=0L;override fun write(x:Int){budget.step(1);if(++bytes>FormLimits.OUTPUT)fail(FormFailure.LIMIT);target.write(x)};override fun write(x:ByteArray,o:Int,n:Int){budget.step(n);bytes+=n;if(bytes>FormLimits.OUTPUT)fail(FormFailure.LIMIT);target.write(x,o,n)}};pdf.save(bounded);bounded.flush()}
        }
        check();load(output,scratch,FormBudget(check)).use{pdf->val (saved,fields)=model(pdf,FormBudget(check));if(saved!=expected.copy(fields=expected.fields.map{it.copy(initial=review.values[it.id]!!)}))fail()
            fields.forEach{f->f.widgets.forEach{w->val n=w.appearance?.normalAppearance?:fail();val stream=if(n.isStream)n.appearanceStream else n.subDictionary[w.appearanceState]?:fail();if(stream.cosObject.length<=0)fail()}}
        };check()
    }
    private fun appearance(pdf:PDDocument,w:PDAnnotationWidget,f:FormField,value:List<String>,on:Boolean):PDAppearanceStream {
        val r=w.rectangle;val width=r.width;val height=r.height
        val ap=PDAppearanceStream(pdf);ap.bBox=PDRectangle(width,height);ap.resources=PDResources()
        PDPageContentStream(pdf,ap).use{c->
            fun circle(radius:Float){val x=width/2;val y=height/2;val k=radius*.55228475f
                c.moveTo(x+radius,y);c.curveTo(x+radius,y+k,x+k,y+radius,x,y+radius);c.curveTo(x-k,y+radius,x-radius,y+k,x-radius,y);c.curveTo(x-radius,y-k,x-k,y-radius,x,y-radius);c.curveTo(x+k,y-radius,x+radius,y-k,x+radius,y);c.closePath()}
            c.setNonStrokingColor(1f);c.addRect(0f,0f,width,height);c.fill();c.setStrokingColor(.35f);c.setLineWidth(.5f)
            if(f.kind==FormKind.RADIO)circle(minOf(width,height)/2-1)else c.addRect(.5f,.5f,width-1,height-1)
            c.stroke();c.setNonStrokingColor(0f);c.setStrokingColor(0f)
            if(f.kind==FormKind.CHECK){if(on){c.setLineWidth(1.5f);c.moveTo(3f,3f);c.lineTo(width-3,height-3);c.moveTo(3f,height-3);c.lineTo(width-3,3f);c.stroke()}}
            else if(f.kind==FormKind.RADIO){if(on){circle(minOf(width,height)*.25f);c.fill()}}
            else {
                val lines=if(f.listBox)f.options.map{it.label}else when(f.kind){FormKind.TEXT->value.single().split(Regex("\\r\\n|\\r|\\n"));FormKind.MULTI->f.options.map{it.label};else->value.map{v->f.options.single{it.value==v}.label}}
                lines.forEachIndexed{index,text->val y=height-14-index*12
                    if((f.kind==FormKind.MULTI||f.listBox)&&f.options[index].value in value){c.setNonStrokingColor(.8f,.87f,1f);c.addRect(2f,y-2,width-4,12f);c.fill();c.setNonStrokingColor(0f)}
                    c.beginText();c.setFont(PDType1Font.HELVETICA,10f);c.newLineAtOffset(4f,y);c.showText(text);c.endText()
                }
            }
        };return ap
    }
}
