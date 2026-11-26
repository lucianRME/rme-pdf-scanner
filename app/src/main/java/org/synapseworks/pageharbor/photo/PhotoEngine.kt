package org.synapseworks.pageharbor.photo

import kotlin.math.*

internal data class PhotoRecipe(val noise:Int=0,val contrast:Int=0,val red:Int=0,val green:Int=0,val blue:Int=0){init{require(noise in 0..2&&contrast in 0..30&&listOf(red,green,blue).all{it in -24..24})}}
internal enum class ScratchDirection{VERTICAL,HORIZONTAL}
internal data class PhotoDab(val x:Float,val y:Float,val erase:Boolean=false,val radius:Int=0)
/** PHOTO_RESTORE_V1. Observed-pixel correction only; never a learned reconstruction. */
internal object PhotoEngine{
 const val MAX_PIXELS=1_048_576
 fun mask(w:Int,h:Int,dabs:List<PhotoDab>,active:()->Unit={}):BooleanArray{
  require(w in 2..1024&&h in 2..1024&&w*h<=MAX_PIXELS&&dabs.size<=4096)
  val out=BooleanArray(w*h);var work=0
  for(d in dabs){active();require(d.x.isFinite()&&d.y.isFinite()&&d.x in 0f..1f&&d.y in 0f..1f&&d.radius in 0..1);val x=(d.x*(w-1)).roundToInt();val y=(d.y*(h-1)).roundToInt()
   for(yy in max(0,y-d.radius)..min(h-1,y+d.radius))for(xx in max(0,x-d.radius)..min(w-1,x+d.radius)){require(++work<=200000);out[yy*w+xx]=!d.erase}}
  require(out.count{it}<=min(10000,w*h/100));return out
 }
 fun process(w:Int,h:Int,input:IntArray,r:PhotoRecipe,mask:BooleanArray=BooleanArray(w*h),direction:ScratchDirection=ScratchDirection.VERTICAL,active:()->Unit={}):IntArray{
  require(w in 2..1024&&h in 2..1024&&w*h<=MAX_PIXELS&&input.size==w*h&&mask.size==input.size)
  require(mask.count{it}<=min(10000,w*h/100));val shifts=intArrayOf(16,8,0);val offsets=intArrayOf(r.red,r.green,r.blue)
  var out=IntArray(input.size)
  for(y in 0 until h){active();for(x in 0 until w){val i=y*w+x;val p=input[i];require(p ushr 24==255);var q=-0x1000000
   for(c in 0..2){val v=((p ushr shifts[c])and 255)+offsets[c];val n=(128+(v-128)*(100+r.contrast)/100.0).roundToInt().coerceIn(0,255);q=q or(n shl shifts[c])};out[i]=q}}
  repeat(r.noise){val previous=out;out=previous.copyOf();for(y in 0 until h){active();val sums=IntArray(3);for(x in 0 until w){val p=previous[y*w+x];for(c in 0..2)sums[c]=((p ushr shifts[c])and 255)*4;var n=4
   for(yy in max(0,y-1)..min(h-1,y+1))for(xx in max(0,x-1)..min(w-1,x+1)){if(xx==x&&yy==y)continue;val q=previous[yy*w+xx];if(shifts.all{abs(((q ushr it)and 255)-((p ushr it)and 255))<=28}){n++;for(c in 0..2)sums[c]+=(q ushr shifts[c])and 255}}
   out[y*w+x]=-0x1000000 or((sums[0]+n/2)/n shl 16)or((sums[1]+n/2)/n shl 8)or((sums[2]+n/2)/n)}}}
  if(mask.any{it}){val v=direction==ScratchDirection.VERTICAL;val rows=if(v)h else w;val cols=if(v)w else h
   fun at(row:Int,col:Int)=if(v)row*w+col else col*w+row
   // Preflight all observed boundaries before producing any repaired output.
   val runs=ArrayList<IntArray>()
   for(row in 0 until rows){active();var c=0;while(c<cols){if(!mask[at(row,c)]){c++;continue};val start=c;while(c<cols&&mask[at(row,c)])c++;val end=c
    require(end-start in 1..3&&start>=2&&end+1<cols){"SCRATCH_UNSUPPORTED"};val a=out[at(row,start-1)];val b=out[at(row,end)];val aa=out[at(row,start-2)];val bb=out[at(row,end+1)]
    for(s in shifts){val av=(a ushr s)and 255;val bv=(b ushr s)and 255;require(abs(av-bv)<=24&&abs(av-((aa ushr s)and 255))<=12&&abs(bv-((bb ushr s)and 255))<=12){"SCRATCH_UNCERTAIN"}}
    runs.add(intArrayOf(row,start,end))}}
   for(run in runs){active();val row=run[0];val start=run[1];val end=run[2];val a=out[at(row,start-1)];val b=out[at(row,end)];for(c in start until end){var p=-0x1000000;for(s in shifts){val av=(a ushr s)and 255;val bv=(b ushr s)and 255;val value=(av+(bv-av)*(c-start+1).toDouble()/(end-start+1)).roundToInt();p=p or(value shl s)};out[at(row,c)]=p}}
  };active();return out
 }
}
