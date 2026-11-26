package org.synapseworks.pageharbor.document.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** M10 effective permission/AppOps contract, shared by continuous capture and M19 analysis. */
internal object CameraAccess {
    fun allowed(context: Context): Boolean {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return false
        return runCatching {
            val ops=context.getSystemService(android.app.AppOpsManager::class.java)?:return@runCatching false
            val mode=ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_CAMERA,android.os.Process.myUid(),context.packageName)
            mode==android.app.AppOpsManager.MODE_ALLOWED || mode==android.app.AppOpsManager.MODE_DEFAULT ||
                (android.os.Build.VERSION.SDK_INT>=29 && mode==android.app.AppOpsManager.MODE_FOREGROUND)
        }.getOrDefault(false)
    }
}
