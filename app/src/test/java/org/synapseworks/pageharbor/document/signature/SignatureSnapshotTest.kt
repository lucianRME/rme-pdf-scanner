package org.synapseworks.pageharbor.document.signature

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.annotation.*

class SignatureSnapshotTest {
    private fun ink() = HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.1f,.2f))),SignatureStroke(listOf(SignaturePoint(.3f,.4f),SignaturePoint(.9f,.7f)))),aspectRatio = 3f)
    private fun mark(ink: HandwrittenSignature) = AnnotationMark.create("signature",AnnotationTool.SIGNATURE,AnnotationBounds(.1f,.2f,.7f,.4f),AnnotationStyle(rgb=0),signature=ink)
    @Test fun snapshotsDetachBothCollectionsAndRetainTapsSpacingAndAspect() {
        val points=mutableListOf(SignaturePoint(.1f,.2f));val stroke=SignatureStroke(points);val strokes=mutableListOf(stroke)
        val signature=HandwrittenSignature(strokes,aspectRatio=3f);points.clear();strokes.clear()
        assertEquals(SignaturePoint(.1f,.2f),signature.strokes.single().points.single());assertEquals(3f,signature.aspectRatio,0f)
        try {(signature.strokes as MutableList).clear();fail()}catch(_:UnsupportedOperationException){}
        assertEquals(signature,HandwrittenSignature.decode(signature.encoding))
    }
    @Test fun literalSignatureDotMapsThroughQuarterTurnLetterboxZoomAndPan() {
        val ink=HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.85f,.2f)))),aspectRatio=2f)
        val mark=AnnotationMark.create("dot",AnnotationTool.SIGNATURE,AnnotationBounds(.1f,.2f,.7f,.6f),signature=ink)
        val point=mark.signature!!.strokes.single().points.single()
        val canonical=AnnotationPoint(mark.bounds.left+point.x*mark.bounds.width,mark.bounds.top+point.y*mark.bounds.height)
        assertEquals(.61f,canonical.x,.0001f);assertEquals(.28f,canonical.y,.0001f)
        // Frozen source dot(.61,.28), displayed90(.72,.61), fit600x800 in1000x800, zoom2,pan40,-20.
        val viewport=AnnotationViewport(1000f,800f,600f,800f,2f,40f,-20f)
        val pixel=viewport.toDisplay(canonical.rotated(90))
        assertEquals(804f,pixel.first,.001f);assertEquals(556f,pixel.second,.001f)
        val source=viewport.toSource(804f,556f)!!.rotated(270)
        assertEquals(.61f,source.x,.0001f);assertEquals(.28f,source.y,.0001f)
    }
    @Test fun proportionalResizeAtFloatBoundaryCannotOverflowOrStretch() {
        val b=AnnotationBounds(.15196462f,.1f,.9508819f,.4f)
        val resized=b.proportionallyResized(1.1f)
        assertEquals(1f,resized.right,0f)
        assertTrue(resized.bottom<=1f);assertEquals(b.width/b.height,resized.width/resized.height,.00001f)
    }
    @Test fun malformedEmptyOversizedAndNonfiniteAreRejectedWhole() {
        for(data in listOf("","!",ink().encoding+"AAAA"))try{HandwrittenSignature.decode(data);fail()}catch(_:IllegalArgumentException){}
        try{HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.5f,.5f)))),aspectRatio=Float.NaN);fail()}catch(_:IllegalArgumentException){}
        try{HandwrittenSignature(List(5){SignatureStroke(List(2048){SignaturePoint(.5f,.5f)})});fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun literalQuarterTurnPreservesStrokeGroupingAndDot() {
        val rotated=ink().rotated()
        assertEquals(SignaturePoint(.8f,.1f),rotated.strokes.first().points.single());assertEquals(1f/3,rotated.aspectRatio,0f)
        assertEquals(2,rotated.strokes.size);assertEquals(.3f,rotated.strokes.last().points.last().x,.0001f)
    }
    @Test fun groupedLayerRoundTripChargesPointsAndCachesAndRejectsPageOverflow() {
        val s=ink();val layer=AnnotationLayer.of(listOf(mark(s)))
        assertEquals(layer,AnnotationLayer.decode(layer.encode()));assertEquals(1,layer.marks.size)
        assertTrue(layer.metadataBytes > s.metadataBytes+layer.encode().length*2L)
        val huge=HandwrittenSignature(List(4){SignatureStroke(List(2048){SignaturePoint(.5f,.5f)})})
        val m=mark(huge)
        try{AnnotationLayer.of(listOf(m,AnnotationMark.create("b",m.tool,m.bounds,signature=huge),AnnotationMark.create("c",m.tool,m.bounds,signature=s)));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun saveRestartRenameReplaceDeleteLeaveDocumentAndCapturedLayerFrozen() = runBlocking {
        val root=Files.createTempDirectory("synthetic-ink").toFile()
        try {
            val store=SignatureTemplateStore(root);val original=ink();val doc=AnnotationLayer.of(listOf(mark(original)));val captured=doc.encode()
            val saved=store.save("Synthetic A",original).single();assertEquals(1,store.save("Synthetic A",original).size);val restarted=SignatureTemplateStore(root)
            assertEquals(original,restarted.list().single().ink)
            restarted.save("Synthetic B",original.rotated(),saved.id);assertEquals("Synthetic B",restarted.list().single().name)
            restarted.delete(saved.id);assertTrue(restarted.list().isEmpty())
            assertEquals(captured,doc.encode());assertEquals(original,AnnotationLayer.decode(captured).marks.single().signature)
        }finally{root.deleteRecursively()}
    }
    @Test fun corruptOrFailedWritesNeverReplaceExistingLibrary() = runBlocking {
        val root=Files.createTempDirectory("synthetic-ink").toFile()
        try {
            val store=SignatureTemplateStore(root);store.save("Synthetic",ink());val file=File(root,"templates.rmesignatures");val bytes=file.readBytes()
            File(root,"pending").mkdir();try{store.save("Next",ink());fail()}catch(_:Exception){};assertArrayEquals(bytes,file.readBytes());File(root,"pending").delete()
            file.writeBytes(byteArrayOf(1,2,3));try{store.save("Next",ink());fail()}catch(_:Exception){};assertArrayEquals(byteArrayOf(1,2,3),file.readBytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun cancelledSaveCannotPublishOrLeavePendingInk() = runBlocking {
        val root=Files.createTempDirectory("synthetic-ink").toFile()
        try {
            val store=SignatureTemplateStore(root);store.save("Synthetic",ink());val before=File(root,"templates.rmesignatures").readBytes()
            try{kotlinx.coroutines.withContext(kotlinx.coroutines.Job().apply{cancel()}){store.save("Cancelled",ink())};fail()}catch(_:kotlinx.coroutines.CancellationException){}
            assertArrayEquals(before,File(root,"templates.rmesignatures").readBytes());assertFalse(File(root,"pending").exists())
            File(root,"pending").writeBytes(byteArrayOf(1,2,3));assertEquals(1,store.list().size);assertFalse(File(root,"pending").exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun templateAndArchiveLimitsRejectWithoutTruncation() = runBlocking {
        val root=Files.createTempDirectory("synthetic-ink").toFile()
        try {
            val store=SignatureTemplateStore(root);repeat(32){store.save("Synthetic $it",ink())};val before=File(root,"templates.rmesignatures").readBytes()
            try{store.save("Overflow",ink());fail()}catch(_:IllegalArgumentException){}
            assertEquals(32,store.list().size);assertArrayEquals(before,File(root,"templates.rmesignatures").readBytes())
            for(name in listOf(""," ","a".repeat(81),"bad\nname"))try{SignatureTemplate(UUID.randomUUID().toString(),name,ink());fail()}catch(_:IllegalArgumentException){}
        }finally{root.deleteRecursively()}
    }
}
