package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.document.collage.*
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.DocumentFilter

@Composable internal fun CollageScreen(model:CollageViewModel,onApply:()->Unit) {
    val s=model.state?:return;val d=s.draft;val settings=d.settings
    var source by rememberSaveable(s.owner){mutableIntStateOf(0)}
    var selected by rememberSaveable(s.owner){mutableStateOf(d.tiles.firstOrNull()?.id)}
    var reviewSheet by rememberSaveable(s.owner){mutableIntStateOf(0)}
    val outputCount=(d.tiles.size+settings.slots-1)/settings.slots
    val sheetCount=outputCount.coerceAtLeast(1)
    val sheetIndex=reviewSheet.coerceIn(0,sheetCount-1)
    val tile=d.tiles.firstOrNull{it.id==selected}?:d.tiles.firstOrNull()
    BackHandler(onBack=model::close)
    Surface(Modifier.fillMaxSize().testTag("collage-screen")){
        Column(Modifier.safeDrawingPadding().padding(12.dp)){
            Text("Document collage",style=MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Arrange copies of pages already in this document. Use Scan, Import or Add pages first to bring in more images.")
                Text("Use appends flattened collage pages after all originals. PDFs include every page in the document; remove originals afterwards if you want only the collage. Individual tiles cannot be edited after Use.")
                Text("White background. Fit shows the whole image; Crop fills its cell and clips the edges. Cropping is not secure redaction.")
                Text(if(s.preparing)"Preparing pages…" else if(s.working)"Updating preview…" else "Preview ready.",Modifier.testTag("collage-progress").semantics{liveRegion=LiveRegionMode.Polite})
                if(s.failed){Text("Collage could not be prepared. A source may be unavailable, changed, too large, or storage may be low. Nothing was changed.",Modifier.testTag("collage-error"),color=MaterialTheme.colorScheme.error)
                    RmeSecondaryButton(onClick=model::retry,enabled=!s.preparing){Text("Retry preview")}}
                Text("Sheet ${sheetIndex+1} of $sheetCount")
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    s.previews.getOrNull(sheetIndex)?.let{image->
                        val uri=Uri.fromFile(image.file)
                        FilteredDocumentPreview(FilteredPreviewRequest(sheetIndex.toLong(),uri.toString(),DocumentFilter.ORIGINAL,DocumentPageRotation.DEGREES_0,image.metadata),
                            uri,1,1,modifier=Modifier.testTag("collage-preview-$sheetIndex"),minHeight=120.dp,maxHeight=280.dp)
                    }
                }
                Row(Modifier.fillMaxWidth()){
                    RmeSecondaryButton(onClick={reviewSheet=sheetIndex-1},enabled=sheetIndex>0&&!s.preparing,modifier=Modifier.weight(1f).testTag("collage-previous-sheet")){Text("Previous sheet")}
                    RmeSecondaryButton(onClick={reviewSheet=sheetIndex+1},enabled=sheetIndex<sheetCount-1&&!s.preparing,modifier=Modifier.weight(1f).testTag("collage-next-sheet")){Text("Next sheet")}
                }
                Text("Images per sheet: ${settings.slots}")
                for(count in listOf(2,4,6))RmeSecondaryButton(onClick={model.settings(settings.copy(slots=count))},enabled=!s.preparing,
                    modifier=Modifier.fillMaxWidth().testTag("collage-slots-$count")){Text("$count images${if(count==settings.slots)" · selected" else ""}")}
                for(paper in CardCopyPageSize.entries)RmeSecondaryButton(onClick={model.settings(settings.copy(paper=paper))},enabled=!s.preparing,
                    modifier=Modifier.fillMaxWidth().testTag("collage-paper-${paper.name}")){Text("${paper.name}${if(paper==settings.paper)" · selected" else ""}")}
                RmeSecondaryButton(onClick={model.settings(settings.copy(landscape=!settings.landscape))},enabled=!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-orientation")){
                    Text(if(settings.landscape)"Landscape · switch to portrait" else "Portrait · switch to landscape")}
                CollageSlider("Margins (mm)",settings.marginMm,0f..25f,!s.preparing,"collage-margin"){model.settings(settings.copy(marginMm=it))}
                CollageSlider("Spacing (mm)",settings.gapMm,0f..15f,!s.preparing,"collage-gap"){model.settings(settings.copy(gapMm=it))}
                Text("Add a copy of source page ${source+1} of ${s.sourceIds.size}. Repeated pages are allowed.")
                Row(Modifier.fillMaxWidth()){
                    RmeSecondaryButton(onClick={source--},enabled=source>0&&!s.preparing,modifier=Modifier.weight(1f)){Text("Previous source")}
                    RmeSecondaryButton(onClick={source++},enabled=source<s.sourceIds.lastIndex&&!s.preparing,modifier=Modifier.weight(1f)){Text("Next source")}
                }
                RmePrimaryButton(onClick={model.add(s.sourceIds[source])},enabled=d.tiles.size<MAX_COLLAGE_TILES&&!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-add")){Text("Add source page ${source+1} (${d.tiles.size}/24 tiles)")}
                d.tiles.forEachIndexed{index,item->
                    RmeSecondaryButton(onClick={selected=item.id},enabled=!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-tile-$index")){
                        Text("Tile ${index+1}: source page ${s.sourceIds.indexOf(item.pageId)+1}${if(item.id==tile?.id)" · selected" else ""}")}
                }
                tile?.let{t->
                    val index=d.tiles.indexOf(t);Text("Placement for tile ${index+1}",style=MaterialTheme.typography.titleMedium)
                    RmeSecondaryButton(onClick={model.move(t.id,-1)},enabled=index>0&&!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-earlier")){Text("Move tile earlier")}
                    RmeSecondaryButton(onClick={model.move(t.id,1)},enabled=index<d.tiles.lastIndex&&!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-later")){Text("Move tile later")}
                    RmeSecondaryButton(onClick={model.tile(t.copy(fit=if(t.fit==CollageFit.FIT)CollageFit.CROP else CollageFit.FIT))},enabled=!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-fit")){
                        Text(if(t.fit==CollageFit.FIT)"Fit whole image · switch to Crop" else "Crop to fill · switch to Fit")}
                    RmeSecondaryButton(onClick={model.tile(t.copy(turns=(t.turns+1)%4))},enabled=!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-rotate")){Text("Rotate tile clockwise (${t.turns*90}°)")}
                    CollageSlider("Horizontal placement",t.x,0f..1f,!s.preparing,"collage-x"){model.tile(t.copy(x=it))}
                    CollageSlider("Vertical placement",t.y,0f..1f,!s.preparing,"collage-y"){model.tile(t.copy(y=it))}
                    RmeSecondaryButton(onClick={model.remove(t.id)},enabled=!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-remove")){Text("Remove tile ${index+1}")}
                }
                RmeSecondaryButton(onClick=model::reset,enabled=!s.preparing,modifier=Modifier.fillMaxWidth().testTag("collage-reset")){Text("Reset draft")}
            }
            // Essential actions stay reachable even in short/narrow windows at large font scales.
            RmePrimaryButton(onClick=onApply,enabled=!s.working&&!s.failed&&d.tiles.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("collage-use")){Text("Use collage · append $outputCount ${if(outputCount==1)"page" else "pages"}")}
            RmeTextButton(onClick=model::close,modifier=Modifier.fillMaxWidth().testTag("collage-cancel")){Text("Cancel")}
        }
    }
}
@Composable private fun CollageSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean,tag:String,onChange:(Float)->Unit){
    Text("$label: ${"%.1f".format(java.util.Locale.ROOT,value)}")
    Slider(value=value,onValueChange=onChange,valueRange=range,enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(tag).semantics{contentDescription=label})
}
