package org.synapseworks.pageharbor.document.collage

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize
import org.synapseworks.pageharbor.document.session.DocumentPaperSize

class CollageGeometryTest {
 private val settings=CollageSettings(6,CardCopyPageSize.LETTER,marginMm=12.7f,gapMm=6.35f)
 private fun tiles(n:Int)=List(n){CollageTile("tile-$it",(it%2).toLong())}
 @Test fun literalSixPortraitGridRetainsRepeatsOddTailAndWhiteSlots(){
  val pages=CollageGeometry.plan(CollageDraft(tiles(7),settings),mapOf(0L to CollageSize(400,200),1L to CollageSize(200,400)))
  assertEquals(2,pages.size);assertEquals(6,pages.first().placements.size);assertEquals(1,pages.last().placements.size)
  val expected=listOf(CollageRect(36f,36f,297f,264f),CollageRect(315f,36f,576f,264f),CollageRect(36f,282f,297f,510f),CollageRect(315f,282f,576f,510f),CollageRect(36f,528f,297f,756f),CollageRect(315f,528f,576f,756f))
  expected.zip(pages.first().placements).forEach{(a,b)->rect(a,b.cell)}
  assertEquals(listOf(0L,1L,0L,1L,0L,1L),pages.first().placements.map{it.tile.pageId})
  rect(expected.first(),pages.last().placements.single().cell);assertEquals("tile-6",pages.last().placements.single().tile.id)
  assertEquals(1530 to 1980,pages.first().pixels())
 }
 @Test fun literalTwoFourAndLandscapeSixDimensions(){
  val sizes=mapOf(0L to CollageSize(200,200),1L to CollageSize(200,200))
  val two=CollageGeometry.plan(CollageDraft(tiles(2),settings.copy(slots=2)),sizes).single()
  rect(CollageRect(36f,36f,576f,387f),two.placements[0].cell);rect(CollageRect(36f,405f,576f,756f),two.placements[1].cell)
  val four=CollageGeometry.plan(CollageDraft(tiles(4),settings.copy(slots=4)),sizes).single();rect(CollageRect(315f,405f,576f,756f),four.placements[3].cell)
  val six=CollageGeometry.plan(CollageDraft(tiles(6),settings.copy(landscape=true)),sizes).single();rect(CollageRect(36f,36f,264f,297f),six.placements[0].cell);rect(CollageRect(528f,315f,756f,576f),six.placements[5].cell)
 }
 @Test fun independentFitCropAnchorAndQuarterTurnExpectations(){
  fun placement(tile:CollageTile)=CollageGeometry.plan(CollageDraft(listOf(tile),CollageSettings(4,CardCopyPageSize.LETTER,marginMm=0f,gapMm=0f)),mapOf(0L to CollageSize(400,100))).single().placements.single()
  // Cell306x396; wide image fits306x76.5 centered at y159.75.
  rect(CollageRect(0f,159.75f,306f,236.25f),placement(CollageTile("a",0)).image)
  rect(CollageRect(-639f,0f,945f,396f),placement(CollageTile("a",0,CollageFit.CROP)).image)
  rect(CollageRect(-1278f,0f,306f,396f),placement(CollageTile("a",0,CollageFit.CROP,x=1f)).image)
  rect(CollageRect(103.5f,0f,202.5f,396f),placement(CollageTile("a",0,turns=1)).image)
 }
 @Test fun malformedLimitsAndMutableInputsCannotChangeFrozenDraft(){
  val list=tiles(4).toMutableList();val draft=CollageDraft(list);list.clear();assertEquals(4,draft.tiles.size)
  for(block in listOf<()->Unit>({CollageDraft(tiles(25))},{CollageDraft(listOf(CollageTile("same",0),CollageTile("same",1)))},{CollageSettings(slots=3)},{CollageSettings(marginMm=Float.NaN)},{CollageTile("x",0,x=Float.NaN)},{CollageSize(6000,6000)}))try{block();fail()}catch(_:IllegalArgumentException){}
 }
 @Test fun physicalMetadataCodecIsExactBoundedAndRejectsMalformedPairs(){
  val a=DocumentPaperSize(595.2756f,841.8898f);assertEquals(a,DocumentPaperSize.decode(a.encode()));assertNull(DocumentPaperSize.decode(""));assertEquals(DocumentPaperSize(841.8898f,595.2756f),a.rotated(90))
  for(text in listOf("invalid","7fc00000:3f800000","00000000:3f800000","3f800000:7f800000"))try{DocumentPaperSize.decode(text);fail()}catch(_:IllegalArgumentException){}
 }
 private fun rect(a:CollageRect,b:CollageRect){assertEquals(a.left,b.left,.002f);assertEquals(a.top,b.top,.002f);assertEquals(a.right,b.right,.002f);assertEquals(a.bottom,b.bottom,.002f)}
}
