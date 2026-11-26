package org.synapseworks.pageharbor.photo

import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Alignment
import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import android.graphics.Bitmap
import org.synapseworks.pageharbor.ui.ReviewConfirmation
import org.synapseworks.pageharbor.ui.ToolHelpHeading
import org.synapseworks.pageharbor.R
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.ui.home.*
import kotlin.math.*

@Composable fun PhotoScreen(vm:PhotoViewModel,save:()->Unit,share:()->Unit){
 val token=vm.interactionToken;fun act(f:()->Unit){vm.interact(token,f)};BackHandler{act(vm::close)}
 Column(Modifier.fillMaxSize().safeDrawingPadding()){
 Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  ToolHelpHeading(R.string.document_photo_restoration_action, R.string.specialist_photo_help)
  Text(vm.message)
  if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  vm.capture?.let{c->
   Text("Original ${c.sourceWidth} × ${c.sourceHeight}  ·  PNG ${c.width} × ${c.height}")
   RmeActionRow {RmeTextButton({act{vm.before=true}},enabled=!vm.busy){Text("Before")};RmeTextButton({act{vm.before=false}},enabled=vm.ready&&!vm.busy){Text("After")}}
   val pixels=if(vm.before)c.pixels else vm.result?:c.pixels
   val preview by rememberManagedDocumentPreview(requestKey=pixels,isCurrent={vm.interactionToken==token},decode={Bitmap.createBitmap(pixels,c.width,c.height,Bitmap.Config.ARGB_8888)})
   var pending by remember{mutableStateOf<List<PhotoDab>>(emptyList())}
   var strokeOverflow by remember{mutableStateOf(false)}
   val erase=vm.erase;val radius=vm.brushRadius
   val gesture=if(vm.painting&&!vm.busy&&!vm.picker)Modifier.pointerInput(token,erase,radius,c){
    fun point(p:Offset)=PhotoDab((p.x/size.width).coerceIn(0f,1f),(p.y/size.height).coerceIn(0f,1f),erase,radius)
    detectDragGestures(onDragStart={strokeOverflow=false;pending=listOf(point(it))},onDragCancel={pending=emptyList()},onDragEnd={val points=pending;pending=emptyList();act{if(strokeOverflow)vm.strokeRefused()else vm.add(points)}},onDrag={change,_->
     change.consume();val next=point(change.position);val last=pending.lastOrNull()?:next;val count=ceil(max(abs(next.x-last.x)*c.width,abs(next.y-last.y)*c.height)).toInt().coerceAtLeast(1)
     if(!strokeOverflow&&pending.size+count<=512)pending=pending+(1..count).map{i->PhotoDab(last.x+(next.x-last.x)*i/count,last.y+(next.y-last.y)*i/count,erase,radius)}else strokeOverflow=true
    })}else Modifier
   val effective=remember(vm.dabs,pending,c){val cells=BooleanArray(c.width*c.height);(vm.dabs+pending).forEach{d->val x=(d.x*(c.width-1)).roundToInt();val y=(d.y*(c.height-1)).roundToInt();for(yy in max(0,y-d.radius)..min(c.height-1,y+d.radius))for(xx in max(0,x-d.radius)..min(c.width-1,x+d.radius))cells[yy*c.width+xx]=!d.erase};cells}
   Canvas(Modifier.fillMaxWidth().aspectRatio(c.width.toFloat()/c.height).semantics{contentDescription="Photo restoration preview"}.then(gesture)){
    (preview as? ManagedDocumentPreviewState.Ready)?.let{drawImage(it.owner.bitmap.asImageBitmap(),dstSize=IntSize(size.width.toInt(),size.height.toInt()))}
    if(vm.painting){effective.forEachIndexed{i,selected->if(selected)drawRect(Color.Red.copy(alpha=.65f),Offset((i%c.width)*size.width/c.width,(i/c.width)*size.height/c.height),androidx.compose.ui.geometry.Size(size.width/c.width,size.height/c.height))}}
   }
   Text(if(vm.before)"Before: captured current appearance"else"After: independent restored copy")
   Text("Corrections apply to the entire captured image. Scratch-only leaves unmasked pixels exact before encoding.")
   PhotoControl("Noise",vm.recipe.noise,0,2,!vm.busy&&!vm.picker){act{vm.settings(vm.recipe.copy(noise=it))}}
   PhotoControl("Contrast",vm.recipe.contrast,0,30,!vm.busy&&!vm.picker,5){act{vm.settings(vm.recipe.copy(contrast=it))}}
   PhotoControl("Red",vm.recipe.red,-24,24,!vm.busy&&!vm.picker,2){act{vm.settings(vm.recipe.copy(red=it))}}
   PhotoControl("Green",vm.recipe.green,-24,24,!vm.busy&&!vm.picker,2){act{vm.settings(vm.recipe.copy(green=it))}}
   PhotoControl("Blue",vm.recipe.blue,-24,24,!vm.busy&&!vm.picker,2){act{vm.settings(vm.recipe.copy(blue=it))}}
   if(!vm.painting)RmeTextButton({act{vm.painting=true;vm.before=true}},enabled=!vm.busy&&!vm.picker){Text("Paint thin scratch mask")}
   RmePrimaryButton({act(vm::preview)},enabled=!vm.busy&&!vm.picker){Text("Preview restoration")}
   RmeTextButton({act(vm::reset)},enabled=!vm.busy&&!vm.picker){Text("Reset corrections and mask")}
   ReviewConfirmation(
       label = R.string.photo_review_confirmation,
       checked = vm.reviewed,
       enabled = vm.ready && !vm.busy && !vm.picker,
       onCheckedChange = { checked -> act { if (!vm.busy && !vm.picker) vm.reviewed = checked } },
   )
   RmePrimaryButton({act(save)},enabled=vm.ready&&vm.reviewed&&!vm.busy&&!vm.picker){Text("Save restored PNG")}
   RmeSecondaryButton({act(share)},enabled=vm.ready&&vm.reviewed&&!vm.busy&&!vm.picker){Text("Share restored PNG")}
  }
  RmeTextButton({act(vm::close)}){Text("Cancel restoration")}
 }
 if(vm.painting){Column(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
  Text("Paint inside image; scroll using its side margins. Missing detail is unknown. Thin 1–3 px runs, ≤1% area; smooth two-sided evidence required.",style=MaterialTheme.typography.bodySmall)
  RmeActionRow {RmeTextButton({act{vm.erase=!vm.erase}},enabled=!vm.busy&&!vm.picker){Text(if(vm.erase)"Erase mask"else"Draw mask")};RmeTextButton({act{vm.brushRadius=1-vm.brushRadius}},enabled=!vm.busy&&!vm.picker){Text("Brush ${if(vm.brushRadius==0)1 else 3}px")}}
  RmeActionRow {RmeTextButton({act{vm.orientation(ScratchDirection.VERTICAL)}},enabled=!vm.busy&&!vm.picker){Text("Vertical scratch")};RmeTextButton({act{vm.orientation(ScratchDirection.HORIZONTAL)}},enabled=!vm.busy&&!vm.picker){Text("Horizontal scratch")}}
  Text("Boundary: ${vm.direction.name.lowercase()}",style=MaterialTheme.typography.bodySmall)
  RmeActionRow {RmeTextButton({act(vm::undo)},enabled=!vm.busy&&!vm.picker){Text("Undo mask stroke")};RmeTextButton({act(vm::clearMask)},enabled=!vm.busy&&!vm.picker){Text("Clear mask")}}
  RmeTextButton({act{vm.painting=false}},Modifier.fillMaxWidth()){Text("Finish mask painting")}
 }}
 }
}
@Composable
private fun PhotoControl(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    step: Int = 1,
    change: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $value", Modifier.weight(1f))
        RmeIconButton(onClick = { change((value - step).coerceAtLeast(min)) }, enabled = enabled && value > min) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.control_decrease, label))
        }
        RmeIconButton(onClick = { change((value + step).coerceAtMost(max)) }, enabled = enabled && value < max) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.control_increase, label))
        }
    }
}
