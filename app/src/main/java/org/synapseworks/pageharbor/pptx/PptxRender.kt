package org.synapseworks.pageharbor.pptx

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Local export-plan preview/fit check, explicitly not a general PowerPoint renderer. */
internal object PptxRender {
    private fun layout(item:PptxItem,box:PptxRect,leading:Float?):StaticLayout {
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#${item.color}");textSize=item.font;typeface=Typeface.create("sans-serif",if(item.bold)Typeface.BOLD else Typeface.NORMAL)}
        require(item.text.codePoints().allMatch{Character.isWhitespace(it)||paint.hasGlyph(String(Character.toChars(it)))})
        return StaticLayout.Builder.obtain(item.text,0,item.text.length,paint,box.width.toInt().coerceAtLeast(1))
            .setAlignment(when(item.align){PptxAlign.LEFT->Layout.Alignment.ALIGN_NORMAL;PptxAlign.CENTER->Layout.Alignment.ALIGN_CENTER;PptxAlign.RIGHT->Layout.Alignment.ALIGN_OPPOSITE})
            .setIncludePad(false).setLineSpacing(leading?.let{it-(paint.fontMetricsInt.descent-paint.fontMetricsInt.ascent)}?:0f,1f).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build()
    }
    fun fit(deck:PptxDeck) {
        require(deck.problem()==null)
        if(deck.mode==PptxMode.IMAGE)return
        for(p in deck.pages)for(b in p.items)if(b.kind==PptxKind.TEXT){val box=PptxGeometry.item(p,deck.size,b.box!!);val l=layout(b,box,PptxGeometry.leading(p,b,deck.size));require(l.height<=box.height+.5f);require((0 until l.lineCount).all{l.getLineWidth(it)<=box.width+.5f})}
    }
    private fun image(canvas:Canvas,media:PptxMedia,b:PptxRect,check:()->Unit) {
        check();val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(media.file.path,options);require(options.outWidth==media.width&&options.outHeight==media.height)
        var sample=1;while(options.outWidth/sample>2048||options.outHeight/sample>3072)sample*=2
        val bitmap=BitmapFactory.decodeFile(media.file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Image unavailable")
        try{check();canvas.drawBitmap(bitmap,null,RectF(b.x,b.y,b.x+b.width,b.y+b.height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))}finally{bitmap.recycle()}
    }
    fun paint(canvas:Canvas,deck:PptxDeck,index:Int,check:()->Unit) {
        check();canvas.drawColor(Color.WHITE);val p=deck.pages[index]
        if(deck.mode==PptxMode.IMAGE){image(canvas,p.visual,PptxGeometry.page(p,deck.size),check);return}
        for(item in p.items){check();val b=PptxGeometry.item(p,deck.size,requireNotNull(item.box));val rect=RectF(b.x,b.y,b.x+b.width,b.y+b.height)
            when(item.kind){
                PptxKind.IMAGE->image(canvas,requireNotNull(item.media),b,check)
                PptxKind.TEXT->{val l=layout(item,b,PptxGeometry.leading(p,item,deck.size));canvas.save();canvas.translate(b.x,b.y);l.draw(canvas);canvas.restore()}
                else->{val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#${item.color}");strokeWidth=1f;style=Paint.Style.FILL};when(item.kind){PptxKind.RECT->canvas.drawRect(rect,pen);PptxKind.ELLIPSE->canvas.drawOval(rect,pen);else->canvas.drawLine(b.x,b.y,b.x+b.width,b.y+b.height,pen)}}
            }
        }
    }
    fun bitmap(deck:PptxDeck,page:Int,check:()->Unit):Bitmap {
        // Review is still pending, but unsafe fonts, geometry, scripts and resource profiles
        // must be rejected before asking Android to construct a text layout or bitmap.
        val reviewedPage=deck.pages[page].copy(coverage=true)
        require(deck.copy(pages=listOf(reviewedPage)).problem(forPreview=true)==null)
        val scale=minOf(960f/deck.size.width,1280f/deck.size.height);val bitmap=Bitmap.createBitmap((deck.size.width*scale).toInt(),(deck.size.height*scale).toInt(),Bitmap.Config.ARGB_8888)
        try{val canvas=Canvas(bitmap);canvas.scale(scale,scale);paint(canvas,deck,page,check);return bitmap}catch(t:Throwable){bitmap.recycle();throw t}
    }
    fun pdf(deck:PptxDeck,file:File,check:()->Unit) {
        require(!file.exists());val pdf=PdfDocument();var accepted=false
        try{
            for(i in deck.pages.indices){check();val page=pdf.startPage(PdfDocument.PageInfo.Builder(deck.size.width.toInt(),deck.size.height.toInt(),i+1).create());paint(page.canvas,deck,i,check);pdf.finishPage(page)}
            file.outputStream().use{raw->val out=object:java.io.FilterOutputStream(raw){var bytes=0L;override fun write(b:Int){check();require(++bytes<=16L*1024*1024);out.write(b)};override fun write(b:ByteArray,o:Int,n:Int){check();bytes+=n;require(bytes<=16L*1024*1024);out.write(b,o,n)}};pdf.writeTo(out)}
            check();require(file.length() in 1..16L*1024*1024);accepted=true
        }finally{pdf.close();if(!accepted)file.delete()}
    }
}
