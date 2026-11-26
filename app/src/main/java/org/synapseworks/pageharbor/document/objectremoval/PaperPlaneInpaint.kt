package org.synapseworks.pageharbor.document.objectremoval

import kotlin.math.*

internal enum class RemovalStatus { READY, EMPTY, UNCHANGED, UNSUPPORTED }
/** Controlled PAPER_PLANE_INPAINT_V1. Fits observed boundary pixels, never hidden letters.
 * All admission/fit checks finish before output rows begin. Caller discards partial output on
 * cancellation/failure. The source and every unselected pixel are immutable.
 */
internal object PaperPlaneInpaint {
    const val VERSION="PAPER_PLANE_INPAINT_V1"
    fun process(width:Int,height:Int,mask:InpaintMask,read:(Int,IntArray)->Unit,write:(Int,IntArray)->Unit,active:()->Unit={}):RemovalStatus {
        require(width in 2..6000&&height in 2..6000&&width.toLong()*height<=12_000_000)
        require(InpaintMask.dimensions(width,height)==(mask.width to mask.height))
        val w=mask.width;val h=mask.height;var selected=0;var left=w;var right=0;var top=h;var bottom=0
        for(i in 0 until mask.size){if(i%256==0)active();if(mask[i]){selected++;left=min(left,i%w);right=max(right,i%w);top=min(top,i/w);bottom=max(bottom,i/w)}}
        if(selected==0)return RemovalStatus.EMPTY
        if(selected>mask.size*.05)return RemovalStatus.UNSUPPORTED
        val ring=BooleanArray(mask.size)
        for(i in 0 until mask.size)if(mask[i]){if(i%256==0)active();val x=i%w;val y=i/w
            for(sy in max(0,y-2)..min(h-1,y+2))for(sx in max(0,x-2)..min(w-1,x+2))if(!mask[sy*w+sx])ring[sy*w+sx]=true}
        if(ring.count{it}>mask.size*.15)return RemovalStatus.UNSUPPORTED
        val cx=(left+right+1)/2.0;val cy=(top+bottom+1)/2.0;val scale=max(2,max(right-left+1,bottom-top+1)).toDouble()
        val basis=DoubleArray(3)
        val normal=Array(3){DoubleArray(6)};val row=IntArray(width);var count=0L;var opaque=true
        fun cell(x:Int,y:Int)=(y.toLong()*h/height).toInt()*w+(x.toLong()*w/width).toInt()
        for(y in 0 until height){active();read(y,row);for(x in 0 until width){if(x%256==0)active();if(!ring[cell(x,y)])continue
            val p=row[x];if(p ushr 24!=255)opaque=false;count++;val v=basis;v[0]=1.0;v[1]=((x+.5)*w/width-cx)/scale;v[2]=((y+.5)*h/height-cy)/scale
            for(j in 0..2){for(k in 0..2)normal[j][k]+=v[j]*v[k];for(c in 0..2)normal[j][3+c]+=v[j]*(p ushr (16-c*8)and 255)}
        }}
        if(!opaque||count<12)return RemovalStatus.UNSUPPORTED
        for(j in 0..2){active();val pivot=(j..2).maxBy{abs(normal[it][j])};if(abs(normal[pivot][j])<1e-8)return RemovalStatus.UNSUPPORTED
            val swap=normal[j];normal[j]=normal[pivot];normal[pivot]=swap;val d=normal[j][j];for(k in j..5)normal[j][k]/=d
            for(i in 0..2)if(i!=j){val f=normal[i][j];for(k in j..5)normal[i][k]-=f*normal[j][k]}}
        fun predict(x:Int,y:Int,c:Int):Double=normal[0][3+c]+normal[1][3+c]*((x+.5)*w/width-cx)/scale+normal[2][3+c]*((y+.5)*h/height-cy)/scale
        val errors=DoubleArray(3);val minima=DoubleArray(3){Double.POSITIVE_INFINITY};val maxima=DoubleArray(3){Double.NEGATIVE_INFINITY}
        var valid=true
        for(y in 0 until height){active();read(y,row);for(x in 0 until width){if(x%256==0)active();val index=cell(x,y);if(!mask[index]&&!ring[index])continue
            for(c in 0..2){val v=predict(x,y,c);if(!v.isFinite())valid=false
                if(mask[index]){minima[c]=min(minima[c],v);maxima[c]=max(maxima[c],v);if(v<160||v>255)valid=false}
                if(ring[index]){val e=abs(v-(row[x] ushr(16-c*8)and 255));errors[c]+=e;if(e>4)valid=false}}
        }}
        if(!valid||(0..2).any{errors[it]/count>1||maxima[it]-minima[it]>32})return RemovalStatus.UNSUPPORTED
        var changed=false
        for(y in 0 until height){active();read(y,row);for(x in 0 until width){if(x%256==0)active();if(!mask[cell(x,y)])continue
            val p=row[x];var q=-0x1000000;for(c in 0..2)q=q or(predict(x,y,c).roundToInt().coerceIn(0,255) shl(16-c*8))
            if(p!=q)changed=true;row[x]=q
        };write(y,row)}
        active();return if(changed)RemovalStatus.READY else RemovalStatus.UNCHANGED
    }
}
