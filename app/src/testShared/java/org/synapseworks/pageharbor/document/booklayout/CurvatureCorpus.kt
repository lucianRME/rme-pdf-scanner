package org.synapseworks.pageharbor.document.booklayout

import java.io.InputStream
import kotlin.math.*

/** Original CC0 fixture renderer. Forward scenes/flat truth never call M12 production geometry. */
internal data class CurveScene(val id:String,val partition:String,val kind:String,val family:String,val amplitude:Double,
    val taper:Double,val turns:Int,val glyphHeight:Int,val colour:Boolean,val illustration:Boolean,val seed:Int) {
    val positive get()=kind=="CURVED"
    fun shape(x:Double):Double=when(family){
        "COS"->cos(PI*x)
        "GAUSS_L"->exp(-((x-.08)/.29).pow(2))
        "GAUSS_R"->exp(-((x-.92)/.29).pow(2))
        "SINE"->.72*sin(PI*x)+.28*sin(2*PI*x+.3)
        "SHOULDER"->(tanh((x-.27)*5)-tanh((x-.79)*7))*.65+.25*x*x
        else->error("Unknown independent fixture")
    }
    fun offset(x:Double)=amplitude*shape(x)
    fun scale(x:Double)=1.0+taper*(x-.5)
    fun sourceY(x:Double,flatY:Double):Double {
        val y=offset(x)+scale(x)*flatY
        return if(kind=="TWIST")y+.09*sin(2*PI*flatY)*cos(PI*x)else y
    }
    // The estimator's central strip defines a fixed affine gauge, not a fitted result alignment.
    fun referenceY(flatY:Double)=offset(.5)+scale(.5)*flatY
}
internal data class CurvePixels(val width:Int,val height:Int,val pixels:IntArray)
internal object CurvatureCorpus {
    const val WIDTH=480;const val HEIGHT=640
    fun scenes(input:InputStream):List<CurveScene> = input.bufferedReader().useLines{lines->lines.drop(1).filter{it.isNotBlank()}.map{row->
        val p=row.split(',');CurveScene(p[0],p[1],p[2],p[3],p[4].toDouble(),p[5].toDouble(),p[6].toInt(),p[7].toInt(),p[8]=="1",p[9]=="1",p[10].toInt())
    }.toList()}
    fun baselines(scene:CurveScene):List<Double> = (0 until 17).map{i->.13+i*.043+(if(i%3==0).003 else if(i%3==1)-.002 else .001)}
    private fun paper(scene:CurveScene)=if(scene.colour)0xfff2e3bd.toInt()else 0xfff9f7f1.toInt()
    private fun ink(scene:CurveScene)=if(scene.colour)0xff394c78.toInt()else if(scene.glyphHeight==7)0xff929292.toInt()else 0xff333333.toInt()
    fun flat(scene:CurveScene):CurvePixels {
        val w=WIDTH;val h=HEIGHT;val pixels=IntArray(w*h){paper(scene)}
        fun rect(l:Int,t:Int,r:Int,b:Int,c:Int){for(y in t.coerceAtLeast(0)until b.coerceAtMost(h))for(x in l.coerceAtLeast(0)until r.coerceAtMost(w))pixels[y*w+x]=c}
        if(scene.kind=="BLANK")return CurvePixels(w,h,pixels)
        if(scene.kind=="PHOTO"){
            for(y in 0 until h)for(x in 0 until w){val r=(128+90*sin(x*.037+y*.009)).toInt().coerceIn(0,255);val g=(128+85*cos(y*.028+x*.004)).toInt().coerceIn(0,255);val b=(80+50*sin(x*y*.00007)).toInt().coerceIn(0,255);pixels[y*w+x]=(255 shl 24)or(r shl 16)or(g shl 8)or b};return CurvePixels(w,h,pixels)
        }
        if(scene.kind=="DIAGRAM"){
            for(i in 0..9){val x=25+(i*37+scene.seed)%400;val y=30+(i*53)%540;rect(x,y,x+22,y+45,0xff535353.toInt());rect(x+5,y+4,x+17,y+41,paper(scene))};return CurvePixels(w,h,pixels)
        }
        if(scene.kind=="GRID"){
            for(y in 60 until 590 step 29)rect(15,y,465,y+2,ink(scene));for(x in 20..460 step 44)rect(x,50,x+2,600,ink(scene));return CurvePixels(w,h,pixels)
        }
        for((row,yn)in baselines(scene).withIndex()){
            val y=(yn*h).roundToInt();val gh=scene.glyphHeight;val pitch=9+(row%3);val right=if(row%4==0)432 else 459
            for(x in 15 until right step pitch){
                // Deterministic, irregular glyph strokes: no external font or production detector.
                val bits=(x*31+row*173+scene.seed*7).ushr(1);if(bits%11==0)continue
                rect(x,y-gh,x+2,y,ink(scene));rect(x+2,y-gh,x+6,y-gh+2,ink(scene))
                if(bits%3!=0)rect(x+2,y-2,x+6,y,ink(scene));if(bits%4!=0)rect(x+5,y-gh+2,x+7,y-2,ink(scene))
                if(bits%5==0)rect(x+2,y-gh/2,x+6,y-gh/2+1,ink(scene))
            }
            if(scene.illustration&&row in 4..6)rect(320,y-gh,430,y,paper(scene))
        }
        if(scene.illustration){rect(327,174,424,264,0xff568cb2.toInt());for(i in 0..5)rect(330+i*14,178,334+i*14,260,0xff8a4b63.toInt())}
        if(scene.seed%5==0){for(x in listOf(12,155,307,466))rect(x,77,x+1,574,0xff777777.toInt());for(y in listOf(75,289,573))rect(12,y,467,y+1,0xff777777.toInt())}
        return CurvePixels(w,h,pixels)
    }
    fun distorted(scene:CurveScene):CurvePixels {
        val flat=flat(scene);val w=flat.width;val h=flat.height;val pixels=IntArray(w*h){paper(scene)}
        for(y in 0 until h)for(x in 0 until w){
            val xn=x.toDouble()/(w-1);val yn=y.toDouble()/(h-1)
            val fx=if(scene.kind=="KEYSTONE")(xn-.5)/(1-.24*(yn-.5))+.5 else xn
            var fy=(yn-scene.offset(xn))/scene.scale(xn)
            if(scene.kind=="TWIST")repeat(12){fy-=(scene.sourceY(xn,fy)-yn)/(scene.scale(xn)+.18*PI*cos(2*PI*fy)*cos(PI*xn))}
            if(scene.kind=="COLUMNS")fy+=(if(xn<.5)-.045 else .045)
            pixels[y*w+x]=if(fx !in 0.0..1.0||fy !in 0.0..1.0)0xff686868.toInt()else sample(flat,fx*(w-1),fy*(h-1))
        }
        return CurvePixels(w,h,pixels)
    }
    private fun sample(image:CurvePixels,x:Double,y:Double):Int {
        // Independent fixture interpolation, deliberately no production sampler reuse.
        val l=floor(x).toInt().coerceIn(0,image.width-1);val t=floor(y).toInt().coerceIn(0,image.height-1);val r=(l+1).coerceAtMost(image.width-1);val b=(t+1).coerceAtMost(image.height-1);val dx=x-l;val dy=y-t
        var out=0xff000000.toInt()
        for(shift in listOf(16,8,0)){fun c(xx:Int,yy:Int)=(image.pixels[yy*image.width+xx]ushr shift)and 255
            val a=c(l,t)*(1-dx)+c(r,t)*dx;val z=c(l,b)*(1-dx)+c(r,b)*dx;out=out or((a*(1-dy)+z*dy).roundToInt().coerceIn(0,255)shl shift)}
        return out
    }
    fun landmarks(scene:CurveScene)=baselines(scene).flatMap{y->listOf(.045,.12,.25,.38,.5,.64,.77,.9,.95).map{x->x to y}}
    fun protected(scene:CurveScene):List<Pair<Double,Double>> {
        val image=flat(scene);val bg=paper(scene);return buildList{
            for(y in 0 until image.height step 2)for(x in 0 until image.width step 2)if(image.pixels[y*image.width+x]!=bg)add(x.toDouble()/(image.width-1)to y.toDouble()/(image.height-1))
        }
    }
}
