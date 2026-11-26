package org.synapseworks.pageharbor.backup.format

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPaperSize
import org.synapseworks.pageharbor.library.duplicate.*

class CollageBackupTest {
 @Test fun physicalPagePairIsCanonicalAndMissingLegacyFieldRemainsEmpty(){
  val p=BackupFormatTestFixture().pages.first().copy(paperSize=DocumentPaperSize(612f,792f).encode(),width=1530,height=1980)
  assertEquals("44190000:44460000",p.paperSize)
  assertEquals(p,BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(p),BackupFormatLimits()))
  val json=BackupJsonCodec.encodePage(p.copy(paperSize=""))
  assertEquals("",BackupJsonCodec.decodePage(json.replace(",\"paperSize\":\"\"",""),BackupFormatLimits()).paperSize)
  for(value in listOf("null","5","true"))try{BackupJsonCodec.decodePage(json.replace("\"paperSize\":\"\"","\"paperSize\":"+value),BackupFormatLimits());fail()}catch(_:BackupFormatException){}
 }
 @Test fun formatSevenAndPixelAspectRequiredAndReleasedReaderRejectsWhole(){
  val f=BackupFormatTestFixture();val modern=f.manifest.copy(formatVersion=7,minimumReaderVersion=7,ocr=BackupOcrManifest.empty())
  val pages=f.pages.map{it.copy(paperSize=DocumentPaperSize(612f,792f).encode(),width=1530,height=1980)}
  val docs=f.documents.map{it.copy(contentHashVersion=4)}
  fun validate(v:Int,p:List<BackupPageRecord>)=BackupFormatValidator.validateCompleteBackup(modern.copy(formatVersion=v,minimumReaderVersion=v),f.folders,docs,p,f.sourceAssets)
  validate(7,pages)
  for((v,p)in listOf(6 to pages,7 to pages.map{it.copy(width=1980)},7 to pages.map{it.copy(paperSize="7fc00000:44460000")}))try{validate(v,p);fail()}catch(_:BackupFormatException){}
  try{M15BackupFormatValidator.validateCompatibility(modern);fail()}catch(e:BackupFormatException){assertEquals(BackupFormatFailure.UNSUPPORTED_VERSION,e.failure)}
 }
 @Test fun physicalFingerprintChangesWithPaperButKeepsLegacyVersionAndBytes(){
  val old=FingerprintPage("00".repeat(32),"image/jpeg",123,0,"ORIGINAL")
  val legacy=DocumentFingerprintV1.calculate(listOf(old));assertEquals(1,legacy.version)
  val a=DocumentFingerprintV1.calculate(listOf(old.copy(paperSize="44190000:44460000")))
  val b=DocumentFingerprintV1.calculate(listOf(old.copy(paperSize=DocumentPaperSize(306f,396f).encode())))
  assertEquals(4,a.version);assertNotEquals(a.sha256,b.sha256);assertNotEquals(a.sha256,legacy.sha256)
  assertEquals(legacy,DocumentFingerprintV1.calculate(listOf(old.copy(paperSize=""))))
  try{DocumentFingerprintV1.calculate(listOf(old.copy(paperSize="broken")));fail()}catch(_:IllegalArgumentException){}
 }
}
