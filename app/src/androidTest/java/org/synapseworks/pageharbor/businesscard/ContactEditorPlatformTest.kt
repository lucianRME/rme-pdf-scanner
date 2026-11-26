package org.synapseworks.pageharbor.businesscard

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic unsaved editor data on the owned emulator. No provider writes. */
class ContactEditorPlatformTest {
    @Test fun realEmulatorEditorShowsReviewedSyntheticFieldsAndCancelLeavesContactsUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        check(context.packageName.endsWith(".phase2test"))
        val automation = instrumentation.uiAutomation
        fun count(): Int {
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_CONTACTS)
            return try { context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID), null, null, null)!!.use { it.count } }
            finally { automation.dropShellPermissionIdentity() }
        }
        val before = count(); check(before == 0) { "Contact test storage must be empty" }
        fun nodes(): List<AccessibilityNodeInfo> {
            val root = automation.rootInActiveWindow ?: return emptyList()
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) { result += node; for(index in 0 until node.childCount) node.getChild(index)?.let(::visit) }
            visit(root); return result
        }
        fun await(predicate: (List<AccessibilityNodeInfo>) -> Boolean): Boolean {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            while(System.nanoTime() < deadline) { if(predicate(nodes())) return true; Thread.sleep(100) }; return false
        }
        val draft = ContactDraft("SyntheticCardReview", "Harbor Studio", "Design Lead", listOf("+1 202 555 0100", "+44 20 7946 0100"), listOf("card@example.invalid", "other@example.invalid"), listOf("12 Sample Road\nDublin D02 AB12", "42 Sample Way"))
        val intent = ContactEditorIntent.create(draft)!!
        // Package visibility can hide query results; actual explicit user handoff is the evidence.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            assertTrue("The emulator editor must present the synthetic reviewed name", await { list -> list.any { it.text?.toString() == draft.name } })
            val expected = (listOf(draft.name, draft.organization, draft.title) + draft.phones + draft.emails + draft.addresses).toSet()
            val seen = mutableSetOf<String>()
            repeat(10) {
                val current = nodes(); seen += current.mapNotNull { it.text?.toString() }.filter { it in expected }
                if(!seen.containsAll(expected)) {
                    current.firstOrNull { it.isScrollable && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(200)
                }
            }
            assertEquals("Every reviewed field must be available in the emulator editor", expected, seen)
        } finally {
            repeat(3) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); Thread.sleep(300)
                nodes().firstOrNull { it.text?.toString()?.equals("Discard", true) == true }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(200)
            }
            assertFalse(nodes().any { it.packageName?.toString() == "com.google.android.contacts" && it.text?.toString() == "Save" })
            assertEquals(before, count())
        }
    }
}
