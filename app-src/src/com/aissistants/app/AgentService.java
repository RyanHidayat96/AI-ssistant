package com.aissistants.app;

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
 * the task drives OTHER apps (open WhatsApp, tap, type): AI-ssistants goes to the background and
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
        return START_NOT_STICKY;
    }

    static Notification build(Context ctx, String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        return b.setContentTitle("AI-ssistants")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    /** update the running notice, e.g. "Working \u00b7 step 3/12" */
    static void status(Context ctx, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build(ctx, text));
        } catch (Throwable ignored) { }
    }
}
