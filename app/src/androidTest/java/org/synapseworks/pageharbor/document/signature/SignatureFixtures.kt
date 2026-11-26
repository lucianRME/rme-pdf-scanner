package org.synapseworks.pageharbor.document.signature

import org.synapseworks.pageharbor.document.annotation.*

internal object SignatureFixtures {
 fun ink()=HandwrittenSignature(listOf(
  SignatureStroke(listOf(SignaturePoint(.1f,.2f),SignaturePoint(.1f,.8f),SignaturePoint(.9f,.8f))),
  SignatureStroke(listOf(SignaturePoint(.85f,.2f)))),strokeWidthFraction=.04f,aspectRatio=2f)
 fun mark()=AnnotationMark.create("synthetic-signature",AnnotationTool.SIGNATURE,AnnotationBounds(.1f,.2f,.7f,.6f),AnnotationStyle(rgb=0),signature=ink())
 fun layer()=AnnotationLayer.of(listOf(mark())+AnnotationFixtures.layer().marks)
}
