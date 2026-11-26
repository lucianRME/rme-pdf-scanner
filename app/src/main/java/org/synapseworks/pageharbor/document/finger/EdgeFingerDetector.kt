package org.synapseworks.pageharbor.document.finger

import kotlin.math.*
import org.synapseworks.pageharbor.document.objectremoval.InpaintMask

internal enum class FingerDecision { PROPOSED, NONE, UNCERTAIN, UNSUPPORTED }
internal data class FingerDetection(val decision:FingerDecision,val mask:InpaintMask,val proposals:Int=0)
/** EDGE_CHROMA_COMPONENT_V1: bounded candidate suggestions, never semantic identity or consent.
 * No truth masks, OCR, learned model, content logging or filesystem writes. Always review.
 */
internal object EdgeFingerDetector {
 const val VERSION="EDGE_CHROMA_COMPONENT_V1"
 fun detect(width:Int,height:Int,read:(Int,IntArray)->Unit,shapeCellLimit:Int=4_000_000,active:()->Unit={}):FingerDetection {
  require(width in 2..6000&&height in 2..6000&&width.toLong()*height<=12_000_000)
  require(shapeCellLimit in 1..4_000_000)
  var remainingShapeCells=shapeCellLimit.toLong()
  val (w,h)=InpaintMask.dimensions(width,height);val n=w*h
  fun empty(d:FingerDecision)=FingerDetection(d,InpaintMask(w,h,BooleanArray(n)))
  if(max(width,height)<256||min(width,height)<128)return empty(FingerDecision.UNSUPPORTED)
  val pixels=IntArray(n);val hist=Array(3){IntArray(256)};val row=IntArray(width);var opaque=true
  for(y in 0 until h){active();read(((y+.5)*height/h).toInt().coerceAtMost(height-1),row)
   for(x in 0 until w){if(x%256==0)active();val p=row[((x+.5)*width/w).toInt().coerceAtMost(width-1)];pixels[y*w+x]=p;if(p ushr 24!=255)opaque=false;for(c in 0..2)hist[c][p ushr(16-c*8)and 255]++}}
  if(!opaque)return empty(FingerDecision.UNSUPPORTED)
  val paper=IntArray(3){c->var total=0;var value=0;while(value<255){total+=hist[c][value];if(total>=n*.85)break;value++};value}
  if(paper.min()<180||paper.max()-paper.min()>48)return empty(FingerDecision.UNSUPPORTED)
  val foreground=BooleanArray(n);val labels=IntArray(n);val queue=IntArray(n);val result=BooleanArray(n)
  fun warm(p:Int):Boolean{val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255;return r>=g+6&&g>=b+3&&r-b>=15&&r<=g*2.6+12&&g<=b*2.6+12&&r-b<170}
  val paperL=(paper[0]*77+paper[1]*150+paper[2]*29)/256
  for(i in 0 until n){if(i%256==0)active();val p=pixels[i];val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
   foreground[i]=maxOf(abs(r-paper[0]),abs(g-paper[1]),abs(b-paper[2]))>=24&&(r*77+g*150+b*29)/256<=paperL-18}
  var components=0;var proposals=0;var uncertain=false
  for(seed in 0 until n){if(seed%256==0)active();if(!foreground[seed]||labels[seed]!=0)continue
   if(++components>8192)return empty(FingerDecision.UNSUPPORTED)
   var head=0;var tail=1;queue[0]=seed;labels[seed]=components
   var minX=w;var minY=h;var maxX=0;var maxY=0;var skin=0
   while(head<tail){if(head%256==0)active();val i=queue[head++];val x=i%w;val y=i/w;minX=min(minX,x);maxX=max(maxX,x);minY=min(minY,y);maxY=max(maxY,y);if(warm(pixels[i]))skin++
    fun add(j:Int){if(foreground[j]&&labels[j]==0){labels[j]=components;queue[tail++]=j}}
    if(x>0)add(i-1);if(x+1<w)add(i+1);if(y>0)add(i-w);if(y+1<h)add(i+w)
   }
   val touches=listOf(minX==0,maxX==w-1,minY==0,maxY==h-1);val edge=touches.indexOf(true)
   if(skin<tail*.65||tail<12)continue
   if(touches.count{it}!=1){if(minOf(minX,w-1-maxX,minY,h-1-maxY)<=max(w,h)*.03)uncertain=true;continue}
   uncertain=true
   if(tail<n*.0008||tail>n*.045)continue
   val normal=if(edge<2)w else h;val along=if(edge<2)h else w
   val bw=maxX-minX+1;val bh=maxY-minY+1;val depth=if(edge<2)bw else bh
   if(depth>normal*.22||bw.toLong()*bh>n*.08)continue
   remainingShapeCells-=bw.toLong()*bh
   if(remainingShapeCells<0)return empty(FingerDecision.UNSUPPORTED)
   // Fill only enclosed holes in this component's bounded box (e.g. a nail).
   val outside=BooleanArray(bw*bh);head=0;var end=0
   fun localIndex(x:Int,y:Int)=y*bw+x
   fun inside(x:Int,y:Int)=labels[(minY+y)*w+minX+x]==components
   fun border(x:Int,y:Int){val j=localIndex(x,y);if(!inside(x,y)&&!outside[j]){outside[j]=true;queue[end++]=j}}
   for(x in 0 until bw){border(x,0);border(x,bh-1)};for(y in 0 until bh){border(0,y);border(bw-1,y)}
   while(head<end){if(head%256==0)active();val j=queue[head++];val x=j%bw;val y=j/bw
    if(x>0)border(x-1,y);if(x+1<bw)border(x+1,y);if(y>0)border(x,y-1);if(y+1<bh)border(x,y+1)}
   fun selected(x:Int,y:Int)=!outside[y*bw+x]
   val spans=IntArray(depth);var filled=0;var continuous=true
   for(u in 0 until depth){active();var first=Int.MAX_VALUE;var last=-1;var count=0
    val cross=if(edge<2)bh else bw
    for(v in 0 until cross){val x=when(edge){0->u;1->bw-1-u;else->v};val y=when(edge){2->u;3->bh-1-u;else->v}
     if(selected(x,y)){first=min(first,v);last=max(last,v);count++}}
    spans[u]=count;filled+=count;if(count==0||count!=last-first+1)continuous=false
   }
   val span=spans.maxOrNull()?:0;val tip=max(1,(depth*.1).roundToInt());val root=max(1,(depth*.15).roundToInt())
   if(!continuous||filled>tail*1.25||span<12||span<along*.025||span>along*.16||depth<span*.7||depth>span*3.5||filled<depth*span*.65||spans.take(root).average()<span*.70||spans.takeLast(tip).average()>span*.65)continue
   if(++proposals>3)return empty(FingerDecision.UNSUPPORTED)
   for(y in 0 until bh){active();for(x in 0 until bw)if(selected(x,y))for(dy in -1..1)for(dx in -1..1){val sx=minX+x+dx;val sy=minY+y+dy;if(sx in 0 until w&&sy in 0 until h)result[sy*w+sx]=true}}
  }
  active();if(result.count{it}>n*.05)return empty(FingerDecision.UNSUPPORTED)
  return if(proposals>0)FingerDetection(FingerDecision.PROPOSED,InpaintMask(w,h,result),proposals)else empty(if(uncertain)FingerDecision.UNCERTAIN else FingerDecision.NONE)
 }
}
