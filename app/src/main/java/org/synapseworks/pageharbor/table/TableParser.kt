package org.synapseworks.pageharbor.table

import kotlin.math.abs
import org.synapseworks.pageharbor.ocr.*

/** Bounded geometric proposal. Evidence text is never silently repaired, normalized or dropped. */
internal object TableParser {
    private fun median(values:List<Float>)=values.sorted()[values.size/2]
    private fun good(b:OcrTextBounds?,w:Int,h:Int)=b!=null&&b.left>=0&&b.top>=0&&b.right<=w&&b.bottom<=h&&b.right>b.left&&b.bottom>b.top
    fun parse(text:String,layout:OcrPageLayout?,check:()->Unit={}):TableExtraction? {
        if(text.length>32768||text.lineSequence().count()>256)return null
        val lines=layout?.lines?.takeIf{it.isNotEmpty()}?:text.lines().map(::OcrTextLine)
        if(lines.size>256||lines.sumOf{it.text.length}>32768||lines.any{it.text.length>1024}||lines.sumOf{it.elements.size}>2048)return null
        val concerns=linkedSetOf<TableConcern>();val units=mutableListOf<TableUnit>()
        fun add(index:Int,start:Int,end:Int,value:String,bounds:OcrTextBounds?){units+=TableUnit(units.size,index,start,end,value,bounds)}
        lines.forEachIndexed { index,line->
            check();if(line.text.isBlank())return@forEachIndexed
            val elements=line.elements
            if(layout==null||layout.rotationDegrees!=0||layout.placementMode!=OcrTextPlacementMode.POSITIONED_LINES||!good(line.bounds,layout.imageWidthPx,layout.imageHeightPx)) {
                add(index,0,line.text.length,line.text,null);return@forEachIndexed
            }
            if(elements.size<2){add(index,0,line.text.length,line.text,line.bounds);return@forEachIndexed}
            val ordered=elements.sortedBy{it.bounds?.left?:Float.MAX_VALUE}
            var cursor=0;val spans=mutableListOf<Pair<Int,Int>>();var mapped=true
            for(e in ordered){val start=line.text.indexOf(e.text,cursor)
                if(e.text.isEmpty()||start<0||!good(e.bounds,layout.imageWidthPx,layout.imageHeightPx)||
                    line.text.substring(cursor,start).any{!it.isWhitespace()}){mapped=false;break}
                spans+=start to start+e.text.length;cursor=start+e.text.length
            }
            if(cursor<line.text.length&&line.text.substring(cursor).any{!it.isWhitespace()})mapped=false
            if(!mapped){concerns+=TableConcern.ELEMENTS;add(index,0,line.text.length,line.text,line.bounds);return@forEachIndexed}
            val height=median(ordered.map{it.bounds!!.bottom-it.bounds.top});val gap=maxOf(height*1.5f,layout.imageWidthPx*.025f)
            val groups=mutableListOf<MutableList<Int>>()
            ordered.indices.forEach { i->if(i==0||ordered[i].bounds!!.left-ordered[i-1].bounds!!.right>gap)groups+=mutableListOf(i)else groups.last()+=i }
            for(group in groups){val first=group.first();val last=group.last();val a=ordered[first].bounds!!;val b=ordered[last].bounds!!
                val start=if(first==0)0 else spans[first].first;val end=if(last==ordered.lastIndex)line.text.length else spans[last].second
                add(index,start,end,line.text.substring(start,end),OcrTextBounds(a.left,group.minOf{ordered[it].bounds!!.top},b.right,group.maxOf{ordered[it].bounds!!.bottom}))
            }
        }
        if(units.size>2048)return null
        if(units.isEmpty())return TableExtraction(text,emptyList(),TableGrid.blank(),setOf(TableConcern.NO_GEOMETRY))
        if(units.any{it.bounds==null}){
            if(lines.count{it.text.isNotBlank()}>TableLimits.ROWS)return null
            val rows=units.groupBy{it.line}.values.map{u->listOf(TableCell(u.joinToString(" "){it.text},u.map{it.id},uncertain=true))}
            val grid=TableGrid(rows);return if(grid.valid())TableExtraction(text,units,grid,concerns+TableConcern.NO_GEOMETRY)else null
        }
        val height=median(units.map{it.bounds!!.bottom-it.bounds.top});val bands=mutableListOf<MutableList<TableUnit>>()
        for(u in units.sortedWith(compareBy<TableUnit>{it.bounds!!.top}.thenBy{it.bounds!!.left})){
            check();val b=u.bounds!!;val band=bands.lastOrNull();val first=band?.firstOrNull()?.bounds
            if(first!=null&&abs((first.top+first.bottom-b.top-b.bottom)/2)<=minOf(first.bottom-first.top,b.bottom-b.top)*.5f)band!!.add(u) else bands+=mutableListOf(u)
        }
        bands.forEach{it.sortBy{u->u.bounds!!.left}}
        val columns=bands.maxOf{it.size};if(columns>TableLimits.COLS)return null
        val anchors=bands.filter{it.size==columns};if(anchors.size<2)concerns+=TableConcern.ONE_ANCHOR
        val left=FloatArray(columns){c->anchors.minOf{it[c].bounds!!.left}};val right=FloatArray(columns){c->anchors.maxOf{it[c].bounds!!.right}}
        for(c in 0 until columns){val ls=anchors.map{it[c].bounds!!.left};val rs=anchors.map{it[c].bounds!!.right}
            if(ls.max()-ls.min()>height*.65f&&rs.max()-rs.min()>height*.65f)concerns+=TableConcern.ALIGNMENT
        }
        val separators=(0 until columns-1).map{c->if(right[c]>=left[c+1])concerns+=TableConcern.ALIGNMENT;(right[c]+left[c+1])/2}
        if(separators.zipWithNext().any{(a,b)->a>=b})return null
        val result=mutableListOf<List<TableCell>>();var previousBottom:Float?=null
        for(band in bands){
            check();val assigned=List(columns){mutableListOf<TableUnit>()};val uncertain=BooleanArray(columns)
            for(u in band){val b=u.bounds!!;val start=separators.count{it<=b.left};val end=separators.count{it<b.right}
                assigned[start]+=u
                if(start!=end){concerns+=TableConcern.SPAN;for(c in start..end)uncertain[c]=true}
            }
            val populated=assigned.indices.filter{assigned[it].isNotEmpty()};val top=band.minOf{it.bounds!!.top};val bottom=band.maxOf{it.bounds!!.bottom}
            val wrap=populated.size==1&&columns>1&&previousBottom!=null&&top-previousBottom>=0&&top-previousBottom<=height*.35f&&result.lastOrNull()?.get(populated.single())?.value?.isNotEmpty()==true
            if(wrap){val c=populated.single();val old=result.last();val more=assigned[c];result[result.lastIndex]=old.mapIndexed{i,cell->if(i==c)cell.copy(value=cell.value+"\n"+more.joinToString(" "){it.text},evidence=cell.evidence+more.map{it.id},uncertain=true)else cell};concerns+=TableConcern.WRAP}
            else {
                if(populated.size<columns)concerns+=TableConcern.SPARSE
                val row=assigned.mapIndexed{c,values->if(values.size>1)concerns+=TableConcern.COLLISION
                    TableCell(values.joinToString("\n"){it.text},values.map{it.id},uncertain[c]||values.size!=1)}
                result+=row
            };previousBottom=bottom
            if(result.size>TableLimits.ROWS)return null
        }
        val grid=TableGrid(result);return if(grid.valid())TableExtraction(text,units,grid,concerns)else null
    }
}
