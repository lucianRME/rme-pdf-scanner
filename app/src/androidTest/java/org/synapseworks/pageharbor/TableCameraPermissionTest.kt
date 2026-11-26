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
import org.synapseworks.pageharbor.table.*
import org.synapseworks.pageharbor.security.*

/** Harness selects each case with controlled effective UID CAMERA access on an isolated AVD. */
class TableCameraPermissionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val model get()=compose.activity.table
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));compose.runOnIdle{
        val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)}
        lock.disableAfterAuthentication();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].clearScan();compose.activity.openTable()
    }}
    @After fun cleanup(){compose.runOnIdle{model.close()}}
    private fun click(tag:String){compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().performClick()}
    @Test fun denialKeepsImageInputReachableWithoutCameraOrDocumentMutation(){
        assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.onAllNodesWithTag("table-viewfinder").assertCountEquals(0);click("table-permission")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;var denied=false
        repeat(100){if(!denied){val nodes=automation.cameraDenialButtons();if(nodes.isNotEmpty())denied=nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)else Thread.sleep(100)}}
        assertTrue("Denial action unavailable; phase=${model.state?.phase}; root=${automation.rootInActiveWindow?.packageName}; rootId=${automation.rootInActiveWindow?.viewIdResourceName}",denied);compose.waitForIdle();assertNull(model.state!!.extraction)
        compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag("table-image"));compose.onNodeWithTag("table-image").assertIsEnabled();click("table-close")
    }
    @Test fun grantedViewfinderReleasesAfterAccessRevocation(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){compose.onAllNodesWithTag("table-viewfinder").fetchSemanticsNodes().isNotEmpty()}
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun access(mode:String){automation.executeShellCommand("appops set --uid ${app.packageName} CAMERA $mode").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes()}}}
        try{
            access("ignore");val ops=app.getSystemService(android.app.AppOpsManager::class.java)
            assertEquals(android.app.AppOpsManager.MODE_IGNORED,ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_CAMERA,android.os.Process.myUid(),app.packageName))
            compose.waitUntil(30_000){model.state?.phase==TablePhase.PAUSED};compose.onAllNodesWithTag("table-viewfinder").assertCountEquals(0);assertNull(model.state!!.extraction)
        }finally{access("foreground")}
    }
    @Test fun grantedEmulatedStillCaptureFinishesAndReleasesCameraWithoutDocumentMutation(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        val session=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java];val before=session.documentSession
        compose.waitUntil(30_000){compose.onAllNodesWithTag("table-viewfinder").fetchSemanticsNodes().isNotEmpty()}
        // Availability of the use case is asserted by the enabled shutter, not just Preview creation.
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<deadline){try{compose.onNodeWithTag("table-shutter").assertIsEnabled();break}catch(_:AssertionError){Thread.sleep(100)}}
        compose.onNodeWithTag("table-shutter").assertIsDisplayed().assertIsEnabled().performClick();compose.waitUntil(40_000){model.state?.phase in setOf(TablePhase.EDIT,TablePhase.PAUSED)}
        compose.onAllNodesWithTag("table-viewfinder").assertCountEquals(0);assertSame(before,session.documentSession)
        click("table-close")
    }
    @Test fun unavailableBackCameraReportsAnErrorAndCanExit(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){model.state?.phase==TablePhase.PAUSED};compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag("table-error"));compose.onNodeWithTag("table-error").assertIsDisplayed();click("table-close")
    }
}
