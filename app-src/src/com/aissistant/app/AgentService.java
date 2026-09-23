package com.aissistant.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the agent process alive, unfrozen and on the network while a task runs - essential when
 * the task drives OTHER apps (open WhatsApp, tap, type): AI-ssistant goes to the background and
 * the system would otherwise freeze it / cut its network, stalling the agent loop mid-task.
 */
public class AgentService extends Service {

    private static final String CHANNEL = "agent";
    static final int ID = 1;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel c = new NotificationChannel(CHANNEL, "Agent",
                            NotificationManager.IMPORTANCE_LOW);
                    c.setShowBadge(false);
                    nm.createNotificationChannel(c);
                }
            }
            startForeground(ID, build(this, "Working on the device \u00b7 driving apps, running root commands"));
        } catch (Throwable ignored) { }
        // the run keeps going while the agent drives OTHER apps - only then does the panel belong
        // on screen; while the user is looking at the chat it would just cover it
        try {
            android.util.Log.i("AIssistant", "service start: appVisible=" + MainActivity.appVisible
                    + " canDraw=" + OverlayView.canDraw(this));
            if (!MainActivity.appVisible && OverlayView.canDraw(this)) OverlayView.show(this);
        } catch (Throwable ignored) { }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        clearPermissionRequired(this);
        try { OverlayView.hide(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    static Notification build(Context ctx, String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        return b.setContentTitle("AI-ssistant")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    /** update the running notice, e.g. "Working \u00b7 step 3/12" */
    static void status(Context ctx, String text) {
        try { OverlayHub.setStatus(text); } catch (Throwable ignored) { }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build(ctx, text));
        } catch (Throwable ignored) { }
    }

    // ---- permission needed notice ----------------------------------------------------------
    private static final String CH_PERMISSION = "permission";
    private static final int ID_PERMISSION = 3;

    /** Surface a paused approval when the chat is behind another app. */
    static void permissionRequired(Context ctx, String label, String command) {
        if (ctx == null || MainActivity.appVisible) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_PERMISSION) == null) {
                NotificationChannel c = new NotificationChannel(CH_PERMISSION, "Izin agent",
                        NotificationManager.IMPORTANCE_HIGH);
                c.setShowBadge(true);
                c.enableVibration(true);
                nm.createNotificationChannel(c);
            }
            String action = label == null || label.trim().isEmpty() ? "aksi agent" : label.trim();
            String line = notificationLine(command);
            String body = "Agent menunggu persetujuan: " + action;
            if (!line.isEmpty()) body += "\n$ " + line;
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, ID_PERMISSION, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CH_PERMISSION)
                    : new Notification.Builder(ctx);
            b.setContentTitle("AI-ssistant · perlu izin")
                    .setContentText("Tap untuk review: " + action)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            nm.notify(ID_PERMISSION, b.build());
        } catch (Throwable t) {
            android.util.Log.e("AIssistant", "permission notification failed: " + t);
        }
    }

    static void clearPermissionRequired(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID_PERMISSION);
        } catch (Throwable ignored) { }
    }

    private static String notificationLine(String raw) {
        String line = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
        return line.length() > 180 ? line.substring(0, 180) + "…" : line;
    }
    // ---- "task finished" notice -----------------------------------------------------------
    private static final String CH_DONE = "done";
    private static final int ID_DONE = 2;

    /** a run ended while the user was elsewhere: tell them, with the gist of the result */
    static void done(Context ctx, String title, String summary) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_DONE) == null) {
                NotificationChannel c = new NotificationChannel(CH_DONE, "Task selesai",
                        NotificationManager.IMPORTANCE_DEFAULT);
                c.setShowBadge(true);
                c.enableVibration(true);
                nm.createNotificationChannel(c);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CH_DONE)
                    : new Notification.Builder(ctx);
            String body = summary == null || summary.trim().isEmpty()
                    ? "Agent sudah selesai bekerja." : summary.trim();
            b.setContentTitle(title == null || title.isEmpty() ? "AI-ssistant \u00b7 selesai" : title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.checkbox_on_background)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            nm.notify(ID_DONE, b.build());
            android.util.Log.i("AIssistant", "finished notification: " + body);
        } catch (Throwable t) {
            android.util.Log.e("AIssistant", "done notification failed: " + t);
        }
    }
}

