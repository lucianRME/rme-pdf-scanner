package org.synapseworks.pageharbor.photo

/** Reject high-bit-depth PNGs before Android can silently quantize them into this8-bit profile. */
internal fun photoPng8Bit(header:ByteArray):Boolean {
 val signature=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
 return header.size>=29&&signature.indices.all{header[it]==signature[it]}&&
  header.sliceArray(8..11).contentEquals(byteArrayOf(0,0,0,13))&&
  header.sliceArray(12..15).contentEquals(byteArrayOf(73,72,68,82))&&header[24].toInt()==8
}
