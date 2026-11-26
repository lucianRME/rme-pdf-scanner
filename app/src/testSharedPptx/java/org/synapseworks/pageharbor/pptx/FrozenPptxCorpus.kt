package org.synapseworks.pageharbor.pptx

import org.synapseworks.pageharbor.backup.format.*
import org.synapseworks.pageharbor.docx.*
import org.synapseworks.pageharbor.ocr.*

/** Reads unchanged independent JSON; production never parses source PPTX or this test schema. */
internal object FrozenPptxCorpus {
    fun pages(json:String,size:PptxSize,media:(String,Int,Int)->PptxMedia):List<PptxPage> {
        val root=parseJsonObject("{\"pages\":$json}",BackupFormatLimits())
        return (root.fields.getValue("pages")as JsonArray).values.map{value->
            val p=value as JsonObject
            fun string(k:String)=(p.fields.getValue(k)as JsonString).value
            fun num(k:String)=(p.fields.getValue(k)as JsonNumber).token.toInt()
            val name=string("name");val w=num("width");val h=num("height")
            fun box(v:JsonValue):DocxBox {val a=(v as JsonArray).values.map{(it as JsonNumber).token.toFloat()};return DocxBox(a[0],a[1],a[0]+a[2],a[1]+a[3])}
            val lines=(p.fields.getValue("lines")as JsonArray).values.map{v->val row=(v as JsonObject).fields;val b=(row.getValue("bounds")as JsonArray).values.map{(it as JsonNumber).token.toFloat()};OcrTextLine((row.getValue("text")as JsonString).value,OcrTextBounds(b[0],b[1],b[2],b[3]))}
            val source=DocxReconstructor.page(OcrPageResult(0,lines.joinToString("\n"){it.text},layout=OcrPageLayout(w,h,lines=lines)))
            val visual=media(name,w,h);var next=0;val scale=minOf(size.width/w,size.height/h)
            val items=(p.fields.getValue("objects")as JsonArray).values.mapIndexed{i,v->
                val o=(v as JsonObject).fields;val kind=PptxKind.valueOf((o.getValue("kind")as JsonString).value);val b=box(o.getValue("box"))
                val text=(o["text"]as? JsonString)?.value.orEmpty();val ids=if(kind==PptxKind.TEXT)text.lines().map{next++}else emptyList()
                PptxItem(i,kind,b,text,ids,((o["font"]as? JsonNumber)?.token?.toFloat()?:18f)*scale,
                    color=(o["color"]as? JsonString)?.value?:"000000",media=if(kind==PptxKind.IMAGE)media("$name-figure",(b.width*w).toInt(),(b.height*h).toInt())else null,sourceRegion=if(kind==PptxKind.IMAGE)b else null,corrected=true)
            }
            PptxPage(source,visual,items,coverage=true)
        }
    }
}
