package org.synapseworks.pageharbor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.capture.*
import org.synapseworks.pageharbor.security.*

/** Each method is launched separately after the external harness configures this isolated package. */
class ContinuousCameraPermissionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun model()=ViewModelProvider(compose.activity)[ContinuousCaptureViewModel::class.java]
    private fun owner()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));compose.runOnIdle{
        val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)}
        lock.disableAfterAuthentication();owner().clearScan();compose.activity.continuousBackendForTest=null;compose.activity.openContinuousCapture()
    }}
    @After fun cleanup(){compose.runOnIdle{compose.activity.cancelContinuousCapture();owner().clearScan()}}
    private fun visibleControl(tag: String): SemanticsNodeInteraction {
        val control = compose.onNodeWithTag(tag)
        if (!control.isDisplayed()) control.performScrollTo()
        return control.assertIsDisplayed()
    }
    private fun click(tag: String) = visibleControl(tag).performClick()
    @Test fun noPermissionDoesNotOpenCameraAndDenialPreservesTheDocument() {
        assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.onNodeWithTag("capture-permission").assertIsDisplayed();compose.onNodeWithTag("capture-shutter").assertIsNotEnabled()
        compose.onAllNodesWithTag("capture-viewfinder").assertCountEquals(0);click("capture-permission")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        var denied=false
        repeat(30){
            if(!denied){val nodes=automation.cameraDenialButtons()
                if(nodes.isNotEmpty()){denied=nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)}else Thread.sleep(100)}
        }
        assertTrue(denied);compose.waitForIdle();assertTrue(owner().documentPages.isEmpty());click("capture-cancel")
    }
    @Test fun grantedEmulatedCameraCapturesTwoIdenticalScenesWithoutPerPageConfirmation() {
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){
            val node=compose.onAllNodesWithTag("capture-shutter").fetchSemanticsNodes().singleOrNull()
            node!=null&&!node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)&&compose.onAllNodesWithTag("capture-viewfinder").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(2){i->
            visibleControl("capture-shutter").assertIsEnabled().performClick()
            compose.waitUntil(30_000){model().state?.pages?.size==i+1&&model().state?.busy==false}
        }
        val files=model().state!!.pages.map{it.file};assertEquals(2,files.size)
        assertTrue(model().state!!.pages.all{it.metadata.width!!<=2000&&it.metadata.height!!<=2000&&it.metadata.width!!.toLong()*it.metadata.height!!<=3_000_000})
        assertTrue(owner().documentPages.isEmpty());click("capture-finish");click("capture-use");compose.waitUntil(30_000){owner().documentPages.size==2}
        assertTrue(files.all{it.exists()});compose.runOnIdle{owner().clearScan()};compose.waitUntil(30_000){files.none{it.exists()}}
    }
    @Test fun liveCameraAccessBlockReleasesViewfinderAndKeepsReadyPagesForReview() {
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){val node=compose.onAllNodesWithTag("capture-shutter").fetchSemanticsNodes().singleOrNull();node!=null&&!node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}
        click("capture-shutter");compose.waitUntil(30_000){model().state?.pages?.size==1&&model().state?.busy==false}
        val file=model().state!!.pages.single().file
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun access(mode:String){automation.executeShellCommand("appops set --uid ${app.packageName} CAMERA $mode").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes()}}}
        try {
            access("ignore")
            val ops=app.getSystemService(android.app.AppOpsManager::class.java)
            val mode=ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_CAMERA,android.os.Process.myUid(),app.packageName)
            java.io.File(app.getExternalFilesDir(null),"m10-appops-diagnostic.txt").writeText("uid=${android.os.Process.myUid()};mode=$mode;expectedIgnored=${android.app.AppOpsManager.MODE_IGNORED}\n")
            assertEquals(android.app.AppOpsManager.MODE_IGNORED,mode)
            compose.waitUntil(30_000){model().state?.paused==true}
            assertTrue(file.exists());assertTrue(owner().documentPages.isEmpty());compose.onAllNodesWithTag("capture-viewfinder").assertCountEquals(0)
            click("capture-finish");click("capture-use");compose.waitUntil(30_000){owner().documentPages.size==1};assertTrue(file.exists())
        } finally {access("foreground")}
    }
    @Test fun unavailableRearCameraReportsFailureAndCanCancelWithoutChangingSession() {
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){model().state?.problem==CaptureProblem.CAMERA}
        visibleControl("capture-error");assertTrue(owner().documentPages.isEmpty());click("capture-cancel")
    }
}
