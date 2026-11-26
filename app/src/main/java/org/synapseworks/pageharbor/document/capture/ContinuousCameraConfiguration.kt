package org.synapseworks.pageharbor.document.capture

import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/** Shared production/optimized-harness configuration; reject an unsupported bounded stream. */
internal object ContinuousCameraConfiguration {
    private fun resolution(width:Int,height:Int,maxEdge:Int,maxPixels:Long)=ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(width,height),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
        .setResolutionFilter{sizes,_->sizes.filter{it.width<=maxEdge&&it.height<=maxEdge&&it.width.toLong()*it.height<=maxPixels}}
        .build()
    fun preview(rotation:Int):Preview=Preview.Builder().setResolutionSelector(resolution(960,720,1280,1_300_000)).setTargetRotation(rotation).build()
    fun singlePageCapture(rotation: Int): ImageCapture = ImageCapture.Builder()
        .setResolutionSelector(resolution(4032, 3024, 4096, 12_500_000))
        .setTargetRotation(rotation)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(95)
        .build()

    fun capture(rotation:Int):ImageCapture=ImageCapture.Builder().setResolutionSelector(resolution(1600,1200,2000,3_000_000))
        .setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(92).build()
}
