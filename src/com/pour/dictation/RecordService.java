package com.pour.dictation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.util.Log;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps phone dictation alive when the screen turns off (phone in a
 * pocket while pacing, etc.): a minimal microphone-type foreground
 * service. It owns no recording logic — the capture screen's engine
 * does the work; this service just holds the foreground claim while a
 * session runs. Started/stopped by MainActivity alongside the session.
 *
 * The notification doubles as the lock-screen control: Stop saves the
 * note, Pause/Resume freezes the session without saving.
 */
public class RecordService extends Service {

    private static final int NOTIF_ID = 42;
    private static final String CHANNEL = "pour_pro_recording";
    private static final String EXTRA_PAUSED = "pour_paused";

    /** Start the foreground claim; call from a visible activity. */
    public static void start(Context ctx) {
        startWithState(ctx, false);
    }

    private static void startWithState(Context ctx, boolean paused) {
        Intent i = new Intent(ctx, RecordService.class);
        i.putExtra(EXTRA_PAUSED, paused);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.w("RecordService", "Suppressed exception", e);
        }
    }

    /** Refresh the notification's pause/resume state. */
    public static void setPaused(Context ctx, boolean paused) {
        startWithState(ctx, paused);
    }

    /** Drop the foreground claim. */
    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, RecordService.class));
        } catch (Exception e) {
            Log.w("RecordService", "Suppressed exception", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        boolean paused = intent != null
                && intent.getBooleanExtra(EXTRA_PAUSED, false);
        Notification n = buildNotification(paused);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // Couldn't go foreground (e.g. started from the background
            // on Android 14+) — stop; the session itself is unaffected.
            stopSelf();
            return START_NOT_STICKY;
        }
        // Don't resurrect after a process kill: with no engine alive the
        // "listening" notification would be a lie; the draft covers
        // recovery instead.
        return START_NOT_STICKY;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        "Pour recording",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setSound(null, null);
                NotificationManager nm =
                        getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            } catch (Exception e) {
                Log.w("RecordService", "Suppressed exception", e);
            }
        }
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("svc_action", action);
        return PendingIntent.getActivity(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(boolean paused) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification.Action pauseAction;
        if (Build.VERSION.SDK_INT >= 23) {
            pauseAction = new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                            this, R.drawable.ic_mic),
                    paused ? "Resume" : "Pause",
                    actionIntent("pause_toggle", 1)).build();
        } else {
            pauseAction = null;
        }
        Notification.Action stopAction;
        if (Build.VERSION.SDK_INT >= 23) {
            stopAction = new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                            this, R.drawable.ic_stop),
                    "Stop", actionIntent("stop", 2)).build();
        } else {
            stopAction = null;
        }
        b.setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(paused ? "Pour is paused"
                        : "Pour is listening\u2026")
                .setContentText(paused ? "Tap to return and resume"
                        : "Tap to return and stop")
                .setContentIntent(pi)
                .setOngoing(true);
        if (pauseAction != null) b.addAction(pauseAction);
        if (stopAction != null) b.addAction(stopAction);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
