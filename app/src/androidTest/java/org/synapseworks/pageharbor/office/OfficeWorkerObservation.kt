package org.synapseworks.pageharbor.office

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import java.io.DataInputStream
import java.io.File

internal val officeWorkerProbe=java.util.concurrent.atomic.AtomicReference<IntArray?>()

/** Bounded authenticated Binder-hello journal and non-signalling kernel existence query.
 * Shell pidof can miss a short-lived isolated worker; no process name is trusted.
 */
internal fun liveOfficeEngine(context: Context): Pair<Int, Int>? {
    val jobs=File(context.noBackupFilesDir,"office-engine/jobs").listFiles().orEmpty()
    check(jobs.size<=1)
    return jobs.firstNotNullOfOrNull { directory ->
        runCatching {
            val file=File(directory,"engine.pid");check(file.length()==12L)
            DataInputStream(file.inputStream()).use { data ->
                check(data.readInt()==0x35354644)
                val pid=data.readInt();val uid=data.readInt()
                check(pid>1 && pid!=Process.myPid() && uid%100000>=90000 && data.read()==-1)
                val statUid=runCatching{Os.stat("/proc/$pid").st_uid}.getOrDefault(-1)
                val errno=try { Os.kill(pid,0);0 }catch(failure:ErrnoException){failure.errno}
                officeWorkerProbe.set(intArrayOf(pid,uid,statUid,errno))
                // Non-dumpable workers need not expose their UID through procfs.
                // UID authentication comes from the coordinator's Binder hello.
                check(errno==0 || errno==OsConstants.EPERM)
                pid to uid
            }
        }.getOrNull()
    }
}
