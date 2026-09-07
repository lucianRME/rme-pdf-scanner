package org.synapseworks.pageharbor

import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationBrandingTest {
    @Test
    fun installedApplicationAndLauncherExposeTheNewNameWithExistingIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager

        assertEquals("org.synapseworks.pageharbor", context.packageName)
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
        val authority = "org.synapseworks.pageharbor.fileprovider"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))

        assertEquals(context.packageName, provider.packageName)
        assertEquals(authority, provider.authority)
    }
}
