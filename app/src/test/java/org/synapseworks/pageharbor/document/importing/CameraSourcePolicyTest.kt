package org.synapseworks.pageharbor.document.importing
import org.junit.Test
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.session.*
class CameraSourcePolicyTest {
 @Test fun phoneSourcesAreDifferentFromWorkingBitmaps(){
  val m=DocumentImageMetadata(268353,4096,3072)
  assertEquals(DocumentImageConstraintViolation.DIMENSIONS_EXCEEDED,DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(m))
  assertNull(DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(m));assertEquals(2,documentWorkingSampleSize(4096,3072))
  assertEquals(2,documentWorkingSampleSize(8000,6000));assertEquals(1,documentWorkingSampleSize(4032,3024))
 }
 @Test fun independentlyBoundBytesPixelsAndEdges(){
  assertNull(documentWorkingSampleSize(20000,10));assertNull(documentWorkingSampleSize(12000,12000))
  assertEquals(DocumentImageConstraintViolation.SOURCE_BYTES_EXCEEDED,DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(DocumentImageMetadata(67108865,100,100)))
  assertNull(DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(DocumentImageMetadata(67108864,10000,10000)))
  assertEquals(4,documentWorkingSampleSize(10000,10000))
 }
 @Test fun customWorkingProfilesAreNotEnlarged(){assertNull(documentWorkingSampleSize(100,100,DocumentInputLimits(maxDecodedPixelCount=100,maxImageDimension=20)))}
 @Test fun oddDimensionsStayWithinTheWorkingBudget(){for((w,h)in listOf(4097 to 3073,10001 to 9999,16384 to 1)){val s=requireNotNull(documentWorkingSampleSize(w,h));val x=(w+s-1)/s;val y=(h+s-1)/s;assertTrue(x<=6000&&y<=6000&&x.toLong()*y<=12500000)}}
}
