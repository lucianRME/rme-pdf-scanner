package org.synapseworks.pageharbor.image

import kotlin.math.*

/** Immutable narrowband v1. No semantic classification or reconstruction of obscured detail.
 * Fixed-size sampled harmonic regression; output uses seven source/prediction and five guard
 * rows. Source ownership, admission and disposal of partial results belong to the caller.
 */
internal object MoireFilter {
    private data class Frequency(val x:Int,val y:Int)
    private data class Model(val frequencies:List<Frequency>,val coefficients:DoubleArray,val paper:Double)
    private val bank=listOf(16,24,32,48).flatMap{k->listOf(Frequency(k,0),Frequency(0,k),Frequency(k,k),Frequency(k,-k))}

    fun process(width:Int,height:Int,amount:Float,readRow:(Int,IntArray)->Unit,writeRow:(Int,IntArray)->Unit,ensureActive:()->Unit={}) {
        require(amount in 0f..1f);require(ArgbImage.isSupportedDimensions(width,height))
        val model=estimate(width,height,readRow,ensureActive)
        if(model==null||amount==0f){val row=IntArray(width);for(y in 0 until height){ensureActive();readRow(y,row);writeRow(y,row)};ensureActive();return}
        val length=max(width,height).toDouble()
        val cosX=model.frequencies.map{f->DoubleArray(width){x->cos(2*PI*f.x*x/length)}}
        val sinX=model.frequencies.map{f->DoubleArray(width){x->sin(2*PI*f.x*x/length)}}
        val source=Array(7){IntArray(width)};val prediction=Array(7){FloatArray(width)}
        val seeds=Array(5){BooleanArray(width)};val columns=IntArray(width);val output=IntArray(width)
        var loaded=-1;var seeded=-1
        fun load(y:Int){readRow(y,source[y%7]);val row=prediction[y%7];row.fill(0f)
            for((j,f)in model.frequencies.withIndex()){ensureActive();val a=model.coefficients[1+2*j];val b=model.coefficients[2+2*j];val angle=2*PI*f.y*y/length;val c=cos(angle);val s=sin(angle)
                for(x in 0 until width){if(x%256==0)ensureActive();row[x]+=(a*(cosX[j][x]*c-sinX[j][x]*s)+b*(sinX[j][x]*c+cosX[j][x]*s)).toFloat()}}
        }
        fun seed(y:Int,dst:BooleanArray){for(x in 0 until width){if(x%256==0)ensureActive();val p=source[y%7][x];val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
            var minR=Double.POSITIVE_INFINITY;var minG=minR;var minB=minR;var maxR=Double.NEGATIVE_INFINITY;var maxG=maxR;var maxB=maxR
            for(sy in max(0,y-1)..min(height-1,y+1))for(sx in max(0,x-1)..min(width-1,x+1)){val q=source[sy%7][sx];val n=prediction[sy%7][sx].toDouble();val qr=(q ushr 16 and 255)-n;val qg=(q ushr 8 and 255)-n;val qb=(q and 255)-n
                minR=min(minR,qr);maxR=max(maxR,qr);minG=min(minG,qg);maxG=max(maxG,qg);minB=min(minB,qb);maxB=max(maxB,qb)}
            dst[x]=p ushr 24!=255||maxOf(r,g,b)-minOf(r,g,b)>24||maxOf(maxR-minR,maxG-minG,maxB-minB)>=12||DocumentImageFilterEngine.pixelLuminance(p)-prediction[y%7][x]<model.paper-30
        }}
        for(y in 0 until height){ensureActive()
            while(loaded<min(height-1,y+3)){loaded++;load(loaded)}
            val remove=y-3;if(remove>=0){val old=seeds[remove%5];for(x in 0 until width)if(old[x])columns[x]--}
            while(seeded<min(height-1,y+2)){seeded++;val row=seeds[seeded%5];seed(seeded,row);for(x in 0 until width)if(row[x])columns[x]++}
            source[y%7].copyInto(output);var guards=0;for(x in 0..min(width-1,2))guards+=columns[x]
            for(x in 0 until width){if(x%256==0)ensureActive();if(guards==0){val p=output[x];val noise=prediction[y%7][x]*amount
                fun channel(s:Int)=((p ushr s and 255)-noise).roundToInt().coerceIn(0,255)
                output[x]=(p and -0x1000000)or(channel(16)shl 16)or(channel(8)shl 8)or channel(0)}
                val left=x-2;if(left>=0)guards-=columns[left];val right=x+3;if(right<width)guards+=columns[right]
            };writeRow(y,output)
        };ensureActive()
    }

