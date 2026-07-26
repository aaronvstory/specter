package com.specter.module.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that continuously captures Specter's on-device trace to a file while the
 * "Diagnostics logging" toggle is ON. It shells {@code su -c logcat -f <file>} filtered to the
 * {@code SpecterTrace} + {@code specter} tags, so the file shows exactly what each Specter-scoped
 * target app READ (native open/prop/stat) and what the Java hooks applied. logcat's own -f/-r/-n
 * do the file write + rotation — no read-loop needed.
 *
 * The file is world-readable under {@link #LOG_PATH} (same root-owned dir as the profiles), so it can
 * be pulled with {@code adb pull} / {@code su -c cat} at any time — "read it as we use it", no export
 * button. READ-ONLY: capturing a log applies nothing, so it is safe regardless of which app is scoped;
 * the native companion still hard-denylists APPLYING to income apps.
 */
public final class DiagnosticsService extends Service {
    public static final String LOG_PATH = DiagnosticsCmd.LOG_PATH;
    private static final String CHANNEL = "specter_diag";
    private static final int NOTE_ID = 4711;

    private Process proc;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTE_ID, buildNotification());
        startCapture();
        return START_STICKY;   // keep capturing across low-memory kills while the toggle is on
    }

    private void startCapture() {
        if (proc != null) return;
        try {
            // Clear only the main buffer (not the whole shared log) so the file starts fresh at enable.
            try { Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -b main -c"}).waitFor(); }
            catch (Throwable ignored) {}
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", DiagnosticsCmd.captureCommand()});
            // A successful exec() doesn't mean capture is alive — su denial / a bad arg can exit at once,
            // leaving proc non-null forever (later starts would no-op). Detect an immediate exit.
            if (p.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // Exited already → root denied or logcat rejected the args. Surface + stop.
                proc = null;
                stopSelf();
                return;
            }
            proc = p;   // still running → good
        } catch (Throwable t) {
            // No root / su denied — stop; the toggle in Settings surfaces the failure to the user.
            proc = null;
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        final Process p = proc;
        proc = null;
        if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        // proc.destroy() only kills the `su` wrapper — the actual `logcat -f` child runs in su's own
        // process and survives. pkill it by its unique -f arg. Do it OFF the main thread with a bounded
        // wait so a hanging su can't ANR the service; a quick re-enable is guarded by the proc!=null check.
        new Thread(() -> {
            try {
                Process k = Runtime.getRuntime().exec(new String[]{"su", "-c", DiagnosticsCmd.killCommand()});
                k.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                try { k.destroy(); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }, "specter-diag-stop").start();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Specter diagnostics", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Specter diagnostics logging")
                    .setContentText("Capturing what scoped apps read → " + LOG_PATH)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setOngoing(true)
                    .build();
        }
        //noinspection deprecation
        return new Notification.Builder(this)
                .setContentTitle("Specter diagnostics logging")
                .setContentText("Capturing → " + LOG_PATH)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
    }

    // ---- start/stop helpers used by the Settings toggle ----
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, DiagnosticsService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, DiagnosticsService.class));
    }
}
