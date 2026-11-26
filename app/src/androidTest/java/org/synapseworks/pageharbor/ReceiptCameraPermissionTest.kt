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
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.security.*

/** Harness selects each case with controlled effective UID CAMERA access on an isolated AVD. */
class ReceiptCameraPermissionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val model get()=compose.activity.receipt
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));compose.runOnIdle{
        val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)}
        lock.disableAfterAuthentication();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].clearScan();compose.activity.openReceipt()
    }}
    @After fun cleanup(){compose.runOnIdle{model.close()}}
    private fun click(tag:String){compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().performClick()}
    @Test fun denialKeepsImageInputReachableWithoutCameraOrDocumentMutation(){
        assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.onAllNodesWithTag("receipt-viewfinder").assertCountEquals(0);click("receipt-permission")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;var denied=false
        repeat(100){if(!denied){val nodes=automation.cameraDenialButtons();if(nodes.isNotEmpty())denied=nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)else Thread.sleep(100)}}
        assertTrue("Denial action unavailable; phase=${model.state?.phase}; root=${automation.rootInActiveWindow?.packageName}; rootId=${automation.rootInActiveWindow?.viewIdResourceName}",denied);compose.waitForIdle();assertNull(model.state!!.extraction)
        compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag("receipt-image"));compose.onNodeWithTag("receipt-image").assertIsEnabled();click("receipt-close")
    }
    @Test fun grantedViewfinderReleasesAfterAccessRevocation(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){compose.onAllNodesWithTag("receipt-viewfinder").fetchSemanticsNodes().isNotEmpty()}
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun access(mode:String){automation.executeShellCommand("appops set --uid ${app.packageName} CAMERA $mode").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes()}}}
        try{
            access("ignore");val ops=app.getSystemService(android.app.AppOpsManager::class.java)
            assertEquals(android.app.AppOpsManager.MODE_IGNORED,ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_CAMERA,android.os.Process.myUid(),app.packageName))
            compose.waitUntil(30_000){model.state?.phase==ReceiptPhase.PAUSED};compose.onAllNodesWithTag("receipt-viewfinder").assertCountEquals(0);assertNull(model.state!!.extraction)
        }finally{access("foreground")}
    }
    @Test fun grantedEmulatedStillCaptureFinishesAndReleasesCameraWithoutDocumentMutation(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        val session=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java];val before=session.documentSession
        compose.waitUntil(30_000){compose.onAllNodesWithTag("receipt-viewfinder").fetchSemanticsNodes().isNotEmpty()}
        // Availability of the use case is asserted by the enabled shutter, not just Preview creation.
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<deadline){try{compose.onNodeWithTag("receipt-shutter").assertIsEnabled();break}catch(_:AssertionError){Thread.sleep(100)}}
        compose.onNodeWithTag("receipt-shutter").assertIsDisplayed().assertIsEnabled().performClick();compose.waitUntil(40_000){model.state?.phase in setOf(ReceiptPhase.EDIT,ReceiptPhase.PAUSED)}
        compose.onAllNodesWithTag("receipt-viewfinder").assertCountEquals(0);assertSame(before,session.documentSession)
        click("receipt-close")
    }
    @Test fun unavailableBackCameraReportsAnErrorAndCanExit(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){model.state?.phase==ReceiptPhase.PAUSED};compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag("receipt-error"));compose.onNodeWithTag("receipt-error").assertIsDisplayed();click("receipt-close")
    }
}
