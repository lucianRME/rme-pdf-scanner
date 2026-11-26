package org.synapseworks.pageharbor.backup.format

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.annotation.*

class AnnotationBackupTest {
 private val layer=AnnotationLayer.of(listOf(AnnotationMark.create("mark",AnnotationTool.TEXT,AnnotationBounds(.1f,.1f,.9f,.9f),text="Café\nNotes")))
 @Test fun editableLayerRoundTripsAndLegacyDefaultsToEmpty() {
  val f=BackupFormatTestFixture();val page=f.pages.first().copy(annotations=layer.encode())
  assertEquals(page,BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(page),BackupFormatLimits()))
  assertEquals(layer,AnnotationLayer.decode(page.annotations))
  assertEquals("",BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(f.pages.first()).replace(",\"annotations\":\"\"",""),BackupFormatLimits()).annotations)
 }
 @Test fun unsupportedReadersAndMalformedLayerAreRejectedWhole() {
  val f=BackupFormatTestFixture()
  fun validate(version:Int,annotations:String)=BackupFormatValidator.validateCompleteBackup(f.manifest.copy(formatVersion=version,minimumReaderVersion=version,ocr=BackupOcrManifest.empty()),f.folders,f.documents,f.pages.map{it.copy(annotations=annotations)},f.sourceAssets)
  validate(5,layer.encode())
  for((v,a) in listOf(4 to layer.encode(),5 to "invalid"))try{validate(v,a);fail("Invalid layer accepted")}catch(_:BackupFormatException){}
  val json=BackupJsonCodec.encodePage(f.pages.first())
  for(value in listOf("null","12"))try{BackupJsonCodec.decodePage(json.replace("\"annotations\":\"\"","\"annotations\":"+value),BackupFormatLimits());fail("Malformed layer type accepted")}catch(_:BackupFormatException){}
  val modern=f.manifest.copy(formatVersion=5,minimumReaderVersion=5,ocr=BackupOcrManifest.empty())
  try { M08BackupFormatValidator.validateCompatibility(modern);fail("Released format4 boundary accepted format5") }catch(e:BackupFormatException){assertEquals(BackupFormatFailure.UNSUPPORTED_VERSION,e.failure)}
  assertEquals(8,RME_BACKUP_READER_VERSION)
 }
}
