package com.master.bedtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class LocalParentService extends Service {
    private static final String TAG = "BedtimeParentService";
    private static final String CHANNEL = "bedtime_parent_local";
    private static final int NOTIFICATION_ID = 17;
    private LocalParentServer server;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        DailyAutoUnlock.scheduleNext(this);
        DailyAutoUnlock.applyParentIfDue(this);
        showForegroundNotification("Starting local Wi-Fi link…");
        ensureServer();
    }

    private void ensureServer() {
        if (server != null && server.isRunning()) return;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
        }
        server = new LocalParentServer(this);
        server.start();
        if (server.isRunning()) {
            Log.i(TAG, "Parent local server is listening on port " + LocalParentServer.PORT);
            showForegroundNotification("Ready for Child pairing on the same Wi-Fi");
        } else {
            Log.e(TAG, "Parent local server did not start; check port and network permissions");
            showForegroundNotification("Local link not ready — reopen the Parent dashboard to retry");
        }
    }

    private void showForegroundNotification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification n = new Notification.Builder(this, CHANNEL)
            .setContentTitle("Bedtime Parent local link")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Bedtime Parent Link", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public static void start(android.content.Context c) {
        Intent i = new Intent(c, LocalParentService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(android.content.Context c) {
        c.stopService(new Intent(c, LocalParentService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ensureServer();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.close();
        server = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
