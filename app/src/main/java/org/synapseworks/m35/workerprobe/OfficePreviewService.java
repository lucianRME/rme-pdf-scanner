package org.synapseworks.m35.workerprobe;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fresh isolated UID, irreversible pre-parse sandbox, raw bounded pixels only.
 * No PDF/image decoder runs in the consumer process. Synchronous row chunks
 * cannot accumulate in an unbounded Binder queue or mutate previously sent rows.
 */
public final class OfficePreviewService extends Service {
    public static final String API = "RME_OFFICE_PREVIEW_V1", RESULT = "RME_OFFICE_PIXELS_V1";
    private final AtomicBoolean used = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();
    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 1 || reply == null || Binder.getCallingUid() != getApplicationInfo().uid || data.dataSize() > 1024) return false;
                ParcelFileDescriptor input = null;
                try {
                    data.enforceInterface(API);
                    String token = data.readString(); int page = data.readInt(), pages = data.readInt();
                    input = data.readFileDescriptor(); IBinder callback = data.readStrongBinder();
                    if (token == null || !token.matches("[a-f0-9]{32}") || page < 0 || page >= pages || pages > 10 || input == null || callback == null || data.dataAvail() != 0 || !used.compareAndSet(false, true)) throw new IOException();
                    ParcelFileDescriptor owned = input; input = null;
                    callback.linkToDeath(() -> android.os.Process.killProcess(android.os.Process.myPid()), 0);
                    io.execute(() -> render(token, owned, page, pages, callback));
                    reply.writeInt(1); return true;
                } catch (Exception ignored) {
                    Coordinator.close(input); reply.writeInt(0); return true;
                }
            }
        };
    }
    private void send(IBinder target, int code, Parcel data) throws Exception {
        Parcel reply = Parcel.obtain();
        try { if (!target.transact(code, data, reply, 0) || reply.readInt() != 1) throw new IOException(); }
        finally { reply.recycle(); data.recycle(); }
    }
    private Parcel message(String token) {
        Parcel data = Parcel.obtain(); data.writeInterfaceToken(RESULT); data.writeString(token); return data;
    }
    private void render(String token, ParcelFileDescriptor source, int index, int expectedPages, IBinder target) {
        String status = "PREVIEW_FAILED";
        AtomicBoolean running = new AtomicBoolean(true);
        long started = android.os.SystemClock.elapsedRealtime();
        // Start the trusted sampler before the irreversible syscall policy.
        // A stuck parser/IPC cannot outlive the fixed client deadline.
        Thread monitor = new Thread(() -> {
            while (running.get()) {
                long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                if (heap > 96L * 1024 * 1024 || android.os.Debug.getPss() * 1024L > 512L * 1024 * 1024
                    || android.os.SystemClock.elapsedRealtime() - started > 15000) {
                    android.os.Process.killProcess(android.os.Process.myPid()); return;
                }
                android.os.SystemClock.sleep(100);
            }
        }, "office-preview-limit");
        monitor.setDaemon(true); monitor.start();
        try {
            // Authenticated identity arrives before any PDF parsing or worker hang.
            Parcel hello = message(token); hello.writeInt(android.os.Process.myUid()); hello.writeInt(android.os.Process.myPid()); send(target, 0, hello);
            StructStat stat = Os.fstat(source.getFileDescriptor());
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0 || stat.st_size > 32L * 1024 * 1024 || (OfficeFd.flags(source) & OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) throw new IOException();
            if (!Engine.quiet()) throw new IOException();
            Class.forName("android.graphics.pdf.PdfRenderer");
            if (!Engine.sandbox()) throw new IOException();
            try (PdfRenderer pdf = new PdfRenderer(ParcelFileDescriptor.dup(source.getFileDescriptor()))) {
                if (pdf.getPageCount() != expectedPages) throw new IOException();
                try (PdfRenderer.Page page = pdf.openPage(index)) {
                    if (page.getWidth() < 1 || page.getHeight() < 1 || page.getWidth() > 14400 || page.getHeight() > 14400) throw new IOException();
                    double scale = 1280.0 / Math.max(page.getWidth(), page.getHeight());
                    int width = Math.max(1, (int)(page.getWidth() * scale)), height = Math.max(1, (int)(page.getHeight() * scale));
                    if ((long)width * height * 4 + 1024 * 1024 > 8 * 1024 * 1024) throw new IOException();
                    // The receiver spools bounded raw rows to owned storage. Its
                    // display bitmap is allocated only after this process dies.
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        Parcel dimensions = message(token); dimensions.writeInt(width); dimensions.writeInt(height); send(target, 1, dimensions);
                        int[] reusable = new int[width * 8];
                        for (int row = 0; row < height;) {
                            int count = Math.min(8, height - row); int[] pixels = count == 8 ? reusable : new int[width * count];
                            bitmap.getPixels(pixels, 0, width, 0, row, width, count);
                            Parcel chunk = message(token); chunk.writeInt(row); chunk.writeInt(count); chunk.writeIntArray(pixels); send(target, 2, chunk); row += count;
                        }
                    } finally { bitmap.recycle(); }
                }
            }
            status = "OK";
        } catch (Throwable ignored) {
            // No parser exception, source text or path is retained in diagnostics.
        } finally {
            running.set(false);
            Coordinator.close(source);
            try { Parcel result = message(token); result.writeString(status); send(target, 3, result); } catch (Exception ignored) { }
        }
    }
    @Override public void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
