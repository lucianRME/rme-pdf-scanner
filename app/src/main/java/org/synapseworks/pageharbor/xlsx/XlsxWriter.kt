package org.synapseworks.pageharbor.xlsx

import java.io.*
import java.math.BigDecimal
import java.util.zip.*
import org.synapseworks.pageharbor.receipt.*

/** Fixed write-only SpreadsheetML subset. No source ZIP/template parser or executable input. */
internal object XlsxWriter {
    private const val S="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private data class Cell(val value:String="",val type:XlsxTyping=XlsxTyping(),val style:Int=0,val formula:String?=null,val cached:String?=null) {
        override fun toString()="Cell(<redacted>)"
    }
    private data class Sheet(val name:String,val rows:List<List<Cell>>,val merges:List<String> = emptyList(),val freeze:Int=4) {
        override fun toString()="Sheet(<redacted>)"
    }
    private fun t(s:String,style:Int=0)=Cell(s,style=style)
    private fun heading(values:List<String>)=values.map{t(it,2)}
    private fun money(s:String,currency:String)=Cell(s,XlsxTyping(XlsxType.MONEY,ReceiptDecimal.DOT,currency=currency))
    private fun date(s:String)=if(s.isEmpty())Cell("",XlsxTyping(XlsxType.BLANK))else Cell(s,XlsxTyping(XlsxType.DATE))
    private fun sheets(book:XlsxBook,check:()->Unit):List<Sheet> {
        require(book.problem()==null)
        val result=mutableListOf<Sheet>();val sources=mutableListOf<List<Cell>>()
        sources+=listOf(t("Sources and review evidence",1));sources+=listOf(t("Capture/page/cell references. Manual entries have no OCR geometry.",13));sources+=emptyList<Cell>()
        sources+=heading(listOf("Output cell","Source","Evidence kind","Original reading","Reviewed value","Review status"))
        fun evidence(output:String,source:String,kind:String,original:String,reviewed:String,status:String) {
            val a=original.chunked(256).ifEmpty{listOf("")};val b=reviewed.chunked(256).ifEmpty{listOf("")}
            repeat(maxOf(a.size,b.size)){n->check();require(sources.size<2000);sources+=listOf(output,source,if(n==0)kind else "$kind continuation ${n+1}",a.getOrElse(n){""},b.getOrElse(n){""},status).map{t(it)}}
        }
        fun raw(s:XlsxSource) {
            evidence("", "S${s.number} P1",s.kind,s.raw,"","Captured raw text; not a new recognition result")
            for(c in s.concerns)evidence("","S${s.number} P1","Uncertainty",c,"","Review retained")
        }
        if(book.expenses.isNotEmpty()) {
            val expenseRows=mutableListOf<List<Cell>>()
            expenseRows+=listOf(t(book.template.label,1));expenseRows+=listOf(t(listOf(book.claimant,book.context).filter{it.isNotBlank()}.joinToString(" · ")));expenseRows+=emptyList<Cell>()
            expenseRows+=heading(listOf("Date","Merchant","Category","Purpose","Currency","Amount","Tax","Source","Review"))
            book.expenses.forEachIndexed{i,e->
                check();val row=i+5
                expenseRows+=listOf(date(e.date),t(e.merchant),t(e.category),t(e.purpose),t(e.currency),if(e.amount.isBlank())Cell("",XlsxTyping(XlsxType.BLANK))else if(e.currency.isEmpty())t(e.amount)else money(e.amount,e.currency),if(e.tax.isBlank())Cell("",XlsxTyping(XlsxType.BLANK))else if(e.currency.isEmpty())t(e.tax)else money(e.tax,e.currency),t("S${e.source.number} P1"),t(if(e.amount.isBlank()||e.currency.isBlank())"Confirmed missing fields"else"User confirmed"))
                raw(e.source)
                for(f in ReceiptField.entries) {
                    val target=when(f){ReceiptField.DATE->"A$row";ReceiptField.MERCHANT->"B$row";ReceiptField.CURRENCY->"E$row";ReceiptField.TOTAL->"F$row";ReceiptField.TAX->"G$row";else->""}
                    val ref=e.source.references[f.name]?:"S${e.source.number} P1 ${f.label}"
                    evidence(if(target.isEmpty())""else "Expenses!$target",ref,f.label,e.source.original[f.name].orEmpty(),e.values[f].orEmpty(),if(f in e.corrected)"Receipt value corrected"else e.source.suggestions[f.name].orEmpty())
                    e.source.evidence[f.name].orEmpty().forEach{evidence("",ref,"Original field evidence",it,"","")}
                    val final=when(f){ReceiptField.DATE->e.date;ReceiptField.MERCHANT->e.merchant;ReceiptField.CURRENCY->e.currency;ReceiptField.TAX->e.tax;else->e.values[f].orEmpty()}
                    if(target.isNotEmpty()&&f!=ReceiptField.TOTAL&&final!=e.values[f].orEmpty())evidence("Expenses!$target",ref,"Workbook correction",e.values[f].orEmpty(),final,"User corrected in workbook")
                }
                evidence("Expenses!C$row","S${e.source.number} P1 manual addition","Category","",e.category,"User supplied")
                evidence("Expenses!D$row","S${e.source.number} P1 manual addition","Purpose","",e.purpose,"User supplied")
                evidence("Expenses!F$row","S${e.source.number} P1 Total","Signed expense amount",e.values[ReceiptField.TOTAL].orEmpty(),e.amount,"User confirmed; no refund sign inferred")
            }
            val summary=mutableListOf<List<Cell>>()
            summary+=listOf(t("Expense totals",1));summary+=listOf(t("Fixed reviewed rows. Missing amounts or changed currencies leave totals blank.",13));summary+=emptyList<Cell>();summary+=heading(listOf("Currency","Total","Status","Reviewed rows"))
            for((currency,entries)in book.expenses.withIndex().groupBy{it.value.currency}.toSortedMap()) {
                val amounts=entries.joinToString(","){"'Expenses'!F${it.index+5}"}
                val currencies=entries.joinToString(","){"'Expenses'!E${it.index+5},\"$currency\""}
                val count=entries.size;val complete=currency.isNotEmpty()&&entries.all{it.value.amount.isNotEmpty()}
                val value=if(complete)entries.fold(BigDecimal("0.00")){a,e->a+BigDecimal(e.value.amount)}.toPlainString()else ""
                val total=if(currency.isEmpty())Cell("",XlsxTyping(XlsxType.BLANK))else Cell("",XlsxTyping(XlsxType.MONEY,currency=currency),formula="IF(COUNT($amounts)=$count,IF(COUNTIFS($currencies)=1,ROUND(SUM($amounts),2),\"\"),\"\")",cached=value)
                val status=if(currency.isEmpty())t("Currency missing")else Cell("",formula="IF(COUNT($amounts)=$count,IF(COUNTIFS($currencies)=1,\"Complete\",\"Currency changed\"),\"Missing amount\")",cached=if(complete)"Complete"else"Missing amount")
                summary+=listOf(t(currency.ifEmpty{"Unassigned"}),total,status,Cell(count.toString(),XlsxTyping(XlsxType.NUMBER,ReceiptDecimal.DOT),style=12))
            }
            result+=Sheet("Totals",summary,freeze=0);result+=Sheet("Expenses",expenseRows)
        }
        for(table in book.tables) {
            check();raw(table.source)
            val rows=mutableListOf<List<Cell>>(listOf(t(table.name,1)),listOf(t("Source S${table.source.number} page 1",13)),emptyList())
            table.grid.rows.forEachIndexed{r,row->rows+=row.mapIndexed{c,cell->
                check();val ref=table.source.references[cell.id]?:"S${table.source.number} P1 manual addition"
                val target="${table.name}!${XlsxText.cell(r+4,c)}"
                evidence(target,ref,"Cell ${(table.types[cell.id]?:XlsxTyping()).type}",table.source.original[cell.id].orEmpty(),cell.value,when{cell.corrected->"User corrected; original retained";cell.uncertain->"Uncertain; user confirmed";else->"User confirmed"})
                val typing=table.types[cell.id]?:XlsxTyping()
                if(typing.type !in setOf(XlsxType.TEXT,XlsxType.BLANK))evidence(target,ref,"Confirmed interpretation",cell.value,XlsxValues.value(cell.value,typing).orEmpty(),"${typing.type}; ${typing.decimals}; ${typing.dates}; ${typing.currency}")
                table.source.evidence[cell.id].orEmpty().forEach{evidence(target,ref,"Original OCR evidence",it,"","")}
                Cell(cell.value,table.types[cell.id]?:XlsxTyping())
            }}
            val merges=table.merges.map{"${XlsxText.cell(it.top+4,it.left)}:${XlsxText.cell(it.bottom+4,it.right)}"}
            result+=Sheet(table.name,rows,merges)
        }
        result+=Sheet("Sources",sources)
        require(result.size<=8&&result.sumOf{it.rows.sumOf{r->r.size}}<=20000&&result.sumOf{it.rows.sumOf{r->r.sumOf{c->c.value.length.toLong()}}}<=524288)
        return result
    }
    fun write(book:XlsxBook,output:File,check:()->Unit={},progress:(Int)->Unit={}) {
        require(!output.exists()&&output.parentFile?.isDirectory==true)
        var accepted=false
        try {
            val sheets=sheets(book.owned(),check);var xmlBytes=0L
            FileOutputStream(output).use{raw->
                val bounded=object:FilterOutputStream(raw){var count=0L
                    override fun write(b:Int){check();require(++count<=XLSX_MAX);out.write(b)}
                    override fun write(b:ByteArray,off:Int,len:Int){check();count+=len;require(count<=XLSX_MAX);out.write(b,off,len)}
                }
                ZipOutputStream(bounded).use{zip->
                    fun part(name:String,xml:String){check();val bytes=xml.toByteArray(Charsets.UTF_8);xmlBytes+=bytes.size;require(xmlBytes<=XLSX_MAX);zip.putNextEntry(ZipEntry(name).apply{time=315532800000L});zip.write(bytes);zip.closeEntry()}
                    part("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.indices.joinToString(""){"<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}}</Types>""")
                    part("_rels/.rels","""<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="book" Type="$R/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                    part("xl/workbook.xml","""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$S" xmlns:r="$R"><workbookPr date1904="0"/><bookViews><workbookView activeTab="0"/></bookViews><sheets>${sheets.mapIndexed{i,s->"<sheet name=\"${XlsxText.xml(s.name)}\" sheetId=\"${i+1}\" r:id=\"sheet${i+1}\"/>"}.joinToString("")}</sheets><calcPr calcId="191029" calcMode="auto" fullCalcOnLoad="1" forceFullCalc="1"/></workbook>""")
                    part("xl/_rels/workbook.xml.rels","""<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="$R/styles" Target="styles.xml"/>${sheets.indices.joinToString(""){"<Relationship Id=\"sheet${it+1}\" Type=\"$R/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>"}}</Relationships>""")
                    part("xl/styles.xml",styles())
                    sheets.forEachIndexed{i,s->part("xl/worksheets/sheet${i+1}.xml",sheet(s,check));progress(i+1)}
                }
            }
            check();FileOutputStream(output,true).use{it.fd.sync()};require(output.length() in 1..XLSX_MAX);accepted=true
        }finally{if(!accepted)output.delete()}
    }
    private fun style(c:Cell)=if(c.style!=0)c.style else when(c.type.type){XlsxType.DATE->3;XlsxType.NUMBER->BigDecimal(requireNotNull(XlsxValues.value(c.value,c.type))).scale().let{if(it==0)12 else 13+it};XlsxType.MONEY->5+ReceiptValues.currencies.toList().indexOf(c.type.currency);else->0}
    private fun sheet(sheet:Sheet,check:()->Unit)=buildString {
        val columns=sheet.rows.maxOf{it.size};val last=XlsxText.cell(sheet.rows.size,columns-1)
        append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$S"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><dimension ref="A1:$last"/><sheetViews><sheetView workbookViewId="0" showGridLines="0">""")
        if(sheet.freeze>0)append("""<pane ySplit="${sheet.freeze}" topLeftCell="A${sheet.freeze+1}" activePane="bottomLeft" state="frozen"/>""")
        append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols>")
        repeat(columns){c->val width=when(sheet.name){"Sources"->if(c==3||c==4)38 else 26;"Totals"->if(c==2)28 else 20;"Expenses"->if(c in setOf(1,2,3,7,8))24 else 18;else->24};append("<col min=\"${c+1}\" max=\"${c+1}\" width=\"$width\" customWidth=\"1\"/>")};append("</cols><sheetData>")
        sheet.rows.forEachIndexed{r,row->
            check();val height=row.maxOfOrNull{cell->if(cell.style==13)20 else cell.value.lines().sumOf{maxOf(1,(it.codePointCount(0,it.length)+15)/16)}*15+4}?.coerceAtLeast(20)?:20
            require(height<=400);append("<row r=\"${r+1}\" ht=\"$height\" customHeight=\"1\">")
            row.forEachIndexed{c,value->
                val address=XlsxText.cell(r+1,c);val st=style(value);require(st in 0..19)
                if(value.formula!=null) {
                    // Only sheets() constructs formulas, from bounded row numbers and whitelisted currencies.
                    val cached=value.cached.orEmpty();val numeric=value.type.type==XlsxType.MONEY&&cached.isNotEmpty()
                    append("<c r=\"$address\" s=\"$st\" t=\"${if(numeric)"n"else"str"}\"><f>${XlsxText.xml(value.formula)}</f><v>${XlsxText.xml(cached)}</v></c>")
                }else if(value.type.type==XlsxType.BLANK)append("<c r=\"$address\" s=\"$st\"/>")
                else if(value.type.type==XlsxType.TEXT)append("<c r=\"$address\" s=\"$st\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${XlsxText.xml(value.value)}</t></is></c>")
                else {val v=requireNotNull(XlsxValues.value(value.value,value.type));val n=if(value.type.type==XlsxType.DATE)XlsxValues.serial(v).toString()else v;append("<c r=\"$address\" s=\"$st\" t=\"n\"><v>$n</v></c>")}
            };append("</row>")
        };append("</sheetData>")
        if(sheet.merges.isNotEmpty())append("<mergeCells count=\"${sheet.merges.size}\">${sheet.merges.joinToString(""){"<mergeCell ref=\"$it\"/>"}}</mergeCells>")
        append("""<pageMargins left="0.3" right="0.3" top="0.4" bottom="0.4" header="0" footer="0"/><pageSetup paperSize="8" orientation="landscape" fitToWidth="1" fitToHeight="0"/></worksheet>""")
    }
    private fun styles()=buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$S"><numFmts count="15"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/><numFmt numFmtId="165" formatCode="0.######"/>""")
        ReceiptValues.currencies.forEachIndexed{i,c->append("<numFmt numFmtId=\"${166+i}\" formatCode=\"0.00&quot; $c&quot;\"/>")}
        for(scale in 1..6)append("<numFmt numFmtId=\"${172+scale}\" formatCode=\"0.${"0".repeat(scale)}\"/>")
        append("""</numFmts><fonts count="3"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="14"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="10"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF344054"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="20">""")
        for(i in 0..19){val num=when{i==0->49;i>=14->173+i-14;i==3->164;i==4->165;i==12->1;i==13->0;i>=5->166+i-5;else->0};append("<xf numFmtId=\"$num\" fontId=\"${if(i==1)1 else if(i==2)2 else 0}\" fillId=\"${if(i==2)2 else 0}\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment horizontal=\"${if(i==2)"center"else if(i in 3..12||i>=14)"right"else"left"}\" vertical=\"top\" wrapText=\"${if(i==13)0 else 1}\"/></xf>")}
        append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
    }
}
