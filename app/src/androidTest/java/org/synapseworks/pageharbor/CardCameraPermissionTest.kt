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
import org.synapseworks.pageharbor.businesscard.*
import org.synapseworks.pageharbor.security.*

/** Harness selects each case with controlled effective UID CAMERA access on an isolated AVD. */
class CardCameraPermissionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val model get()=compose.activity.businessCard
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));compose.runOnIdle{
        val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)}
        lock.disableAfterAuthentication();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].clearScan();compose.activity.openBusinessCard()
    }}
    @After fun cleanup(){compose.runOnIdle{model.close()}}
    private fun click(tag:String){compose.onNodeWithTag("card-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().performClick()}
    @Test fun denialKeepsImageInputReachableWithoutCameraOrDocumentMutation(){
        assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.onAllNodesWithTag("card-viewfinder").assertCountEquals(0);click("card-permission")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;var denied=false
        repeat(30){if(!denied){val nodes=automation.cameraDenialButtons();if(nodes.isNotEmpty())denied=nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)else Thread.sleep(100)}}
        assertTrue(denied);compose.waitForIdle();assertNull(model.state!!.suggestions)
        compose.onNodeWithTag("card-list").performScrollToNode(hasTestTag("card-image"));compose.onNodeWithTag("card-image").assertIsEnabled();click("card-close")
    }
    @Test fun grantedViewfinderReleasesAfterAccessRevocation(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){compose.onAllNodesWithTag("card-viewfinder").fetchSemanticsNodes().isNotEmpty()}
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun access(mode:String){automation.executeShellCommand("appops set --uid ${app.packageName} CAMERA $mode").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes()}}}
        try{
            access("ignore");val ops=app.getSystemService(android.app.AppOpsManager::class.java)
            assertEquals(android.app.AppOpsManager.MODE_IGNORED,ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_CAMERA,android.os.Process.myUid(),app.packageName))
            compose.waitUntil(30_000){model.state?.phase==CardPhase.PAUSED};compose.onAllNodesWithTag("card-viewfinder").assertCountEquals(0);assertNull(model.state!!.suggestions)
        }finally{access("foreground")}
    }
    @Test fun grantedEmulatedStillCaptureFinishesAndReleasesCameraWithoutDocumentMutation(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        val session=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java];val before=session.documentSession
        compose.waitUntil(30_000){compose.onAllNodesWithTag("card-viewfinder").fetchSemanticsNodes().isNotEmpty()}
        // Availability of the use case is asserted by the enabled shutter, not just Preview creation.
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<deadline){try{compose.onNodeWithTag("card-shutter").assertIsEnabled();break}catch(_:AssertionError){Thread.sleep(100)}}
        compose.onNodeWithTag("card-shutter").assertIsDisplayed().assertIsEnabled().performClick();compose.waitUntil(40_000){model.state?.phase in setOf(CardPhase.EDIT,CardPhase.PAUSED)}
        compose.onAllNodesWithTag("card-viewfinder").assertCountEquals(0);assertSame(before,session.documentSession)
        click("card-close")
    }
    @Test fun unavailableBackCameraReportsAnErrorAndCanExit(){
        assertEquals(PackageManager.PERMISSION_GRANTED,ContextCompat.checkSelfPermission(app,Manifest.permission.CAMERA))
        compose.waitUntil(30_000){model.state?.phase==CardPhase.PAUSED};compose.onNodeWithTag("card-list").performScrollToNode(hasTestTag("card-error"));compose.onNodeWithTag("card-error").assertIsDisplayed();click("card-close")
    }
}
