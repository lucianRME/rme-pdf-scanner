package org.synapseworks.pageharbor.xlsx

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.table.*

internal const val XLSX_MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
internal const val XLSX_MAX=4L*1024*1024
internal enum class XlsxType { TEXT, NUMBER, MONEY, DATE, BLANK }
internal data class XlsxTyping(val type:XlsxType=XlsxType.TEXT,val decimals:ReceiptDecimal=ReceiptDecimal.AUTO,
    val dates:ReceiptDateOrder=ReceiptDateOrder.AUTO,val currency:String="")
internal data class XlsxMerge(val top:Int,val left:Int,val bottom:Int,val right:Int) {
    fun cells()=(top..bottom).flatMap{r->(left..right).map{c->r to c}}
}
internal data class XlsxSource(val number:Int,val kind:String,val revision:String,val raw:String,
    val evidence:Map<String,List<String>> = emptyMap(),val original:Map<String,String> = emptyMap(),
    val references:Map<String,String> = emptyMap(),val suggestions:Map<String,String> = emptyMap(),val concerns:List<String> = emptyList()) {
    fun owned()=copy(evidence=evidence.mapValues{it.value.toList()},original=original.toMap(),references=references.toMap(),suggestions=suggestions.toMap(),concerns=concerns.toList())
    override fun toString()="XlsxSource(<redacted>)"
}
internal data class XlsxTable(val name:String,val source:XlsxSource,val grid:TableGrid,
    val types:Map<String,XlsxTyping> = emptyMap(),val merges:List<XlsxMerge> = emptyList(),val confirmed:Boolean=false) {
    fun owned()=copy(source=source.owned(),grid=grid.owned(),types=types.toMap(),merges=merges.toList())
    override fun toString()="XlsxTable(<redacted>)"
}
internal data class XlsxExpense(val source:XlsxSource,val values:Map<ReceiptField,String>,val corrected:Set<ReceiptField>,
    val merchant:String=values[ReceiptField.MERCHANT].orEmpty(),val date:String=values[ReceiptField.DATE].orEmpty(),
    val currency:String=values[ReceiptField.CURRENCY].orEmpty(),val amount:String=values[ReceiptField.TOTAL].orEmpty(),
    val tax:String=values[ReceiptField.TAX].orEmpty(),val category:String="",val purpose:String="",val confirmed:Boolean=false) {
    override fun toString()="XlsxExpense(<redacted>)"
}
internal enum class XlsxTemplate(val label:String) { REGISTER("Expense register"), REIMBURSEMENT("Reimbursement report") }
internal data class XlsxBook(val tables:List<XlsxTable> = emptyList(),val expenses:List<XlsxExpense> = emptyList(),
    val template:XlsxTemplate=XlsxTemplate.REGISTER,val claimant:String="",val context:String="") {
    fun owned()=copy(tables=tables.map{it.owned()},expenses=expenses.map{it.copy(source=it.source.owned(),values=it.values.toMap(),corrected=it.corrected.toSet())})
    fun problem():String?=runCatching {
        require(tables.size<=4&&expenses.size<=12&&tables.size+expenses.size in 1..16)
        require(XlsxText.safe(claimant)&&XlsxText.safe(context)&&claimant.length<=128&&context.length<=128)
        val names=tables.map{it.name};require(names.map{it.lowercase(Locale.ROOT)}.distinct().size==names.size)
        for(t in tables) {
            require(XlsxText.sheetName(t.name)&&t.name.lowercase(Locale.ROOT) !in setOf("sources","expenses","totals"))
            require(t.grid.valid()&&t.confirmed&&t.types.keys.all{id->t.grid.rows.flatten().any{it.id==id}})
            require(t.merges.size<=40)
            val used=mutableSetOf<Pair<Int,Int>>()
            for(m in t.merges) {
                require(m.top in t.grid.rows.indices&&m.bottom in m.top until t.grid.rows.size&&m.left in 0 until t.grid.columns&&m.right in m.left until t.grid.columns)
                require(m.top!=m.bottom||m.left!=m.right)
                for((r,c)in m.cells()){require(used.add(r to c));require(r==m.top&&c==m.left||t.grid.rows[r][c].value.isEmpty())}
            }
            t.grid.rows.flatten().forEach{require(XlsxValues.valid(it.value,t.types[it.id]?:XlsxTyping()))}
        }
        for(e in expenses) {
            require(e.confirmed)
            for(s in listOf(e.merchant,e.category,e.purpose))require(XlsxText.safe(s)&&s.length<=256)
            require(e.currency.isEmpty()||e.currency in ReceiptValues.currencies)
            require(e.date.isEmpty()||XlsxValues.date(e.date,ReceiptDateOrder.AUTO)!=null)
            for(s in listOf(e.amount,e.tax))require(s.isEmpty()||ReceiptValues.amount(s,ReceiptDecimal.DOT)==s)
        }
        val sources=tables.map{it.source}+expenses.map{it.source};require(sources.map{it.number}.distinct().size==sources.size)
        require(sources.all{it.number in 1..16&&it.raw.length<=32768&&it.evidence.size<=480&&it.original.size<=480&&it.concerns.size<=32})
    }.exceptionOrNull()?.let{"Review unsupported values, types, dates, names, merges or workbook limits."}
    override fun toString()="XlsxBook(<redacted>)"
}
internal object XlsxText {
    fun safe(s:String)=s.length<=1024&&TableLimits.safe(s)&&s.none{it=='\uFFFE'||it=='\uFFFF'}
    fun sheetName(s:String)=s.length in 1..31&&s==s.trim()&&s.firstOrNull()!='\''&&s.lastOrNull()!='\''&&s.none{it.isISOControl()||it in "[]:*?/\\"}&&java.text.Normalizer.isNormalized(s,java.text.Normalizer.Form.NFC)&&safe(s)
    fun xml(value:String):String {
        require(safe(value))
        // Escape SpreadsheetML's own lexical escape, so literal _x0041_ never becomes A.
        val s=Regex("(?i)_x[0-9a-f]{4}_").replace(value){"_x005F_"+it.value.substring(1)}
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("\r","&#13;")
    }
    fun cell(row:Int,column:Int):String {require(row in 1..3000&&column in 0..15);return ('A'.code+column).toChar().toString()+row}
}
internal object XlsxValues {
    fun number(raw:String,rule:ReceiptDecimal):String? {
        if(rule==ReceiptDecimal.AUTO||!XlsxText.safe(raw))return null
        val s=raw.trim();val separator=if(rule==ReceiptDecimal.DOT)'.'else ','
        if(!Regex("[+-]?[0-9]+(?:\\${separator}[0-9]{1,6})?").matches(s))return null
        val integer=s.removePrefix("-").removePrefix("+").substringBefore(separator)
        if(integer.length>1&&integer.startsWith('0'))return null
        return runCatching{val n=BigDecimal(s.replace(separator,'.'));require(n.precision()<=12&&n.scale()<=6&&n.abs()<=BigDecimal("999999999999"));n.toPlainString()}.getOrNull()
    }
    fun date(raw:String,order:ReceiptDateOrder):String?=ReceiptValues.date(raw,order)?.takeIf{it>="1900-03-01"&&it<="9999-12-31"}
    fun value(raw:String,type:XlsxTyping):String?=when(type.type) {
        XlsxType.TEXT->raw.takeIf{XlsxText.safe(it)}
        XlsxType.BLANK->"".takeIf{raw.isEmpty()}
        XlsxType.NUMBER->number(raw,type.decimals)
        XlsxType.MONEY->ReceiptValues.amount(raw,type.decimals)?.takeIf{type.currency in ReceiptValues.currencies}
        XlsxType.DATE->date(raw,type.dates)
    }
    fun valid(raw:String,type:XlsxTyping)=value(raw,type)!=null
    fun serial(date:String)=LocalDate.parse(date).toEpochDay()+25569L
    fun totals(book:XlsxBook):Map<String,BigDecimal?> = book.expenses.groupBy{it.currency}.toSortedMap().mapValues{(_,rows)->
        if(rows.any{!it.confirmed||it.amount.isBlank()||it.currency.isBlank()})null else rows.fold(BigDecimal("0.00")){a,e->a+BigDecimal(e.amount)}
    }
}
/** Adapters reuse the existing reviewed M21/M22 models without another detector. */
internal object XlsxCapture {
    fun table(state:TableState,number:Int,kind:String):XlsxTable {
        require(state.phase==TablePhase.REVIEW&&state.confirmed)
        val grid=requireNotNull(state.review).grid.owned();val extraction=state.extraction
        val source=XlsxSource(number,kind,state.owner+":"+state.epoch,extraction?.source.orEmpty(),
            grid.rows.flatten().associate{cell->cell.id to (cell.evidence.map{idx->extraction?.units?.firstOrNull{it.id==idx}?.let{u->"OCR unit ${u.id}, row ${u.line+1}, chars ${u.start}..${u.end}, bounds ${u.bounds?.left},${u.bounds?.top},${u.bounds?.right},${u.bounds?.bottom}: ${u.text}"}?:"OCR unit $idx"}+cell.readings.map{r->"Crop height ${r.targetTextHeight}; ${r.bounds.left},${r.bounds.top},${r.bounds.right},${r.bounds.bottom}: ${r.text}"})},
            grid.rows.flatten().associate{cell->cell.id to (extraction?.grid?.rows?.flatten()?.firstOrNull{it.id==cell.id}?.value?:cell.value)},
            grid.rows.flatten().associate{cell->
                val original=extraction?.grid?.rows?.flatMapIndexed{r,row->row.mapIndexed{c,v->Triple(v.id,r,c)}}?.firstOrNull{it.first==cell.id}
                cell.id to if(original==null)"S$number P1 manual addition"else "S$number P1 R${original.second+1} C${original.third+1}"
            },
            concerns=extraction?.concerns?.map{it.message}.orEmpty())
        return XlsxTable("Table $number",source,grid)
    }
    fun receipt(state:ReceiptState,number:Int,kind:String):XlsxExpense {
        require(state.phase==ReceiptPhase.REVIEW)
        val source=XlsxSource(number,kind,state.owner+":"+state.epoch,state.extraction?.source.orEmpty(),
            ReceiptField.entries.associate{f->f.name to state.extraction?.fields?.get(f)?.candidates.orEmpty().flatMap{c->c.rows.map{r->"Rows ${r.indices.joinToString(",")}: ${r.text}"}}},
            ReceiptField.entries.associate{f->f.name to (state.extraction?.fields?.get(f)?.candidates?.joinToString("\n"){it.raw}?:state.draft.values[f].orEmpty())},ReceiptField.entries.associate{it.name to "S$number P1 ${it.label}"},
            ReceiptField.entries.associate{f->f.name to state.extraction?.fields?.get(f)?.let{"${it.status}; ${it.value.orEmpty()}"}.orEmpty()})
        return XlsxExpense(source,requireNotNull(state.reviewed).toMap(),state.draft.corrected.toSet())
    }
}
