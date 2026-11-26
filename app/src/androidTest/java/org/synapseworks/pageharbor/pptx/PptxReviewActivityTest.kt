package org.synapseworks.pageharbor.pptx

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.security.*

class PptxReviewActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private lateinit var vm:PptxViewModel
    private lateinit var source:File
    private fun scroll(tag:String):SemanticsNodeInteraction {compose.onNodeWithTag("pptx-screen").performScrollToNode(hasTestTag(tag));return compose.onNodeWithTag(tag)}
    private fun ready(){compose.waitUntil(30000){!vm.busy&&vm.capture!=null&&vm.preview!=null&&vm.previewReady}}
    @Before fun prepare(){
        source=File(app.cacheDir,"shared-pdfs/m34-ui-${UUID.randomUUID()}.png").apply{parentFile!!.mkdirs();writeBytes(instrumentation.context.assets.open("m34/landscape.png").use{it.readBytes()})}
        compose.runOnIdle{
            val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)}
            lock.disableAfterAuthentication();vm=ViewModelProvider(compose.activity)[PptxViewModel::class.java]
            val s=DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),960,540))))
            vm.foreground(true);vm.open(s,7,"synthetic-reviewed-source",{true},{},{null})
        };ready();scroll("pptx-mode-EDITABLE").performClick();ready()
        compose.onNodeWithTag("pptx-screen").performScrollToNode(hasText("Add native text"));compose.onNodeWithText("Add native text").performClick()
    }
    @After fun cleanup(){compose.runOnIdle{vm.close()};source.delete()}
    private fun fill(){scroll("pptx-text-1").performTextReplacement("Reviewed native paragraph")}
    private fun generate(){fill();scroll("pptx-result").performClick();ready();scroll("pptx-reviewed").performClick();scroll("pptx-generate").performClick();compose.waitUntil(30000){!vm.busy&&vm.prepared!=null}}
    @Test fun nativeCorrectionFreshPreviewExplicitReviewAndGeneration(){
        scroll("pptx-color-1").performTextReplacement("17395B");scroll("pptx-color-apply-1").performClick()
        generate();val p=vm.prepared!!;assertEquals("Reviewed native paragraph",p.deck.pages.single().items.single().text);assertEquals("17395B",p.deck.pages.single().items.single().color)
        scroll("pptx-text-1").performTextReplacement("Expanded reviewed text\nSecond paragraph")
        assertNull(vm.prepared);assertTrue(vm.previewStale);assertFalse(vm.deck!!.pages.single().coverage)
        scroll("pptx-result").performClick();ready();scroll("pptx-reviewed").performClick();scroll("pptx-generate").performClick();compose.waitUntil(30000){vm.prepared!=null&&!vm.busy}
        assertTrue(vm.prepared!!.deck.pages.single().items.single().text.contains("Second paragraph"));assertNotEquals(p.digest,vm.prepared!!.digest)
    }
    @Test fun recreationDoesNotSerializeDraftText(){
        val secret="SYNTHETIC-M34-PRIVATE-DRAFT";scroll("pptx-text-1").performTextReplacement(secret)
        val state=Bundle();compose.runOnIdle{instrumentation.callActivityOnSaveInstanceState(compose.activity,state)}
        val parcel=Parcel.obtain();val data=try{state.writeToParcel(parcel,0);parcel.marshall()}finally{parcel.recycle()}
        assertFalse(String(data,Charsets.UTF_16LE).contains(secret));assertFalse(String(data,Charsets.UTF_8).contains(secret))
        compose.activityRule.scenario.recreate();compose.waitUntil(30000){!vm.visible&&vm.capture==null&&vm.deck==null&&vm.prepared==null}
    }
    @Test fun actualAppLockDiscardsPendingSaveAndDraft(){
        generate();val file=vm.prepared!!.file
        compose.runOnIdle{assertNotNull(vm.beginSave());val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil(30000){!vm.visible&&vm.deck==null&&!file.exists()};assertFalse(vm.picker)
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val r=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(r.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication()}
    }
}
