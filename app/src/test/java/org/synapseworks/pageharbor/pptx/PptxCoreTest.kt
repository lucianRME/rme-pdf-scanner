package org.synapseworks.pageharbor.pptx

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.zip.ZipFile
import org.synapseworks.pageharbor.docx.*

class PptxCoreTest {
    private fun fixtures(root:File,size:PptxSize):List<PptxPage> {
        val json=javaClass.getResourceAsStream("/m34/objects.json")!!.reader().use{it.readText()}
        return FrozenPptxCorpus.pages(json,size){name,w,h->
            val f=File(root,"$name.jpg")
            if(!f.exists()) {
                val bytes=javaClass.getResourceAsStream("/m34/$name.jpg")?.use{it.readBytes()}
                if(bytes!=null)f.writeBytes(bytes)else {
                    val image=ImageIO.read(javaClass.getResourceAsStream("/m34/$name.png"));val rgb=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_RGB);rgb.graphics.apply{drawImage(image,0,0,null);dispose()};ImageIO.write(rgb,"jpg",f)
                }
            }
            val image=ImageIO.read(f);PptxMedia(f,image.width,image.height,PptxWriter.digest(f))
        }
    }
    @Test fun frozenBothModesAllSizesHaveRealObjectsAndNoSourcePageBehindEditableText() {
        val root=Files.createTempDirectory("m34-").toFile();val reports=File("build/reports/m34-pptx").apply{mkdirs()}
        try {
            for(size in PptxSize.entries) {
                val pages=fixtures(root,size)
                for(mode in PptxMode.entries) {
                    val d=PptxDeck(pages,mode,size);assertNull(d.problem());val f=File(root,"${mode}-${size}.pptx");PptxWriter.write(d,f);f.copyTo(File(reports,f.name),true)
                    ZipFile(f).use{z->
                        val names=z.entries().asSequence().map{it.name}.toList();assertTrue(names.none{it.contains("notes",true)||it.contains("docProps")||it.contains("vba",true)||it.contains("external",true)})
                        for(i in pages.indices){val xml=z.getInputStream(z.getEntry("ppt/slides/slide${i+1}.xml")).reader().readText()
                            if(mode==PptxMode.IMAGE){assertFalse(xml.contains("<a:t"));assertEquals(1,"<p:pic>".toRegex().findAll(xml).count())}
                            else{assertTrue(xml.contains("<p:txBody>"));assertTrue(xml.contains("<a:normAutofit/>"));assertEquals(pages[i].items.count{it.kind==PptxKind.IMAGE},"<p:pic>".toRegex().findAll(xml).count());assertFalse(xml.contains("portrait.png"));assertFalse(xml.contains("landscape.png"))}
                            assertFalse(xml.contains("<p:timing"));assertFalse(xml.contains("hlinkClick"))
                        }
                    }
                }
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun textImageOverlapAspectMissingGeometryAndUnsupportedProfilesDeclineClearly() {
        val root=Files.createTempDirectory("m34-profile-").toFile()
        try {
            val p=fixtures(root,PptxSize.WIDE).first();fun problem(page:PptxPage)=PptxDeck(listOf(page),PptxMode.EDITABLE).problem()
            assertNotNull(problem(p.copy(rotation=90)));assertNotNull(problem(p.copy(marked=true)));assertNotNull(problem(p.copy(coverage=false)))
            assertNotNull(problem(p.copy(items=p.items.map{if(it.kind==PptxKind.TEXT)it.copy(box=null)else it})))
            val text=p.items.first{it.kind==PptxKind.TEXT};assertNotNull(problem(p.copy(items=p.items.map{if(it.kind==PptxKind.IMAGE)it.copy(sourceRegion=text.box)else it})))
            assertNotNull(problem(p.copy(items=p.items.map{if(it.kind==PptxKind.IMAGE)it.copy(box=DocxBox(.1f,.45f,.3f,.46f))else it})))
            for(bad in listOf("שלום","नमस्ते","😀","\uFFFE","\uD800"))assertNotNull(problem(p.copy(items=p.items.map{if(it.id==text.id)it.copy(text=bad)else it})))
            val imageOnly=p.copy(items=p.items.filter{it.kind==PptxKind.IMAGE});assertNotNull(problem(imageOnly))
            assertNull(PptxDeck(listOf(p.copy(marked=true,rotation=90)),PptxMode.IMAGE).problem())
        }finally{root.deleteRecursively()}
    }
    @Test fun originalOccurrenceOrderRawReadingsAndSnapshotsStayIndependent() {
        val root=Files.createTempDirectory("m34-snapshot-").toFile()
        try {
            val p=fixtures(root,PptxSize.WIDE).last();val texts=p.items.filter{it.kind==PptxKind.TEXT};assertEquals(2,texts.count{it.text=="Repeated text"});assertNotEquals(texts[1].sourceIds,texts[2].sourceIds)
            val snap=PptxDeck(listOf(p),PptxMode.EDITABLE).owned();val changed=p.copy(items=p.items.map{if(it.id==texts[1].id)it.copy(text="Changed occurrence")else it})
            assertEquals("Repeated text",snap.pages[0].items[1].text);assertTrue(changed.source.sourceText.contains("Repeated text"));assertEquals("Repeated text",changed.items[2].text)
            val proposals=PptxGeometry.proposals(p.source,true);assertEquals(p.source.lines.map{it.id}.toSet(),proposals.flatMap{it.sourceIds}.toSet())
        }finally{root.deleteRecursively()}
    }
    @Test fun cancellationMissingMediaAndChangedResourcesNeverPublishPartialPackages() {
        val root=Files.createTempDirectory("m34-failure-").toFile()
        try {
            val d=PptxDeck(fixtures(root,PptxSize.WIDE),PptxMode.EDITABLE)
            val a=File(root,"cancel.pptx");assertThrows(IllegalStateException::class.java){PptxWriter.write(d,a,progress={error("Synthetic cancellation")})};assertFalse(a.exists())
            val pic=d.pages.first().items.first{it.kind==PptxKind.IMAGE}.media!!;pic.file.writeBytes(byteArrayOf(1,2,3))
            val b=File(root,"changed.pptx");assertThrows(IllegalArgumentException::class.java){PptxWriter.write(d,b)};assertFalse(b.exists())
            pic.file.delete();val c=File(root,"missing.pptx");assertThrows(IllegalArgumentException::class.java){PptxWriter.write(d,c)};assertFalse(c.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun everySlideSizeContainsPortraitLandscapeAndTenPagesWithinResourceBounds() {
        val root=Files.createTempDirectory("m34-bound-").toFile()
        try {
            for(size in PptxSize.entries)for(p in fixtures(root,size)) {
                val r=PptxGeometry.page(p,size);assertTrue(r.x>=0&&r.y>=0&&r.x+r.width<=size.width+.001f&&r.y+r.height<=size.height+.001f)
                assertEquals(p.visual.width.toDouble()/p.visual.height,r.width.toDouble()/r.height,.00001)
            }
            val p=fixtures(root,PptxSize.WIDE).first();val ten=PptxDeck(List(10){p});assertNull(ten.problem());val f=File(root,"ten.pptx");val start=System.nanoTime();PptxWriter.write(ten,f);assertTrue(f.length()<PPTX_MAX);assertTrue((System.nanoTime()-start)/1e9<15);assertNotNull(ten.copy(pages=List(11){p}).problem())
        }finally{root.deleteRecursively()}
    }
}
