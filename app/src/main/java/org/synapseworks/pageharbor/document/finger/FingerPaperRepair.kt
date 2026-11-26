package org.synapseworks.pageharbor.document.finger

import org.synapseworks.pageharbor.document.objectremoval.*

/** EDGE_PAPER_GUARD_V1. Original M28 admission plus its admission on a two-cell larger mask.
 * The larger mask is only a guard; no pixels are written for it. Union of both support rings
 * catches nearby ink missed by one sampled footprint. Actual output uses the original mask.
 * At most nine full row passes, one source row per pass; every pass remains cancellable.
 */
internal object FingerPaperRepair {
 const val VERSION="EDGE_PAPER_GUARD_V1"
 fun process(width:Int,height:Int,mask:InpaintMask,read:(Int,IntArray)->Unit,write:(Int,IntArray)->Unit,active:()->Unit={}):RemovalStatus {
  val original=PaperPlaneInpaint.process(width,height,mask,read,{_,_->},active)
  if(original!=RemovalStatus.READY)return original
  val w=mask.width;val h=mask.height;val cells=mask.copyCells();val expanded=cells.copyOf()
  for(i in cells.indices){if(i%256==0)active();if(cells[i]){val x=i%w;val y=i/w
   for(sy in maxOf(0,y-2)..minOf(h-1,y+2))for(sx in maxOf(0,x-2)..minOf(w-1,x+2))expanded[sy*w+sx]=true}}
  val guard=PaperPlaneInpaint.process(width,height,InpaintMask(w,h,expanded),read,{_,_->},active)
  if(guard==RemovalStatus.UNSUPPORTED||guard==RemovalStatus.EMPTY)return RemovalStatus.UNSUPPORTED
  active();return PaperPlaneInpaint.process(width,height,mask,read,write,active)
 }
}
