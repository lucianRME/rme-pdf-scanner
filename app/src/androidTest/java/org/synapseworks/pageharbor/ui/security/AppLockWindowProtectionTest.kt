package org.synapseworks.pageharbor.ui.security

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.security.SharedPreferencesAppLockStateStore

class AppLockWindowProtectionTest {
    @Test
    fun secureWindowFollowsPersistedAppLockSettingAtLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".phase2test"))
        val store = SharedPreferencesAppLockStateStore(context)
        val original = store.read()
        try {
            listOf(false, true).forEach { enabled ->
                store.write(original.copy(config = original.config.copy(enabled = enabled)))
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val flags = activity.window.attributes.flags
                        assertEquals(
                            "FLAG_SECURE with App lock enabled=$enabled",
                            enabled,
                            flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                        )
                    }
                }
            }
        } finally {
            store.write(original)
        }
    }
}
