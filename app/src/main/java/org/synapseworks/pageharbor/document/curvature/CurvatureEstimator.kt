package org.synapseworks.pageharbor.document.curvature

import kotlin.math.*

/** Original bounded text-track estimator. No OCR, learned model, scene formula or M11 mutation.
 * Confident means the declared evidence rules pass, not a calibrated probability.
 */
internal object CurvatureEstimator {
    private data class Fit(val a:Double,val b:Double,val count:Int,val rms:Double)
    fun estimate(image:CurvatureImage,check:()->Unit={}):CurvatureEstimate {
        val w=image.width;val h=image.height
        if(w<120||h<160||w.toDouble()/h !in .35..1.8)return CurvatureEstimate(CurvatureStatus.UNSUPPORTED)
        fun luminance(p:Int):Int {val v=CurvatureSampling.white(p);return (((v ushr 16)and 255)*77+((v ushr 8)and 255)*150+(v and 255)*29)ushr 8}
        // Visible page-edge taper is a warning, never an inferred horizontal correction.
        val widths=listOf(.18,.5,.82).map{fraction->
            val y=(fraction*(h-1)).toInt();var l=0;var r=w-1
            while(l<w&&luminance(image.pixels[y*w+l])<180)l++
            while(r>l&&luminance(image.pixels[y*w+r])<180)r--
            r-l+1
        }
        if(widths.max()-widths.min()>w*.075)return CurvatureEstimate(CurvatureStatus.UNSUPPORTED)
        val nodes=13;val positions=List(nodes){.05+it*.9/(nodes-1)}
        val peaks=positions.map{x->
            check();val left=((x-.026)*w).toInt().coerceIn(0,w-2);val right=((x+.026)*w).toInt().coerceIn(left+1,w-1);val width=right-left
            val shades=ArrayList<Int>();for(y in h/10 until h*9/10 step max(1,h/40))for(xx in left until right step max(1,width/8))shades+=luminance(image.pixels[y*w+xx])
            shades.sort();val threshold=min(205,shades[(shades.size*.85).toInt()]-35)
            val projection=IntArray(h)
            for(y in h/12 until h*11/12){
                if(y%32==0)check();var count=0;for(xx in left until right)if(luminance(image.pixels[y*w+xx])<threshold)count++
                projection[y]=if(count>width*.82)0 else count
            }
            val active=BooleanArray(h){projection[it]>=max(2.0,width*.12).toInt()}
            // Bridge up to two empty raster rows within one glyph band, without joining text lines.
            for(y in 1 until h-2)if(active[y-1]&&!active[y]){
                if(active[y+1])active[y]=true
                else if(active[y+2]){active[y]=true;active[y+1]=true}
            }
            val out=mutableListOf<Double>();var y=h/12
            while(y<h*11/12){
                if(!active[y]){y++;continue};val start=y;while(y<h*11/12&&active[y])y++;val length=y-start
                if(length in 2..max(4,(h*.04).toInt())){
                    var weight=0.0;var sum=0.0;for(yy in start until y){weight+=projection[yy];sum+=projection[yy]*yy}
                    if(weight>0)out+=sum/weight/(h-1)
                }
            };out
        }
        val centre=nodes/2;val reference=peaks[centre]
        if(reference.size !in 8..36||reference.last()-reference.first()<.50)return CurvatureEstimate(CurvatureStatus.UNCERTAIN,tracks=reference.size)
        val gaps=reference.zipWithNext{a,b->b-a};if(gaps.count{it in .018.. .10}<gaps.size*.85)return CurvatureEstimate(CurvatureStatus.UNCERTAIN,tracks=reference.size)
        val fits=arrayOfNulls<Fit>(nodes);fits[centre]=Fit(0.0,1.0,reference.size,0.0)
        for(direction in listOf(-1,1)){
            var i=centre+direction
            while(i in 0 until nodes){
                check();val previous=requireNotNull(fits[i-direction]);val fit=fit(reference,peaks[i],previous)
                    ?:return CurvatureEstimate(CurvatureStatus.UNCERTAIN,tracks=previous.count)
                if(abs(fit.a-previous.a)>.035||abs(fit.b-previous.b)>.04)return CurvatureEstimate(CurvatureStatus.UNSUPPORTED)
                fits[i]=fit;i+=direction
            }
        }
        val aa=fits.map{it!!.a};val bb=fits.map{it!!.b}
        // Abrupt track-phase changes can masquerade as curl in independent columns.
        if((1 until aa.lastIndex).any{abs(aa[it-1]-2*aa[it]+aa[it+1])>.025})return CurvatureEstimate(CurvatureStatus.UNCERTAIN)
        // Resample measured strips onto equally spaced knots; no scene-family fit.
        fun value(v:List<Double>,x:Double):Float {
            if(x<=.05)return (v[0]+(v[1]-v[0])*(x-.05)/.075).toFloat()
            if(x>=.95)return (v.last()+(v.last()-v[v.lastIndex-1])*(x-.95)/.075).toFloat()
            val p=(x-.05)/.075;val i=floor(p).toInt().coerceIn(0,nodes-2);return(v[i]+(v[i+1]-v[i])*(p-i)).toFloat()
        }
        val offsets=List(21){value(aa,it/20.0)};val scales=List(21){value(bb,it/20.0)}
        if(offsets.any{abs(it)>.145f}||scales.any{it !in .84f..1.16f})return CurvatureEstimate(CurvatureStatus.UNSUPPORTED)
        val nonlinear=offsets.indices.maxOf{i->abs(offsets[i]-(offsets.first()*(1-i/20f)+offsets.last()*(i/20f)))}
        val spacing=scales.max()-scales.min()
        if(nonlinear<.0035f)return CurvatureEstimate(if(spacing<.025f)CurvatureStatus.FLAT else CurvatureStatus.UNCERTAIN,tracks=reference.size,spacingVariation=spacing)
        val field=runCatching{CurvatureField(offsets,scales).also{it.dimensions(w,h)}}.getOrNull()?:return CurvatureEstimate(CurvatureStatus.UNSUPPORTED)
        return CurvatureEstimate(CurvatureStatus.PROPOSED,field,reference.size,spacing)
    }
    private fun fit(reference:List<Double>,target:List<Double>,previous:Fit):Fit? {
        if(target.size<7)return null
        var best:Fit?=null
        for(r in reference)for(t in target){
            var a=t-previous.b*r;var b=previous.b;if(abs(a-previous.a)>.035)continue
            var pairs=emptyList<Pair<Double,Double>>()
            repeat(3){
                val used=mutableSetOf<Int>();pairs=reference.mapNotNull{y->val predicted=a+b*y
                    val nearest=target.indices.minByOrNull{abs(target[it]-predicted)}?:return@mapNotNull null
                    if(abs(target[nearest]-predicted)>.007||!used.add(nearest))null else y to target[nearest]}
                if(pairs.size>=4){val x=pairs.map{it.first}.average();val y=pairs.map{it.second}.average()
                    val denominator=pairs.sumOf{(it.first-x).pow(2)}
                    if(denominator>0){b=pairs.sumOf{(it.first-x)*(it.second-y)}/denominator;a=y-b*x}}
            }
            if(pairs.size<7||pairs.last().first-pairs.first().first<.48||b !in .84..1.16)continue
            val rms=sqrt(pairs.sumOf{(it.second-a-b*it.first).pow(2)}/pairs.size)
            if(rms>.0025)continue
            val candidate=Fit(a,b,pairs.size,rms)
            val old=best
            if(old==null||candidate.count>old.count||candidate.count==old.count&&candidate.rms+abs(candidate.a-previous.a)*.002<old.rms+abs(old.a-previous.a)*.002)best=candidate
        }
        return best
    }
}
