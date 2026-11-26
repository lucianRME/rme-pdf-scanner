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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.booklayout.BookPreviewImage
import org.synapseworks.pageharbor.document.curvature.*
import org.synapseworks.pageharbor.document.crop.PageCrop
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

@Composable
internal fun CurvatureScreen(model:CurvatureViewModel,onApply:()->Unit) {
    val s=model.state?:return;val review=s.review
    var corners by remember(s.owner) { mutableStateOf<PageCrop?>(null) }
    BackHandler {
        if (corners == null) model.close() else corners = null
    }
    Surface(Modifier.fillMaxSize()){
        Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(stringResource(R.string.curve_title),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.curve_help))
            if(s.work!=null)Text(stringResource(if(s.work==CurvatureWork.PREPARING)R.string.curve_preparing else R.string.curve_working,s.done,s.total),Modifier.testTag("curve-progress").semantics{liveRegion=LiveRegionMode.Polite})
            if(s.failed)Text(stringResource(R.string.curve_failed),Modifier.testTag("curve-error"),color=MaterialTheme.colorScheme.error)
            if (review != null && corners != null && s.preview != null) {
                Text(stringResource(R.string.import_crop_drag))
                CropImage(
                    image = s.preview.before,
                    pageId = review.page.id.value,
                    filter = review.page.filter,
                    adjustments = review.page.adjustments,
                    corners = corners,
                    onMove = { index, dx, dy -> corners = corners?.moveCorner(index, dx, dy) },
                    cornerTag = "book-crop",
                )
                RmePrimaryButton(
                    onClick = { model.updateCorners(requireNotNull(corners)); corners = null },
                    modifier = Modifier.fillMaxWidth().testTag("book-crop-preview-adjusted"),
                ) { Text(stringResource(R.string.import_crop_preview)) }
                review.initial.perspective?.let { detected ->
                    RmeTextButton(onClick = { corners = detected }) { Text(stringResource(R.string.import_crop_auto)) }
                }
                RmeTextButton(onClick = { corners = null }) { Text(stringResource(R.string.manual_cancel)) }
            } else if(review!=null){
                val status = when {
                    !review.initial.canProcess -> R.string.curve_unsupported
                    review.straighten && !review.enabled -> R.string.curve_edges_only
                    !review.enabled && review.initial.estimate.status == CurvatureStatus.FLAT -> R.string.curve_flat
                    !review.enabled -> R.string.curve_uncertain
                    review.manual -> R.string.curve_manual
                    else -> R.string.curve_automatic
                }
                Text(stringResource(status),Modifier.testTag("curve-status").semantics{liveRegion=LiveRegionMode.Polite})
                if (review.initial.canProcess) {
                    RmeSecondaryButton(
                        onClick = { corners = review.perspective ?: PageCrop.FULL },
                        enabled = s.preview != null && s.work == null && !s.failed,
                        modifier = Modifier.fillMaxWidth().testTag("book-crop-adjust"),
                    ) { Text(stringResource(R.string.import_crop_adjust)) }
                }
                s.preview?.let{p->
                    Text(stringResource(R.string.curve_before));CurvaturePreviewCard(p.before,review,"curve-before")
                    Text(stringResource(R.string.curve_after));CurvaturePreviewCard(p.after,review,"curve-after")
                }
                if(review.initial.canProcess){
                    if (review.perspective != null) {
                        val perspectiveLabel = stringResource(R.string.curve_perspective)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = review.straighten,
                                onCheckedChange = model::setStraighten,
                                enabled = !s.blocked,
                                modifier = Modifier.testTag("curve-perspective").semantics {
                                    contentDescription = perspectiveLabel
                                },
                            )
                            Text(stringResource(R.string.curve_perspective))
                        }
                    }
                    RmeSecondaryButton(onClick={model.setEnabled(!review.enabled)},enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("curve-toggle")){
                        Text(stringResource(if(review.enabled)R.string.curve_turn_off else R.string.curve_manual_start))}
                    if(review.enabled){
                        Text(stringResource(R.string.curve_controls_help))
                        for(i in 0..4){
                            val value=review.field?.offset(i/4f)?:0f;val label=stringResource(R.string.curve_anchor_description,i+1)
                            Text(stringResource(R.string.curve_anchor,i+1,(value*1000).toInt()/10f))
                            Slider(value=value.coerceIn(-.14f,.14f),onValueChange={model.setAnchor(i,it)},valueRange=-.14f.. .14f,enabled=!s.blocked,
                                modifier=Modifier.fillMaxWidth().testTag("curve-anchor-$i").semantics{contentDescription=label})
                        }
                        val taper=(review.field?.let{it.scale(1f)-it.scale(0f)}?:0f).coerceIn(-.16f,.16f);val label=stringResource(R.string.curve_taper_description)
                        Text(stringResource(R.string.curve_taper,(taper*100).toInt()))
                        Slider(value=taper,onValueChange=model::setTaper,valueRange=-.16f.. .16f,enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("curve-taper").semantics{contentDescription=label})
                        val stretch = review.field?.horizontalStretch ?: 0f
                        val stretchLabel = stringResource(R.string.curve_horizontal_description)
                        Text(stringResource(R.string.curve_horizontal, (stretch * 100).toInt()))
                        Slider(
                            value = stretch,
                            onValueChange = model::setHorizontalStretch,
                            valueRange = -.40f.. .40f,
                            enabled = !s.blocked,
                            modifier = Modifier.fillMaxWidth().testTag("curve-horizontal")
                                .semantics { contentDescription = stretchLabel },
                        )
                    }
                    RmeSecondaryButton(onClick=model::reset,enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("curve-reset")){Text(stringResource(R.string.curve_reset))}
                }
            }
            if (corners == null) {
                RmePrimaryButton(onClick=onApply,enabled=s.work==null&&!s.failed&&review!=null,modifier=Modifier.fillMaxWidth().testTag("curve-apply")){Text(stringResource(R.string.curve_apply))}
                RmeTextButton(onClick=model::close,modifier=Modifier.fillMaxWidth().testTag("curve-cancel")){Text(stringResource(R.string.manual_cancel))}
            }
        }
    }
}
@Composable
private fun CurvaturePreviewCard(image:BookPreviewImage,review:CurvatureReview,tag:String) {
    val uri=Uri.fromFile(image.file)
    FilteredDocumentPreview(FilteredPreviewRequest(review.page.id.value,uri.toString(),review.page.filter,DocumentPageRotation.DEGREES_0,image.metadata,review.page.adjustments),
        uri,1,1,modifier=Modifier.testTag(tag),minHeight=90.dp,maxHeight=220.dp)
}
