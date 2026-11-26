package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.image.ManualImageAdjustments

@Composable
internal fun BookSplitScreen(model:BookSplitViewModel,onApply:()->Unit) {
    val s=model.state?:return
    val review=s.current
    BackHandler(onBack=model::close)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.book_title),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.book_help))
            if(s.work!=null)Text(stringResource(if(s.work==BookWork.ANALYZING)R.string.book_analyzing else if(s.work==BookWork.PREPARING)R.string.book_preparing else R.string.book_previewing,s.done,s.total),
                Modifier.testTag("book-progress").semantics{liveRegion=LiveRegionMode.Polite})
            if(s.failed)Text(stringResource(R.string.book_failed),Modifier.testTag("book-error"),color=MaterialTheme.colorScheme.error)
            if(review!=null){
                Text(stringResource(R.string.book_page,s.index+1,s.reviews.size))
                val status=if(!review.analysis.canSplit)R.string.book_unsupported else if(!review.split)R.string.book_unchanged else if(review.manual)R.string.book_manual else if (s.capturedSpread && review.analysis.detection.status != BookDetectionStatus.CONFIDENT) R.string.camera_book_suggested else R.string.book_automatic
                Text(stringResource(status),Modifier.testTag("book-status").semantics{liveRegion=LiveRegionMode.Polite})
                s.preview?.let{preview->
                    Text(stringResource(R.string.book_guide))
                    BookPreviewCard(preview.spread,review.page.id.value,DocumentFilter.ORIGINAL,ManualImageAdjustments(),1,1,"book-spread-preview")
                    if (preview.halves.size == 2) {
                        val layoutDirection = LocalLayoutDirection.current
                        val physicalOrder = if (s.order == BookReadingOrder.LEFT_TO_RIGHT) listOf(0, 1) else listOf(1, 0)
                        // Physical left/right stay fixed. A wrapping heading must move both images equally.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((side, output) in physicalOrder.withIndex()) {
                                        val label = if (side == 0) R.string.camera_book_left_page else R.string.camera_book_right_page
                                        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                                            Text(stringResource(label, output + 1), modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (output in physicalOrder) {
                                        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                                            BookPreviewCard(preview.halves[output], review.page.id.value, review.page.filter,
                                                review.page.adjustments, output + 1, 2, "book-half-${output + 1}",
                                                modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if(review.analysis.canSplit){
                    if (!s.capturedSpread) RmeSecondaryButton(onClick={model.setSplit(!review.split)},enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("book-toggle-split")){
                        Text(stringResource(if(review.split)R.string.book_keep else R.string.book_split_manual))
                    }
                    val line=review.line
                    if(review.split&&line!=null){
                        val topLabel=stringResource(R.string.book_top_description);val bottomLabel=stringResource(R.string.book_bottom_description)
                        Text(stringResource(R.string.book_top,(line.top*100).toInt()))
                        Slider(value=line.top,onValueChange=model::setTop,valueRange=.1f.. .9f,enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("book-top").semantics{contentDescription=topLabel})
                        Text(stringResource(R.string.book_bottom,(line.bottom*100).toInt()))
                        Slider(value=line.bottom,onValueChange=model::setBottom,valueRange=.1f.. .9f,enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("book-bottom").semantics{contentDescription=bottomLabel})
                    }
                    RmeSecondaryButton(onClick=model::rotate,enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("book-rotate")){Text(stringResource(R.string.book_rotate))}
                    RmeSecondaryButton(onClick=model::reset,enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("book-reset")){Text(stringResource(R.string.book_reset))}
                }
                RmeFilterChip(selected=s.order==BookReadingOrder.LEFT_TO_RIGHT,onClick={model.setOrder(BookReadingOrder.LEFT_TO_RIGHT)},enabled=!s.blocked,label={Text(stringResource(R.string.book_ltr))},modifier=Modifier.fillMaxWidth().testTag("book-ltr"))
                RmeFilterChip(selected=s.order==BookReadingOrder.RIGHT_TO_LEFT,onClick={model.setOrder(BookReadingOrder.RIGHT_TO_LEFT)},enabled=!s.blocked,label={Text(stringResource(R.string.book_rtl))},modifier=Modifier.fillMaxWidth().testTag("book-rtl"))
                if (s.reviews.size > 1) RmeSecondaryButton(onClick={model.select(s.index-1)},enabled=!s.blocked&&s.index>0,modifier=Modifier.fillMaxWidth().testTag("book-previous")){Text(stringResource(R.string.book_previous))}
                if (s.reviews.size > 1) RmeSecondaryButton(onClick={model.select(s.index+1)},enabled=!s.blocked&&s.index<s.reviews.lastIndex,modifier=Modifier.fillMaxWidth().testTag("book-next")){Text(stringResource(R.string.book_next))}
            }
            if(s.reviews.size<s.sourceCount)RmeSecondaryButton(onClick=model::reviewAll,enabled=!s.blocked,modifier=Modifier.fillMaxWidth().testTag("book-all")){Text(stringResource(R.string.book_all,s.sourceCount))}
            RmePrimaryButton(onClick=onApply,enabled=s.work==null&&!s.failed&&s.reviews.isNotEmpty()&&(!s.capturedSpread || s.current?.split==true),modifier=Modifier.fillMaxWidth().testTag("book-apply")){Text(stringResource(if (s.capturedSpread) R.string.camera_book_use else R.string.book_apply))}
            RmeTextButton(onClick=model::close,modifier=Modifier.fillMaxWidth().testTag("book-cancel")){
                Text(stringResource(if(s.capturedSpread) R.string.camera_book_keep else R.string.manual_cancel))
            }
        }
    }
}

@Composable
private fun BookPreviewCard(image:BookPreviewImage,id:Long,filter:DocumentFilter,adjustments:ManualImageAdjustments,number:Int,count:Int,tag:String,modifier: Modifier = Modifier) {
    val uri=Uri.fromFile(image.file)
    FilteredDocumentPreview(FilteredPreviewRequest(id,uri.toString(),filter,DocumentPageRotation.DEGREES_0,image.metadata,adjustments),uri,number,count,
        modifier=modifier.testTag(tag),minHeight=80.dp,maxHeight=200.dp)
}
