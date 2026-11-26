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
import org.synapseworks.pageharbor.qr.*
import org.synapseworks.pageharbor.security.*

/** Harness selects each case with controlled effective UID CAMERA access on an isolated AVD. */
class QrCameraPermissionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val model get()=compose.activity.qr
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));compose.runOnIdle{
        val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)}
        lock.disableAfterAuthentication();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].clearScan();compose.activity.openQrScanner()
    }}
    @After fun cleanup(){compose.runOnIdle{model.close()}}
    private fun click(tag:String){compose.onNodeWithTag("qr-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().performClick()}
    @Test fun denialKeepsImageInputReachableWithoutCameraOrDocumentMutation(){
        assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.onAllNodesWithTag("qr-viewfinder").assertCountEquals(0);click("qr-permission")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;var denied=false
        repeat(30){if(!denied){val nodes=automation.cameraDenialButtons();if(nodes.isNotEmpty())denied=nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)else Thread.sleep(100)}}
        assertTrue(denied);compose.waitForIdle();assertNull(model.state!!.result)
        compose.onNodeWithTag("qr-list").performScrollToNode(hasTestTag("qr-image"));compose.onNodeWithTag("qr-image").assertIsEnabled();click("qr-close")
    }
    @Test fun grantedViewfinderReleasesAfterAccessRevocation(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){compose.onAllNodesWithTag("qr-viewfinder").fetchSemanticsNodes().isNotEmpty()}
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun access(mode:String){automation.executeShellCommand("appops set --uid ${app.packageName} CAMERA $mode").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes()}}}
        try{
            access("ignore");val ops=app.getSystemService(android.app.AppOpsManager::class.java)
            assertEquals(android.app.AppOpsManager.MODE_IGNORED,ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_CAMERA,android.os.Process.myUid(),app.packageName))
            compose.waitUntil(30_000){model.state?.problem==QrProblem.PERMISSION};compose.onAllNodesWithTag("qr-viewfinder").assertCountEquals(0);assertNull(model.state!!.result)
        }finally{access("foreground")}
    }
    @Test fun unavailableBackCameraReportsAnErrorAndCanExit(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){model.state?.problem==QrProblem.CAMERA};compose.onNodeWithTag("qr-list").performScrollToNode(hasTestTag("qr-error"));compose.onNodeWithTag("qr-error").assertIsDisplayed();click("qr-close")
    }
}
