package org.synapseworks.pageharbor.pptx

import android.app.Instrumentation
import android.graphics.Rect
import org.synapseworks.pageharbor.workflow.WorkflowSystemUi

/** DocumentsUI can expose the selected root without ACTION_CLICK; use its fresh visible bounds. */
internal fun Instrumentation.pptxDownloads(ui:WorkflowSystemUi) {
    ui.until {
        if(uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true)false
        else {
            if(android.os.Build.VERSION.SDK_INT>=34)uiAutomation.clearCache()
            val node=ui.node{it.text?.toString()=="Downloads"}
            val bounds=Rect();node?.getBoundsInScreen(bounds)
            if(node?.refresh()==true&&node.isVisibleToUser&&!bounds.isEmpty){ui.shell("input tap ${bounds.centerX()} ${bounds.centerY()}");true}else false
        }
    }
}
