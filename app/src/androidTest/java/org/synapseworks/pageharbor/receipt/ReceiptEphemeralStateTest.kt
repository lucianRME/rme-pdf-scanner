package org.synapseworks.pageharbor.receipt

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.security.*

/** Exercises the actual platform/Compose save path, not just the absence of SavedStateHandle. */
class ReceiptEphemeralStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun editedReceiptValuesAreAbsentFromTheActivitySavedStateParcel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check((instrumentation.targetContext.applicationContext as Application).packageName.endsWith(".phase2test"))
        val model = compose.activity.receipt
        val secret = "M21EphemeralSyntheticOnlyQ7Z9"
        try {
            compose.runOnIdle {
                val lock = ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
                if(lock.state.value.phase == AppLockPhase.LOCKED) { val t = lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!; lock.onAuthenticationResult(t.id, AppLockAuthenticationResult.Success) }
                lock.disableAfterAuthentication(); model.close(); compose.activity.openReceipt(); model.manual()
            }
            compose.onNodeWithTag("receipt-list").performScrollToNode(hasTestTag("receipt-merchant"))
            compose.onNodeWithTag("receipt-merchant").performTextReplacement(secret)
            val saved = Bundle()
            compose.runOnIdle { assertEquals(secret, model.state!!.draft.values[ReceiptField.MERCHANT]); instrumentation.callActivityOnSaveInstanceState(compose.activity, saved) }
            val parcel = Parcel.obtain()
            val bytes = try { saved.writeToParcel(parcel, 0); parcel.marshall() } finally { parcel.recycle() }
            for(charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                val needle = secret.toByteArray(charset)
                val found = (0..(bytes.size - needle.size).coerceAtLeast(-1)).any { start -> needle.indices.all { bytes[start + it] == needle[it] } }
                assertFalse("Ephemeral receipt text must not be saved by Activity or Compose", found)
            }
        } finally { compose.runOnIdle { model.close() } }
    }
}
