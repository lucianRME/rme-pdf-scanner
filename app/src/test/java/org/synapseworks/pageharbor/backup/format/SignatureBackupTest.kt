package org.synapseworks.pageharbor.backup.format

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*

class SignatureBackupTest {
 @Test fun groupedInkRequiresVersionSixAndOldReaderRejectsBeforeMetadata(){
  val f=BackupFormatTestFixture()
  val ink=HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.2f,.3f)))))
  val layer=AnnotationLayer.of(listOf(AnnotationMark.create("synthetic",AnnotationTool.SIGNATURE,AnnotationBounds(.1f,.2f,.8f,.6f),signature=ink)))
  val pages=f.pages.map{it.copy(annotations=layer.encode())}
  val manifest=f.manifest.copy(formatVersion=6,minimumReaderVersion=6,ocr=BackupOcrManifest.empty())
  BackupFormatValidator.validateCompleteBackup(manifest,f.folders,f.documents,pages,f.sourceAssets)
  assertFalse(M14BackupCompatibility.accepts(manifest))
  try{BackupFormatValidator.validateCompleteBackup(manifest.copy(formatVersion=5,minimumReaderVersion=5),f.folders,f.documents,pages,f.sourceAssets);fail()}catch(_:BackupFormatException){}
  assertEquals(layer,AnnotationLayer.decode(BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(pages.first()),BackupFormatLimits()).annotations))
 }
}
