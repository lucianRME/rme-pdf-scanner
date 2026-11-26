package org.synapseworks.pageharbor.photo
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
class PhotoEngineTest{
 private fun data(name:String,suffix:String)=File("src/m36Test/assets/m39/$name-$suffix").readBytes()
 private fun pixels(name:String,suffix:String):IntArray{val b=ByteBuffer.wrap(data(name,"$suffix.argb"));return IntArray(b.remaining()/4){b.int}}
 private fun mae(a:IntArray,b:IntArray,select:(Int)->Boolean={true}):Double{var n=0L;var d=0L;for(i in a.indices)if(select(i)){for(s in intArrayOf(16,8,0)){d+=abs(((a[i] ushr s)and 255)-((b[i] ushr s)and 255));n++}};return d.toDouble()/n}
 @Test fun neutral(){val source=pixels("neutral","input");val reference=pixels("neutral","reference");val mask=data("neutral","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,0,0,0)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 neutral before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL))
 assertArrayEquals(source,out)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun clean_noise(){val source=pixels("clean-noise","input");val reference=pixels("clean-noise","reference");val mask=data("clean-noise","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(1,0,0,0,0)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 clean-noise before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue("clean preservation",after<=1.0)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun noise(){val source=pixels("noise","input");val reference=pixels("noise","reference");val mask=data("noise","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(1,0,0,0,0)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 noise before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue("noise reduction",1-after/before>=.25)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun fade(){val source=pixels("fade","input");val reference=pixels("fade","reference");val mask=data("fade","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,25,0,0,0)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 fade before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue(after<=2&&1-after/before>=.75)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun cast(){val source=pixels("cast","input");val reference=pixels("cast","reference");val mask=data("cast","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,-12,6,-10)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 cast before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue(after<=.05)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun combo(){val source=pixels("combo","input");val reference=pixels("combo","reference");val mask=data("combo","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(1,25,-12,6,-10)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 combo before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue("combined reduction",1-after/before>=.5)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun scratch(){val source=pixels("scratch","input");val reference=pixels("scratch","reference");val mask=data("scratch","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,0,0,0)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 scratch before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue(mae(out,reference){mask[it]}<=2);assertTrue(1-mae(out,reference){mask[it]}/mae(source,reference){mask[it]}>=.9);for(i in out.indices)if(!mask[i])assertEquals(source[i],out[i])
 }
 @Test fun scratch_horizontal(){val source=pixels("scratch-horizontal","input");val reference=pixels("scratch-horizontal","reference");val mask=data("scratch-horizontal","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,0,0,0)
 val out=PhotoEngine.process(128,96,source,r,mask,ScratchDirection.HORIZONTAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 scratch-horizontal before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(128,96,source,r,mask,ScratchDirection.HORIZONTAL))
 assertTrue(mae(out,reference){mask[it]}<=2);assertTrue(1-mae(out,reference){mask[it]}/mae(source,reference){mask[it]}>=.9);for(i in out.indices)if(!mask[i])assertEquals(source[i],out[i])
 }
 @Test fun scratch_edge(){val source=pixels("scratch-edge","input");val reference=pixels("scratch-edge","reference");val mask=data("scratch-edge","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,0,0,0)
 try{PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);fail("Expected refusal")}catch(_:IllegalArgumentException){}
 }
 @Test fun scratch_wide(){val source=pixels("scratch-wide","input");val reference=pixels("scratch-wide","reference");val mask=data("scratch-wide","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,0,0,0)
 try{PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);fail("Expected refusal")}catch(_:IllegalArgumentException){}
 }
 @Test fun scratch_ambiguous(){val source=pixels("scratch-ambiguous","input");val reference=pixels("scratch-ambiguous","reference");val mask=data("scratch-ambiguous","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,0,0,0)
 try{PhotoEngine.process(128,96,source,r,mask,ScratchDirection.VERTICAL);fail("Expected refusal")}catch(_:IllegalArgumentException){}
 }
 @Test fun held_noise(){val source=pixels("held-noise","input");val reference=pixels("held-noise","reference");val mask=data("held-noise","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(1,0,0,0,0)
 val out=PhotoEngine.process(173,117,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 held-noise before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(173,117,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue("noise reduction",1-after/before>=.25)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun held_combo(){val source=pixels("held-combo","input");val reference=pixels("held-combo","reference");val mask=data("held-combo","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(1,25,-12,6,-10)
 val out=PhotoEngine.process(173,117,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 held-combo before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(173,117,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue("combined reduction",1-after/before>=.5)
 var union=0;var intersection=0
 for(i in out.indices){val a=(reference[i] ushr 16 and 255)<64&&(reference[i] ushr 8 and 255)<64&&(reference[i] and 255)<64;val b=(out[i] ushr 16 and 255)<64&&(out[i] ushr 8 and 255)<64&&(out[i] and 255)<64;if(a||b)union++;if(a&&b)intersection++};if(union>0)assertTrue("dark text IoU ${intersection.toDouble()/union}",intersection.toDouble()/union>=.98)
 }
 @Test fun held_scratch(){val source=pixels("held-scratch","input");val reference=pixels("held-scratch","reference");val mask=data("held-scratch","mask.bin").map{it.toInt()!=0}.toBooleanArray();val r=PhotoRecipe(0,0,0,0,0)
 val out=PhotoEngine.process(173,117,source,r,mask,ScratchDirection.VERTICAL);val before=mae(source,reference);val after=mae(out,reference);println("M39 held-scratch before=$before after=$after reduction=${1-after/before}")
 assertArrayEquals(out,PhotoEngine.process(173,117,source,r,mask,ScratchDirection.VERTICAL))
 assertTrue(mae(out,reference){mask[it]}<=2);assertTrue(1-mae(out,reference){mask[it]}/mae(source,reference){mask[it]}>=.9);for(i in out.indices)if(!mask[i])assertEquals(source[i],out[i])
 }
 @Test fun cancellationAndBounds(){val p=IntArray(128*96){-1};var calls=0;try{PhotoEngine.process(128,96,p,PhotoRecipe(noise=2)){if(++calls==5)throw java.util.concurrent.CancellationException()};fail()}catch(_:java.util.concurrent.CancellationException){};assertEquals(5,calls);try{PhotoEngine.process(1025,2,IntArray(2050),PhotoRecipe());fail()}catch(_:IllegalArgumentException){} }
 @Test fun maskLimitsAndErase(){val d=PhotoDab(.5f,.5f);val a=PhotoEngine.mask(128,96,listOf(d));assertEquals(1,a.count{it});val b=PhotoEngine.mask(128,96,listOf(d,d.copy(erase=true)));assertFalse(b.any{it});try{PhotoEngine.mask(128,96,List(4097){d});fail()}catch(_:IllegalArgumentException){}}
}
