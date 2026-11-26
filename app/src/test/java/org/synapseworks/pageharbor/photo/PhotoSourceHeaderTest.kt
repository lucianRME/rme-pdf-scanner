package org.synapseworks.pageharbor.photo
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class PhotoSourceHeaderTest{
 @Test fun sourcePrecision(){val ordinary=File("src/m36Test/assets/m39/neutral-input.png").readBytes().take(29).toByteArray();val high=File("src/m36Test/assets/m39-16bit-control.png").readBytes().take(29).toByteArray();assertTrue(photoPng8Bit(ordinary));assertFalse(photoPng8Bit(high));assertFalse(photoPng8Bit(ordinary.copyOfRange(0,24)))}
 @Test fun identityAndHeader(){val h=File("src/m36Test/assets/m39/neutral-input.png").readBytes().take(29).toByteArray();h[0]=0;assertFalse(photoPng8Bit(h));h[0]=137.toByte();h[12]=0;assertFalse(photoPng8Bit(h));h[12]=73;h[24]=4;assertFalse(photoPng8Bit(h))}
}