    private fun estimate(width:Int,height:Int,readRow:(Int,IntArray)->Unit,active:()->Unit):Model? {
        val stride=(max(width,height)+127)/128;val cols=(width+stride-1)/stride;val rows=(height+stride-1)/stride
        val pixels=IntArray(cols*rows);val histogram=IntArray(256);val row=IntArray(width);var n=0;var opaque=0;var neutral=0
        for(y in 0 until height step stride){active();readRow(y,row);for(x in 0 until width step stride){if(x%256==0)active();val p=row[x];pixels[n++]=p
            if(p ushr 24==255){opaque++;val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255;if(maxOf(r,g,b)-minOf(r,g,b)<=12){neutral++;histogram[DocumentImageFilterEngine.pixelLuminance(p)]++}}}}
        if(width<32||height<32||max(width,height)<384||opaque!=n||neutral<n*.75)return null
        var sum=0;var paper=0;for(v in 0..255){sum+=histogram[v];if(sum>=neutral/2){paper=v;break}}
        val dark=histogram.take(max(0,paper-80)).sum()
        if(paper !in 210..245||dark<max(8,n/500))return null
        val eligible=BooleanArray(n){i->val p=pixels[i];val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255;maxOf(r,g,b)-minOf(r,g,b)<=12&&abs(DocumentImageFilterEngine.pixelLuminance(p)-paper)<=34}
        val count=eligible.count{it};if(count<n*.75)return null
        val length=max(width,height).toDouble()
        // Only the active <=5-column basis is retained, not one plane per bank frequency.
        fun basis(frequencies:List<Frequency>):Array<DoubleArray> {val b=Array(1+2*frequencies.size){DoubleArray(n)};b[0].fill(1.0)
            for((j,f)in frequencies.withIndex()){active();for(i in 0 until n){if(i%256==0)active();val x=(i%cols)*stride;val y=(i/cols)*stride;val angle=2*PI*(f.x*x+f.y*y)/length;b[1+2*j][i]=cos(angle);b[2+2*j][i]=sin(angle)}};return b}
        val values=DoubleArray(n){DocumentImageFilterEngine.pixelLuminance(pixels[it]).toDouble()}
        val selected=mutableListOf<Frequency>();var coefficients=doubleArrayOf(paper.toDouble());var selectedBasis=basis(emptyList())
        repeat(2){
            val residual=DoubleArray(n){i->values[i]-selectedBasis.indices.sumOf{j->selectedBasis[j][i]*coefficients[j]}}
            var best:Frequency?=null;var amplitude=2.99
            for(f in bank)if(f !in selected){active();val b=basis(listOf(f));val c=fit(b,residual,eligible,active)?:continue;val a=hypot(c[1],c[2]);if(a>amplitude){amplitude=a;best=f}}
            if(best!=null){selected+=requireNotNull(best);selectedBasis=basis(selected);coefficients=fit(selectedBasis,values,eligible,active)?:return null}
        }
        if(selected.isEmpty())return null
        var inliers=eligible.copyOf()
        for(limit in listOf(16.0,6.0,4.0)){
            inliers=BooleanArray(n){i->eligible[i]&&abs(values[i]-selectedBasis.indices.sumOf{j->selectedBasis[j][i]*coefficients[j]})<=limit}
            coefficients=fit(selectedBasis,values,inliers,active)?:return null
        }
        if(inliers.count{it}<count*.94)return null
        var residual=0.0;for(i in 0 until n)if(inliers[i])residual+=abs(values[i]-selectedBasis.indices.sumOf{j->selectedBasis[j][i]*coefficients[j]})
        if(residual/inliers.count{it}>.65)return null
        val amplitudes=selected.indices.map{hypot(coefficients[1+it*2],coefficients[2+it*2])}
        if(amplitudes.any{it !in 3.0..24.0}||amplitudes.sum()>32)return null
        // A gray projection must not silently remove only the luminance of color interference.
        for(shift in listOf(0,8,16)){active();val channels=DoubleArray(n){(pixels[it] ushr shift and 255).toDouble()};val c=fit(selectedBasis,channels,inliers,active)?:return null
            for(j in 1 until c.size)if(abs(c[j]-coefficients[j])>1.5)return null}
        return Model(selected.toList(),coefficients,coefficients[0])
    }

    private fun fit(b:Array<DoubleArray>,values:DoubleArray,mask:BooleanArray,active:()->Unit):DoubleArray? {
        val size=b.size;val a=Array(size){DoubleArray(size+1)};var count=0
        for(i in values.indices){if(i%256==0)active();if(!mask[i])continue;count++
            for(j in 0 until size){val v=b[j][i];for(k in 0 until size)a[j][k]+=v*b[k][i];a[j][size]+=v*values[i]}}
        if(count<size*4)return null
        for(j in 0 until size){active();val pivot=(j until size).maxBy{abs(a[it][j])};if(abs(a[pivot][j])<1e-8)return null;val swap=a[j];a[j]=a[pivot];a[pivot]=swap
            val divisor=a[j][j];for(k in j..size)a[j][k]/=divisor
            for(i in 0 until size)if(i!=j){val factor=a[i][j];for(k in j..size)a[i][k]-=factor*a[j][k]}}
        return DoubleArray(size){a[it][size]}
    }
}

internal val DocumentFilter.moireAmount:Float?
    get()=when(this){DocumentFilter.MOIRE_LOW_V1->.35f;DocumentFilter.MOIRE_BALANCED_V1->.65f;DocumentFilter.MOIRE_STRONG_V1->1f;else->null}
