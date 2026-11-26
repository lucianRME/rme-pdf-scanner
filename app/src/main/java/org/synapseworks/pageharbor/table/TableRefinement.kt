package org.synapseworks.pageharbor.table

import kotlin.math.ceil
import kotlin.math.floor
import org.synapseworks.pageharbor.ocr.OcrTextBounds

/** A source-reading proposal, never a spelling rule. Single-token identifiers stay literal. */
internal object TableRefinement {
    const val MAX_CELLS=8
    val heights=listOf(48,96)
    private val numeric=Regex("[+-]?(?:[0-9]+(?:[.,][0-9]+)?|[.,][0-9]+)(?:[eE][+-]?[0-9]+)?")
    private fun words(value:String)=value.split(' ')
    fun eligible(cell:TableCell)=!cell.corrected&&!cell.uncertain&&cell.evidence.size==1&&TableLimits.safe(cell.value)&&
        words(cell.value).let{it.size>=2&&it.all{w->w.isNotEmpty()&&w.none{c->c.isWhitespace()}}}
    fun bounds(unit:TableUnit,width:Int,height:Int):OcrTextBounds? {
        val b=unit.bounds?:return null;val h=b.bottom-b.top
        if(h<4||b.left<0||b.top<0||b.right>width||b.bottom>height)return null
        return OcrTextBounds(floor(b.left-h*.5f).coerceAtLeast(0f),floor(b.top-h*.5f).coerceAtLeast(0f),
            ceil(b.right+h*.5f).coerceAtMost(width.toFloat()),ceil(b.bottom+h*.5f).coerceAtMost(height.toFloat()))
    }
    fun apply(raw:TableExtraction,width:Int,height:Int,check:()->Unit={},read:(TableUnit,OcrTextBounds,Int)->String?):TableExtraction {
        var count=0;var conflict=false
        val grid=TableGrid(raw.grid.rows.map{row->row.map cell@{cell->
            check();if(!eligible(cell)||count>=MAX_CELLS)return@cell cell
            val unit=raw.units.singleOrNull{it.id==cell.evidence.single()}?:return@cell cell
            val box=bounds(unit,width,height)?:return@cell cell
            count++
            val readings=heights.mapNotNull{target->check();val value=read(unit,box,target);check()
                value?.takeIf{it.isNotEmpty()&&TableLimits.safe(it)&&it.none{c->c=='\r'||c=='\n'||c=='\t'}}?.let{TableCropReading(it,target,box)}}
            val candidate=readings.firstOrNull()?.text
            val before=words(cell.value);val after=candidate?.let(::words)
            val agree=readings.size==heights.size&&readings.map{it.text}.distinct().size==1&&after!=null&&after.size==before.size&&
                before.indices.all{i->!numeric.matches(before[i])||before[i]==after[i]}
            val differs=readings.any{it.text!=cell.value};conflict=conflict||differs
            cell.copy(value=if(agree)candidate!! else cell.value,readings=readings,uncertain=cell.uncertain||differs)
        }})
        check()
        return if(grid.valid())raw.copy(grid=grid,concerns=if(conflict)raw.concerns+TableConcern.REREAD else raw.concerns)else raw
    }
}
