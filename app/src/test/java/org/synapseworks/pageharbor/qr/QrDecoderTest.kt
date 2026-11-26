package org.synapseworks.pageharbor.qr

import javax.imageio.ImageIO
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class QrDecoderTest {
    private fun decode(name:String):QrDecode {
        val bitmap=javaClass.classLoader!!.getResourceAsStream("m19/$name.png").use{ImageIO.read(it)}
        val bytes=ByteArray(bitmap.width*bitmap.height)
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width)bytes[y*bitmap.width+x]=(bitmap.getRGB(x,y) and 255).toByte()
        return QrDecoder().decode(bytes,bitmap.width,bitmap.height)
    }
    @Test fun independentSegnoFixturesDecodeExactPayloadsIncludingUnicodeAndDenseVersion40(){
        for(name in listOf("web","web-90","web-180","web-270","web-inverted","unicode","lines","unsupported","userinfo","wifi","controls","dense","dense-90","dense-180","dense-270")){
            val expected=javaClass.classLoader!!.getResourceAsStream("m19/$name.txt")!!.bufferedReader(Charsets.UTF_8).use{it.readText()}
            val result=decode(name);assertTrue(name,result is QrDecode.Found);assertEquals(expected,(result as QrDecode.Found).payload.text)
        }
    }
    @Test fun unreadableAndOutOfBoundsInputsDoNotProduceResults(){
        assertFalse(decode("blank") is QrDecode.Found);assertFalse(decode("corrupt") is QrDecode.Found)
        assertEquals(QrDecode.Invalid,decode("structured-0"));assertEquals(QrDecode.Invalid,decode("structured-1"))
        assertEquals(QrDecode.Invalid,QrDecoder().decode(ByteArray(1),1281,1))
        assertEquals(QrDecode.Invalid,QrDecoder().decode(ByteArray(1),1280,1280))
        assertEquals(QrDecode.Invalid,QrDecoder().decode(ByteArray(1),1,0))
    }
    @Test fun croppedStridedPlaneHonorsBufferPositionAndRotation(){
        val buffer=ByteBuffer.allocate(50).apply{position(3)}
        for(y in 0..2)for(x in 0..3)buffer.put(3+y*12+x*2,(y*10+x).toByte())
        for((rotation,expected) in listOf(0 to listOf(11,12,21,22),90 to listOf(21,11,22,12),180 to listOf(22,21,12,11),270 to listOf(12,22,11,21))){
            val result=QrLuma.plane(buffer,4,3,12,2,1,1,2,2,rotation)!!
            assertEquals(expected,result.first.map{it.toInt()});assertEquals(3,buffer.position())
        }
        assertNull(QrLuma.plane(buffer,4,3,2,2,0,0,4,3,0))
        assertNull(QrLuma.plane(buffer,4,3,Int.MAX_VALUE,Int.MAX_VALUE,0,0,4,3,0))
        assertNull(QrLuma.plane(buffer,4,3,12,2,2,2,4,3,0))
        assertNull(QrLuma.plane(buffer,4,3,12,2,0,0,4,3,45))
    }
}
