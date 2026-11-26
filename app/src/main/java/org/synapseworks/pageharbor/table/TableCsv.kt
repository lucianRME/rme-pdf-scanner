package org.synapseworks.pageharbor.table

import java.io.OutputStream
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal object TableCsv {
    private val numeric=Regex("[+-]?(?:[0-9]+(?:[.,][0-9]+)?|[.,][0-9]+)(?:[eE][+-]?[0-9]+)?")
    /** Printable literal prefix survives CSV framing; no application-specific quote stripping. */
    fun outgoing(value:String):String {
        if(numeric.matches(value))return value
        val probe=Normalizer.normalize(value,Normalizer.Form.NFKC)
        var index=0;var control=false
        fun leading(c:Char)=c.isWhitespace()||Character.isSpaceChar(c)||c=='\''||c=='"'||Character.getType(c) in listOf(Character.NON_SPACING_MARK.toInt(),Character.COMBINING_SPACING_MARK.toInt(),Character.ENCLOSING_MARK.toInt())
        while(index<probe.length&&leading(probe[index])){if(probe[index] in "\t\r\n")control=true;index++}
        val formula=index<probe.length&&probe[index] in "=+-@"
        return if(control||formula)"text: "+value else value
    }
    fun encode(grid:TableGrid):ByteArray? {
        if(!grid.valid())return null
        val text=buildString { for(row in grid.rows){row.forEachIndexed{index,cell->if(index>0)append(',');append('"');append(outgoing(cell.value).replace("\"","\"\""));append('"')};append("\r\n")} }
        return text.toByteArray(Charsets.UTF_8).takeIf{it.size<=TableLimits.CSV}
    }
}
internal class TableReview private constructor(val id:String,val grid:TableGrid,private val bytes:ByteArray) {
    fun export(token:TableSaveToken)=TableExport(token,bytes.copyOf())
    override fun toString()="TableReview(<redacted>)"
    companion object {fun create(grid:TableGrid):TableReview? {val owned=grid.owned();val data=TableCsv.encode(owned)?:return null;return TableReview(java.util.UUID.randomUUID().toString(),owned,data)}}
}
internal class TableSaveToken(val owner:String,val epoch:Long,val review:String,val id:String=java.util.UUID.randomUUID().toString())
internal class TableExport(val token:TableSaveToken,private val bytes:ByteArray) {
    fun write(open:()->OutputStream?,check:()->Unit)=TableCsvWriter.write(bytes,open,check)
    override fun toString()="TableExport(<redacted>)"
}
internal object TableCsvWriter {
    val work=Mutex()
    fun write(bytes:ByteArray,open:()->OutputStream?,check:()->Unit={}):Boolean {
        if(bytes.isEmpty()||bytes.size>TableLimits.CSV)return false
        return try {
            check();val output=open()?:return false
            output.use { out->var offset=0;while(offset<bytes.size){check();val count=minOf(4096,bytes.size-offset);out.write(bytes,offset,count);offset+=count};check();out.flush() }
            check();true
        }catch(e:CancellationException){throw e}catch(_:Exception){false}
    }
}
