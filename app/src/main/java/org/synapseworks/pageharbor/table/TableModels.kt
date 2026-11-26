package org.synapseworks.pageharbor.table

import java.util.UUID
import org.synapseworks.pageharbor.ocr.OcrTextBounds

internal object TableLimits {
    const val ROWS=40;const val COLS=12;const val CELL=1024;const val TEXT=131072;const val CSV=1024*1024
    fun safe(value:String):Boolean {
        if(value.length>CELL)return false
        var i=0
        while(i<value.length){val c=value[i++];val cp=if(Character.isHighSurrogate(c)){
            if(i>=value.length||!Character.isLowSurrogate(value[i]))return false
            Character.toCodePoint(c,value[i++])
        }else{if(Character.isLowSurrogate(c))return false;c.code}
            if((Character.isISOControl(cp)&&cp!=9&&cp!=10&&cp!=13)||Character.getType(cp)==Character.FORMAT.toInt())return false
        };return true
    }
}
internal data class TableCropReading(val text:String,val targetTextHeight:Int,val bounds:OcrTextBounds) {
    override fun toString()="TableCropReading(<redacted>)"
}
internal data class TableCell(val value:String="",val evidence:List<Int> = emptyList(),val uncertain:Boolean=false,
    val corrected:Boolean=false,val id:String=UUID.randomUUID().toString(),val readings:List<TableCropReading> = emptyList()) {
    override fun toString()="TableCell(<redacted>)"
}
internal data class TableGrid(val rows:List<List<TableCell>>) {
    val columns get()=rows.firstOrNull()?.size?:0
    fun values()=rows.map { r->r.map { it.value } }
    fun valid()=rows.size in 1..TableLimits.ROWS&&columns in 1..TableLimits.COLS&&rows.all{it.size==columns}&&
        rows.flatten().sumOf{it.value.length}<=TableLimits.TEXT&&rows.flatten().all{TableLimits.safe(it.value)}
    fun owned()=TableGrid(rows.map{r->r.map{it.copy(evidence=it.evidence.toList(),readings=it.readings.toList())}})
    fun edit(id:String,value:String)=if(value.length>TableLimits.CELL)this else copy(rows=rows.map{r->r.map{if(it.id==id)it.copy(value=value,corrected=true)else it}})
    fun insertRow(index:Int):TableGrid=if(rows.size>=TableLimits.ROWS||index !in 0..rows.size)this else copy(rows=rows.toMutableList().apply{add(index,List(columns){TableCell()})})
    fun deleteRow(index:Int):TableGrid=if(rows.size<=1||index !in rows.indices)this else copy(rows=rows.filterIndexed{i,_->i!=index})
    fun moveRow(from:Int,to:Int):TableGrid=if(from !in rows.indices||to !in rows.indices)this else copy(rows=rows.toMutableList().apply{add(to,removeAt(from))})
    fun insertColumn(index:Int):TableGrid=if(columns>=TableLimits.COLS||index !in 0..columns)this else copy(rows=rows.map{r->r.toMutableList().apply{add(index,TableCell())}})
    fun deleteColumn(index:Int):TableGrid=if(columns<=1||index !in 0 until columns)this else copy(rows=rows.map{r->r.filterIndexed{i,_->i!=index}})
    fun moveColumn(from:Int,to:Int):TableGrid=if(from !in 0 until columns||to !in 0 until columns)this else copy(rows=rows.map{r->r.toMutableList().apply{add(to,removeAt(from))}})
    override fun toString()="TableGrid(<redacted>)"
    companion object {
        fun blank(rows:Int=2,columns:Int=2)=TableGrid(List(rows){List(columns){TableCell()}})
        fun from(values:List<List<String>>)=TableGrid(values.map{r->r.map{TableCell(it)}})
    }
}
internal data class TableUnit(val id:Int,val line:Int,val start:Int,val end:Int,val text:String,val bounds:OcrTextBounds?) {
    override fun toString()="TableUnit(<redacted>)"
}
internal enum class TableConcern(val message:String) {
    REREAD("Source-crop OCR differs from the original. Inspect the available readings before confirming."),
    NO_GEOMETRY("No usable positioned table geometry. Check the one-column draft and correct the layout."),
    ELEMENTS("Some OCR elements could not be mapped precisely. Check source evidence."),
    ONE_ANCHOR("Only one complete anchor row. Verify the column proposal."),
    ALIGNMENT("Competing or uneven column alignment. Verify column positions and cell placement."),
    SPARSE("Sparse rows contain blank slots. Check for missed text and column positions."),
    SPAN("Possible merged or crossing cell. Text is kept once; review blank covered cells."),
    WRAP("A close single-column continuation was joined with a newline. Verify wrapping versus separate rows."),
    COLLISION("More than one run occupies a cell. Text is kept with a newline; review the structure.")
}
internal data class TableExtraction(val source:String,val units:List<TableUnit>,val grid:TableGrid,val concerns:Set<TableConcern>) {
    override fun toString()="TableExtraction(<redacted>)"
}
