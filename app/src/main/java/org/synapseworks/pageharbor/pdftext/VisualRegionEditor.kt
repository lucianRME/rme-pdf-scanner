package org.synapseworks.pageharbor.pdftext

import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.document.annotation.AnnotationBounds
import kotlin.math.*

internal fun LazyListScope.visualRegionItems(vm:TextViewModel){
    val page=vm.visualPage;val model=vm.visualDocument?:return
    item("visual-page-info"){
    Text("Page ${page+1} of ${model.pages.size}. Drag a rectangle over plain white paper, or select and adjust the region below.")
    RmeActionRow {RmeTextButton({vm.chooseVisualPage(page-1)},enabled=page>0,modifier=Modifier.testTag("visual-previous")){Text("Previous page")};RmeTextButton({vm.chooseVisualPage(page+1)},enabled=page+1<model.pages.size,modifier=Modifier.testTag("visual-next")){Text("Next page")}}
    }
    val bitmap=vm.preview
    if(bitmap!=null){
        item("visual-source-page"){Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height).testTag("visual-region-page").pointerInput(vm.visualPage,bitmap){
            awaitEachGesture {
                val down=awaitFirstDown();val start=down.position;down.consume()
                while(true){val event=awaitPointerEvent();val change=event.changes.firstOrNull{it.id==down.id}?:break
                    if(!change.pressed)break
                    change.consume();val end=change.position
                    val l=(min(start.x,end.x)/size.width).coerceIn(0f,1f);val r=(max(start.x,end.x)/size.width).coerceIn(0f,1f);val t=(min(start.y,end.y)/size.height).coerceIn(0f,1f);val b=(max(start.y,end.y)/size.height).coerceIn(0f,1f)
                    if(r-l>=.02f&&b-t>=.02f)vm.chooseVisualRegion(AnnotationBounds(l,t,r,b))
                }
            }
        }){
            Image(bitmap.asImageBitmap(),"Source cropped page for visual region selection",Modifier.fillMaxSize())
            val region=vm.visualRegion
            Canvas(Modifier.fillMaxSize()){region?.let{drawRect(Color.Blue,Offset(it.left*size.width,it.top*size.height),androidx.compose.ui.geometry.Size(it.width*size.width,it.height*size.height),style=Stroke(2.dp.toPx()))}}
        }
        }
    }else item("visual-source-loading"){Text(vm.previewFailure?.let(::visualFailure)?:"Preparing source preview…")}
    item("visual-select"){RmeSecondaryButton({vm.chooseVisualRegion(DEFAULT_VISUAL_REGION)},Modifier.testTag("visual-select-region")){Text("Select central region")}}
    vm.visualRegion?.let{r->
        item("visual-region-summary"){Text("Region: left ${(r.left*100).roundToInt()}%, top ${(r.top*100).roundToInt()}%, right ${(r.right*100).roundToInt()}%, bottom ${(r.bottom*100).roundToInt()}%",Modifier.testTag("visual-region-summary"))}
        fun update(left:Float=r.left,top:Float=r.top,right:Float=r.right,bottom:Float=r.bottom){vm.chooseVisualRegion(AnnotationBounds(left,top,right,bottom))}
        item("visual-slider-left"){Column{Text("Left edge");Slider(r.left,{update(left=it)},valueRange=0f..(r.right-.02f),modifier=Modifier.testTag("visual-left"))}}
        item("visual-slider-top"){Column{Text("Top edge");Slider(r.top,{update(top=it)},valueRange=0f..(r.bottom-.02f),modifier=Modifier.testTag("visual-top"))}}
        item("visual-slider-right"){Column{Text("Right edge");Slider(r.right,{update(right=it)},valueRange=(r.left+.02f)..1f,modifier=Modifier.testTag("visual-right"))}}
        item("visual-slider-bottom"){Column{Text("Bottom edge");Slider(r.bottom,{update(bottom=it)},valueRange=(r.top+.02f)..1f,modifier=Modifier.testTag("visual-bottom"))}}
    }
}
