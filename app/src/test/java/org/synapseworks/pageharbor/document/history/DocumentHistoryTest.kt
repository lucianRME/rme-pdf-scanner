package org.synapseworks.pageharbor.document.history

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import kotlin.random.Random

class DocumentHistoryTest {
    private data class Value(val id:Long,val source:String,val rotation:Int=0,val filter:Int=0,val brightness:Int=0)
    private fun frame(values:List<Value>)=DocumentSession(values.map{v->DocumentPage(DocumentPageId(v.id),DocumentResource(v.source,DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,
        rotation=DocumentPageRotation.entries[v.rotation],filter=DocumentFilter.entries[v.filter],adjustments=ManualImageAdjustments(v.brightness,0))})
    private fun values(s:DocumentSession)=s.pages.map{Value(it.id.value,it.source.reference,it.rotation.ordinal,it.filter.ordinal,it.adjustments.brightness)}
    // Independent value-stack oracle; it never reads the implementation's snapshots/cursor/labels.
    private class Reference(initial:List<Value>){var now=initial;val past=mutableListOf<List<Value>>();val future=mutableListOf<List<Value>>()
        fun edit(next:List<Value>){if(next==now)return;past+=now;now=next;future.clear();if(past.size>32)past.removeAt(0)}
        fun undo(){if(past.isNotEmpty()){future+=now;now=past.removeAt(past.lastIndex)}}
        fun redo(){if(future.isNotEmpty()){past+=now;now=future.removeAt(future.lastIndex)}}
        fun boundary(next:List<Value>){now=next;past.clear();future.clear()}}
    @Test fun deterministicMixedTransactionsMatchIndependentReferenceThroughUndoRedoAndBranches(){
        val initial=(1L..4L).map{Value(it,"source:$it")};val reference=Reference(initial);val history=DocumentHistory();history.reset(frame(initial));val random=Random(73119);var nextId=5L
        repeat(600){step->
            when(val action=random.nextInt(13)){
                9,10->{val move=history.move(action==9);if(action==9)reference.undo()else reference.redo();if(move!=null)assertTrue(history.accept(move))}
                11->{val v=listOf(Value(nextId++,"boundary:$step"));reference.boundary(v);history.reset(frame(v))}
                else->{val old=reference.now;val index=random.nextInt(old.size)
                    val next=when(action){
                        0->old.mapIndexed{i,v->if(i==index)v.copy(rotation=(v.rotation+1)%4)else v}
                        1->old.mapIndexed{i,v->if(i==index)v.copy(filter=3)else v}
                        2->old.mapIndexed{i,v->if(i==index)v.copy(brightness=25)else v}
                        3->old.reversed()
                        4->if(old.size>1)old.filterIndexed{i,_->i!=index}else old
                        5->old+Value(nextId++,"append:$step")
                        6->old.flatMapIndexed{i,v->if(i==index)listOf(Value(nextId++,"left:$step"),Value(nextId++,"right:$step"))else listOf(v)}
                        7->old.mapIndexed{i,v->if(i==index)Value(nextId++,"curve:$step",filter=v.filter,brightness=v.brightness)else v}
                        8->old.map{it.copy(filter=4,brightness=-10)}
                        else->old
                    }
                    reference.edit(next);history.record(history.present,frame(next),HistoryAction.entries[minOf(action,8)])
                }
            }
            assertEquals("step $step",reference.now,values(history.present));assertEquals(reference.past.size,history.undoCount);assertEquals(reference.future.size,history.redoCount)
        }
        while(reference.past.isNotEmpty()){reference.undo();assertTrue(history.accept(history.move(true)!!));assertEquals(reference.now,values(history.present))}
        while(reference.future.isNotEmpty()){reference.redo();assertTrue(history.accept(history.move(false)!!));assertEquals(reference.now,values(history.present))}
    }
    @Test fun noOpStaleMovesAndReplayNeverCreateExtraTransactions(){
        val h=DocumentHistory();val original=frame(listOf(Value(1,"a")));h.reset(original);assertTrue(h.record(original,original.copy(),HistoryAction.FILTER).isEmpty());assertEquals(0,h.undoCount)
        h.record(h.present,frame(listOf(Value(1,"a",rotation=1))),HistoryAction.ROTATE);val move=h.move(true)!!
        h.record(h.present,frame(listOf(Value(1,"a",rotation=2))),HistoryAction.ROTATE);assertFalse(h.accept(move));assertEquals(2,h.undoCount)
        assertTrue(h.accept(h.move(true)!!));assertEquals(1,h.undoCount);assertEquals(1,h.redoCount)
        h.record(h.present,frame(listOf(Value(1,"a",filter=3))),HistoryAction.FILTER);assertEquals(0,h.redoCount);assertNull(h.move(false))
    }
    @Test fun entryMetadataAndPageAdmissionLimitsPreserveCurrentAndExposeTruncation(){
        val h=DocumentHistory(HistoryLimits(entries=3,metadataBytes=2000,pages=5));h.reset(frame(listOf(Value(1,"a"))))
        repeat(20){h.record(h.present,frame(listOf(Value(1,"a",rotation=it%4))),HistoryAction.ROTATE)}
        assertTrue(h.undoCount<=3);assertTrue(h.metadataCharge()<=2000);assertTrue(h.limited)
        val huge=frame((1L..6L).map{Value(it,"source:$it")});h.record(h.present,huge,HistoryAction.APPEND);assertSame(huge,h.present);assertEquals(0,h.undoCount);assertTrue(h.dirty)
    }
    @Test fun byteEvictionAndExternalLibraryReferenceRebaseDoNotRewriteCurrentEdits(){
        val h=DocumentHistory(HistoryLimits(temporaryBytes=10));val original=frame(listOf(Value(1,"a")));h.reset(original)
        repeat(3){h.record(h.present,frame(listOf(Value(1,"v:$it"))),HistoryAction.CURVATURE)}
        h.evictToByteLimit{states->(states.size-1)*8L};assertTrue(h.undoCount<=1)
        val pages=h.present.pages;h.rebaseLibrary(LibraryDocumentReference("saved","New name","folder"));assertSame(pages,h.present.pages);assertTrue(h.dirty)
        h.move(true)?.let{assertEquals("New name",it.target.libraryDocument!!.title)}
        val bounded=DocumentHistory(HistoryLimits(metadataBytes=1200));bounded.reset(original)
        repeat(2){bounded.record(bounded.present,frame(listOf(Value(1,"a",rotation=it+1))),HistoryAction.ROTATE)}
        assertEquals(2,bounded.undoCount);val currentPages=bounded.present.pages
        val retired=bounded.rebaseLibrary(LibraryDocumentReference("saved","n".repeat(80)))
        assertTrue(retired.isNotEmpty());assertTrue(bounded.metadataCharge()<=1200);assertTrue(bounded.limited);assertSame(currentPages,bounded.present.pages)
        assertEquals(1,bounded.undoCount);assertTrue(bounded.dirty)
    }
    @Test fun largeDirectPdfAndLibraryResourceReferencesAreIncludedInMetadataBudget(){
        val huge=DocumentResource("content:"+"x".repeat(4000),DocumentResourceOwnership.USER_OR_EXTERNAL)
        val original=frame(listOf(Value(1,"a"))).copy(directPdfSource=huge,directPdfPageIds=listOf(DocumentPageId(1)),originalPdfSources=listOf(huge))
        val h=DocumentHistory(HistoryLimits(metadataBytes=4096));h.reset(original)
        val changed=original.copy(pages=listOf(original.pages.single().copy(rotation=DocumentPageRotation.DEGREES_90)))
        h.record(original,changed,HistoryAction.ROTATE);assertSame(changed,h.present);assertEquals(0,h.undoCount);assertTrue(h.limited);assertTrue(h.metadataCharge()<=4096)
    }
    @Test fun annotationTextPointsAndPropertiesAreChargedAndNoOpDoesNotFloodHistory(){
        val h=DocumentHistory(HistoryLimits(metadataBytes=1800));val original=frame(listOf(Value(1,"a")));h.reset(original)
        val mark=org.synapseworks.pageharbor.document.annotation.AnnotationMark.create("long-text",org.synapseworks.pageharbor.document.annotation.AnnotationTool.TEXT,
            org.synapseworks.pageharbor.document.annotation.AnnotationBounds(.1f,.1f,.9f,.9f),text="x".repeat(1000))
        val layer=org.synapseworks.pageharbor.document.annotation.AnnotationLayer.of(listOf(mark))
        val first=original.copy(pages=listOf(original.pages[0].copy(annotations=layer)))
        h.record(original,first,HistoryAction.ANNOTATE);assertTrue(h.dirty);assertEquals(1,h.undoCount)
        h.record(first,first.copy(),HistoryAction.ANNOTATE);assertEquals(1,h.undoCount)
        val second=first.copy(pages=listOf(first.pages[0].copy(annotations=layer.put(mark.withText("y".repeat(1000))))))
        h.record(first,second,HistoryAction.ANNOTATE)
        assertSame(second,h.present);assertTrue(h.metadataCharge()<=1800);assertTrue(h.limited);assertEquals(0,h.undoCount)
    }

}
