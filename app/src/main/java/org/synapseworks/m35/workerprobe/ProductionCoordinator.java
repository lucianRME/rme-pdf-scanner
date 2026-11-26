package org.synapseworks.m35.workerprobe;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import android.app.ActivityManager;
import android.os.Debug;

/** App-private adapter; qualified preparation/publication method bodies stay intact.
 * No test command or fault injection is reachable through this binder.
 */
public final class ProductionCoordinator extends Coordinator {
    private volatile int clientPid;
    private long parentPeak, reportedNativePeak;
    @Override boolean permitted() { return Binder.getCallingUid() == android.os.Process.myUid(); }
    private long ownedBytes(File file, int depth, int[] entries) throws IOException {
        if (++entries[0] > 2048 || depth > 8 || Files.isSymbolicLink(file.toPath())) throw new IOException();
        if (!file.exists()) return 0;
        if (!file.isDirectory()) return file.length();
        File[] children = file.listFiles(); if (children == null) throw new IOException();
        long total = 0; for (File child : children) total = Math.addExact(total, ownedBytes(child, depth + 1, entries));
        return total;
    }
    private long otherStorage() throws IOException {
        int[] entries = {0};
        long total = ownedBytes(new File(super.getNoBackupFilesDir(), "office-ui"), 0, entries)
                + ownedBytes(new File(super.getNoBackupFilesDir(), "office-offers"), 0, entries)
            + ownedBytes(new File(super.getNoBackupFilesDir(), "office-preview"), 0, entries);
        File shared = new File(getCacheDir(), "shared-pdfs");
        if (Files.isSymbolicLink(shared.toPath())) throw new IOException();
        File[] files = shared.listFiles();
        if (files != null) for (File file : files) if (file.getName().startsWith("office-")) total = Math.addExact(total, ownedBytes(file, 0, entries));
        return total;
    }
    @Override void prepare(Job job) {
        try {
            // Count retained UI/share bytes, not just the isolated job's scratch.
            if (otherStorage() + CapabilityProfile.WRITABLE + CapabilityProfile.READ_ONLY > 256L * 1024 * 1024) {
                finish(job, "TEMP_LIMIT"); return;
            }
        } catch (Exception ignored) { finish(job, "MONITOR_FAILED"); return; }
        super.prepare(job);
    }
    @Override void monitor(Job job) {
        if (job.finished.get() || job.cancelled.get()) return;
        try {
            long used = bytes(job.root) + otherStorage() + (job.isolated != null ? job.isolated.reserved : 0);
            job.tempPeak = Math.max(job.tempPeak, used);
            ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
            Debug.MemoryInfo[] samples = manager.getProcessMemoryInfo(new int[]{job.pid, android.os.Process.myPid(), clientPid});
            long parent = (samples[1].getTotalPss() + samples[2].getTotalPss()) * 1024L;
            long pss;
            synchronized (this) {
                parentPeak = Math.max(parentPeak, parent);
                // Recent Android versions may suppress another UID's PSS in
                // ActivityManager. Retain the isolated worker's own reported
                // peak, conservatively combined with the parent/client peak.
                pss = Math.max(samples[0].getTotalPss() * 1024L + parent, reportedNativePeak + parentPeak);
            }
            job.pssPeak = Math.max(job.pssPeak, pss);
            if (new File(job.root, "result.pdf").length() > 32L * 1024 * 1024) requestCancel(job, "OUTPUT_LIMIT");
            else if (used > 256L * 1024 * 1024) requestCancel(job, "TEMP_LIMIT");
            else if (pss > 512L * 1024 * 1024) requestCancel(job, "MEMORY_LIMIT");
        } catch (Exception ignored) { requestCancel(job, "MONITOR_FAILED"); }
    }
    synchronized void nativePeak(Job job, long peak) {
        if (active != job) return;
        if (peak < 0 || peak > 512L * 1024 * 1024) { job.pssPeak = Long.MAX_VALUE; return; }
        reportedNativePeak = Math.max(reportedNativePeak, peak);
        job.pssPeak = Math.max(job.pssPeak, reportedNativePeak + parentPeak);
    }
    @Override void finish(Job job, String status) {
        // A local Binder call otherwise retains the original isolated caller's
        // identity. Publish only the trusted coordinator's verified outcome.
        long identity = Binder.clearCallingIdentity();
        try { super.finish(job, status); } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public File getNoBackupFilesDir() {
        File directory = new File(super.getNoBackupFilesDir(), "office-engine");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Office storage unavailable");
        return directory;
    }
    @Override public IBinder onBind(Intent intent) {
        IBinder trusted = super.onBind(intent);
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (!permitted() || reply == null || data.dataSize() > 2048 || (code != 1 && code != 2 && code != 5)) return false;
                int position = data.dataPosition();
                try {
                    data.enforceInterface(API);
                    if (code == 1) { data.readString(); data.readString(); if (data.readInt() != 0) return false; }
                    data.setDataPosition(position);
                    if (code == 1) {
                        synchronized (ProductionCoordinator.this) {
                            if (active == null) {
                                int caller = Binder.getCallingPid();
                                if (caller <= 1 || caller == android.os.Process.myPid()) return false;
                                clientPid = caller;
                                parentPeak = 0; reportedNativePeak = 0;
                            }
                            return trusted.transact(code, data, reply, flags);
                        }
                    }
                    return trusted.transact(code, data, reply, flags);
                } catch (Exception ignored) { return false; }
            }
        };
    }
}
