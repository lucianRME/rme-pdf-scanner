package org.synapseworks.pageharbor

import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo

internal fun UiAutomation.cameraDenialButtons(): List<AccessibilityNodeInfo> {
    val service = serviceInfo
    if (service.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0) {
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = service
    }
    val window = rootInActiveWindow ?: return emptyList()
    val systemPackage = window.packageName?.toString() ?: return emptyList()
    if (systemPackage !in setOf(
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )
    ) return emptyList()
    // The Google permission controller can keep framework resource IDs from its AOSP package.
    val resourcePackages = listOf(systemPackage, "com.android.permissioncontroller", "com.android.packageinstaller")
    for (name in resourcePackages.distinct()) {
        val matches = window.findAccessibilityNodeInfosByViewId("$name:id/permission_deny_button")
        if (matches.isNotEmpty()) return matches
    }
    return listOf("Don’t allow", "Don't allow", "Deny").flatMap { label ->
        window.findAccessibilityNodeInfosByText(label).filter { node ->
            node.text?.toString() == label && node.isEnabled && node.isClickable
        }
    }
}
