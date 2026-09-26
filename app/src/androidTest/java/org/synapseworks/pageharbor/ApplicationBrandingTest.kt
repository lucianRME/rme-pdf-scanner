package org.synapseworks.pageharbor

import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationBrandingTest {
    @Test
    fun installedApplicationAndLauncherExposeTheNewNameWithExistingIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager

        assertTrue(context.packageName in SUPPORTED_TEST_TARGETS)
        assertEquals("RME: PDF & Document Scanner", context.getString(R.string.app_name))
        assertEquals("RME PDF Scanner", context.getString(R.string.app_name_short))
        assertEquals(
            "RME: PDF & Document Scanner",
            context.applicationInfo.loadLabel(packageManager).toString(),
        )
        val launcher = requireNotNull(packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(ComponentName(context, MainActivity::class.java), launcher.component)
        val activity = packageManager.getActivityInfo(requireNotNull(launcher.component), 0)
        assertEquals("RME: PDF & Document Scanner", activity.loadLabel(packageManager).toString())
    }

    @Test
    fun existingFileProviderAuthorityStillResolvesToTheSameApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.fileprovider"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))

        assertEquals(context.packageName, provider.packageName)
        assertEquals(authority, provider.authority)
    }

    @Test
    fun phaseTwoDeviceSuiteUsesOnlyTheIsolatedTargetPackage() {
        val actual = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        assertEquals(PHASE_2_TEST_APPLICATION_ID, actual)
        assertNotEquals(PRODUCTION_APPLICATION_ID, actual)
    }

    private companion object {
        const val PRODUCTION_APPLICATION_ID = "org.synapseworks.pageharbor"
        const val PHASE_2_TEST_APPLICATION_ID = "$PRODUCTION_APPLICATION_ID.phase2test"
        val SUPPORTED_TEST_TARGETS = setOf(PRODUCTION_APPLICATION_ID, PHASE_2_TEST_APPLICATION_ID)
    }
}
