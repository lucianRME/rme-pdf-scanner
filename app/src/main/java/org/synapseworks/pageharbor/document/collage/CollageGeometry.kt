package org.synapseworks.pageharbor.document.collage

import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize
import org.synapseworks.pageharbor.document.session.DocumentPaperSize

internal const val MAX_COLLAGE_TILES = 24
internal const val COLLAGE_DPI = 180
internal enum class CollageFit { FIT, CROP }
internal data class CollageSettings(val slots: Int = 4,val paper: CardCopyPageSize = CardCopyPageSize.A4,
    val landscape: Boolean = false,val marginMm: Float = 10f,val gapMm: Float = 5f) {
    init { require(slots in setOf(2,4,6));require(marginMm.isFinite()&&marginMm in 0f..25f);require(gapMm.isFinite()&&gapMm in 0f..15f) }
    val size get()=if(landscape)DocumentPaperSize(paper.heightPoints,paper.widthPoints)else DocumentPaperSize(paper.widthPoints,paper.heightPoints)
}
/** Distinct tile IDs retain deliberate repeated references to one source page. */
internal data class CollageTile(val id: String,val pageId: Long,val fit: CollageFit = CollageFit.FIT,
    val turns: Int = 0,val x: Float = .5f,val y: Float = .5f) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")));require(turns in 0..3);require(x.isFinite()&&y.isFinite()&&x in 0f..1f&&y in 0f..1f) }
}
internal data class CollageSize(val width: Int,val height: Int) { init { require(width in 1..6000&&height in 1..6000&&width.toLong()*height<=12_000_000) } }
internal data class CollageRect(val left: Float,val top: Float,val right: Float,val bottom: Float) {
    init { require(listOf(left,top,right,bottom).all{it.isFinite()});require(right>left&&bottom>top) }
    val width get()=right-left
    val height get()=bottom-top
}
internal data class CollagePlacement(val tile: CollageTile,val cell: CollageRect,val image: CollageRect)
internal data class CollageSheet(val size: DocumentPaperSize,val placements: List<CollagePlacement>) {
    fun pixels(dpi: Int=COLLAGE_DPI): Pair<Int,Int> { require(dpi in 1..COLLAGE_DPI);return (size.widthPoints*dpi/72f).roundToInt().coerceAtLeast(1) to (size.heightPoints*dpi/72f).roundToInt().coerceAtLeast(1) }
}
internal class CollageDraft(tiles: List<CollageTile>,val settings: CollageSettings = CollageSettings()) {
    init { require(tiles.size<=MAX_COLLAGE_TILES&&tiles.map{it.id}.distinct().size==tiles.size) }
    val tiles: List<CollageTile> = Collections.unmodifiableList(tiles.toList())
    fun withTiles(value: List<CollageTile>)=CollageDraft(value,settings)
    fun withSettings(value: CollageSettings)=CollageDraft(tiles,value)
}
/** All geometry is in physical sheet points, upper-left origin; tile scaling is uniform. */
internal object CollageGeometry {
    fun plan(draft: CollageDraft,sources: Map<Long,CollageSize>): List<CollageSheet> {
        val s=draft.settings;val paper=s.size;val margin=s.marginMm*72f/25.4f;val gap=s.gapMm*72f/25.4f
        var columns=if(s.slots==2)1 else 2;var rows=s.slots/columns
        if(s.landscape){val swap=columns;columns=rows;rows=swap}
        val width=(paper.widthPoints-2*margin-(columns-1)*gap)/columns
        val height=(paper.heightPoints-2*margin-(rows-1)*gap)/rows
        require(width>0f&&height>0f)
        return draft.tiles.chunked(s.slots).map{group->
            CollageSheet(paper,Collections.unmodifiableList(group.mapIndexed{index,tile->
                val source=requireNotNull(sources[tile.pageId]);val w=if(tile.turns%2==0)source.width else source.height;val h=if(tile.turns%2==0)source.height else source.width
                val left=margin+(index%columns)*(width+gap);val top=margin+(index/columns)*(height+gap)
                val cell=CollageRect(left,top,(left+width).coerceAtMost(paper.widthPoints-margin),(top+height).coerceAtMost(paper.heightPoints-margin))
                val scale=if(tile.fit==CollageFit.FIT)min(cell.width/w,cell.height/h)else max(cell.width/w,cell.height/h)
                val dw=w*scale;val dh=h*scale;val x=cell.left+(cell.width-dw)*tile.x;val y=cell.top+(cell.height-dh)*tile.y
                CollagePlacement(tile,cell,CollageRect(x,y,x+dw,y+dh))
            }))
        }
    }
}
