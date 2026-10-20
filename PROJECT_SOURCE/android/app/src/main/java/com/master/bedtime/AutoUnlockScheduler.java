package com.master.bedtime;

import com.master.bedtime.practice.BuildConfig;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.net.Uri;
import android.provider.Settings;
import org.json.JSONObject;

/**
 * Child-local Auto Unlock scheduler. The Parent sends the selected timestamp
 * over local Wi-Fi once; the Child then owns the alarm so Parent availability
 * is not required when the unlock time arrives.
 */
public final class AutoUnlockScheduler {
    public static final String KEY_UNLOCK_AT = "scheduledUnlockAt";
    public static final String KEY_EXACT_PERMISSION_MISSING = "autoUnlockExactPermissionMissing";
    public static final String KEY_PERMISSION_PROMPT_PENDING = "autoUnlockPermissionPromptPending";
    public static final String KEY_LAST_COMPLETED_AT = "scheduledUnlockCompletedAt";
    private static final String KEY_LAST_SYNC_ATTEMPT_AT = "scheduledUnlockLastSyncAttemptAt";
    private static final String KEY_LOCAL_SYNCED_AT = "scheduledUnlockLocalSyncedAt";
    private static final String KEY_CLOUD_SYNCED_AT = "scheduledUnlockCloudSyncedAt";
    public static final String ACTION_AUTO_UNLOCK = "com.master.bedtime.action.AUTO_UNLOCK";
    public static final String EXTRA_UNLOCK_AT = "unlockAt";

    private static final String CHILD_PREFS = "child";
    private static final String CHANNEL_ID = "bedtime_auto_unlock_permission";
    private static final int ALARM_REQUEST_CODE = 7631;
    private static final int NOTIFICATION_ID = 7632;

    private AutoUnlockScheduler() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(CHILD_PREFS, Context.MODE_PRIVATE);
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    private static PendingIntent alarmIntent(Context context, long unlockAt) {
        Intent intent = new Intent(context, AutoUnlockReceiver.class)
                .setAction(ACTION_AUTO_UNLOCK)
                .putExtra(EXTRA_UNLOCK_AT, unlockAt);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);
    }

    public static void schedule(Context context, long unlockAt) {
        Context app = context.getApplicationContext();
        if (unlockAt <= System.currentTimeMillis()) {
            expireIfDue(app);
            return;
        }

        SharedPreferences sp = prefs(app);
        long previous = sp.getLong(KEY_UNLOCK_AT, 0L);
        boolean exactAllowed = canScheduleExactAlarms(app);
        boolean previousMissingPermission = sp.getBoolean(KEY_EXACT_PERMISSION_MISSING, false);
        boolean shouldReschedule = previous != unlockAt
                || (exactAllowed && previousMissingPermission)
                || (!exactAllowed && !previousMissingPermission);
        sp.edit().putLong(KEY_UNLOCK_AT, unlockAt).apply();
        if (!shouldReschedule) return;

        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = alarmIntent(app, unlockAt);
        boolean missingPermission = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAllowed) {
                // Fallback still schedules an idle-capable alarm, but Android may defer it.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, unlockAt, pending);
                missingPermission = true;
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, unlockAt, pending);
            }
        } catch (SecurityException denied) {
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, unlockAt, pending);
            } catch (Exception ignored) {}
            missingPermission = true;
        } catch (Exception ignored) {
            missingPermission = true;
        }

        sp.edit()
                .putBoolean(KEY_EXACT_PERMISSION_MISSING, missingPermission)
                .putBoolean(KEY_PERMISSION_PROMPT_PENDING, missingPermission)
                .apply();
        if (missingPermission) postPermissionNotification(app);
        else {
            try {
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIFICATION_ID);
            } catch (Exception ignored) {}
        }
    }

    public static void cancelAlarm(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = alarmIntent(app, 0L);
        if (manager != null) manager.cancel(pending);
        pending.cancel();
    }

    public static void clearSchedule(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences sp = prefs(app);
        long previousAt = sp.getLong(KEY_UNLOCK_AT, 0L);
        boolean permissionWasMissing = sp.getBoolean(KEY_EXACT_PERMISSION_MISSING, false);
        boolean promptWasPending = sp.getBoolean(KEY_PERMISSION_PROMPT_PENDING, false);
        if (previousAt <= 0L && !permissionWasMissing && !promptWasPending) return;
        if (previousAt > 0L) cancelAlarm(app);
        sp.edit()
                .putLong(KEY_UNLOCK_AT, 0L)
                .putBoolean(KEY_EXACT_PERMISSION_MISSING, false)
                .putBoolean(KEY_PERMISSION_PROMPT_PENDING, false)
                .apply();
        if (permissionWasMissing || promptWasPending) {
            try {
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIFICATION_ID);
            } catch (Exception ignored) {}
        }
    }

    /** Returns true when a saved scheduled time expired and locally unlocks the Child. */
    public static boolean expireIfDue(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences sp = prefs(app);
        long at = sp.getLong(KEY_UNLOCK_AT, 0L);
        if (at <= 0L || System.currentTimeMillis() < at) return false;
        clearSchedule(app);
        sp.edit().putBoolean("locked", false).putLong(KEY_LAST_COMPLETED_AT, at).commit();
        BedtimeOverlay.hide(app);
        return true;
    }

    /** Clear the completed marker only after the Parent issues a newer command. */
    public static void noteCommandTimestamp(Context context, long commandAt) {
        SharedPreferences sp = prefs(context);
        long completedAt = sp.getLong(KEY_LAST_COMPLETED_AT, 0L);
        if (completedAt > 0L && commandAt > completedAt) {
            sp.edit().putLong(KEY_LAST_COMPLETED_AT, 0L).apply();
        }
    }

    /** Prevent an old Cloud LOCK from re-locking a Child after the scheduled local unlock. */
    public static boolean shouldIgnoreStaleCloudLock(Context context, long cloudLastCommandAt) {
        long completedAt = prefs(context).getLong(KEY_LAST_COMPLETED_AT, 0L);
        return completedAt > 0L && (cloudLastCommandAt <= 0L || cloudLastCommandAt <= completedAt);
    }

    /** Best-effort sync to the Parent's local roster and the existing Child auto-unlock API. */
    public static void syncCompletedUnlock(Context context) {
        syncCompletedUnlock(context, null);
    }

    public static void syncCompletedUnlock(Context context, Runnable onFinished) {
        Context app = context.getApplicationContext();
        SharedPreferences sp = prefs(app);
        long completedAt = sp.getLong(KEY_LAST_COMPLETED_AT, 0L);
        if (completedAt <= 0L) {
            finish(onFinished);
            return;
        }
        String localBase = sp.getString("localServer", "");
        String localToken = sp.getString("localToken", "");
        boolean cloudEnabled = sp.contains("cloudFallbackEnabled")
            ? sp.getBoolean("cloudFallbackEnabled", false)
            : !sp.getString("cloudToken", "").isEmpty();
        String cloudToken = cloudEnabled ? sp.getString("cloudToken", "") : "";
        boolean needLocal = !localBase.isEmpty() && !localToken.isEmpty()
            && sp.getLong(KEY_LOCAL_SYNCED_AT, 0L) < completedAt;
        boolean needCloud = !cloudToken.isEmpty()
            && sp.getLong(KEY_CLOUD_SYNCED_AT, 0L) < completedAt;
        if (!needLocal && !needCloud) {
            finish(onFinished);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sp.getLong(KEY_LAST_SYNC_ATTEMPT_AT, 0L) < 60000L) {
            finish(onFinished);
            return;
        }
        sp.edit().putLong(KEY_LAST_SYNC_ATTEMPT_AT, now).apply();
        new Thread(() -> {
            try {
                if (needLocal) {
                    try {
                        Net.request("POST", localBase, "/local/child/heartbeat",
                            "x-child-local-token", localToken, new JSONObject(), 900, 1200);
                        sp.edit().putLong(KEY_LOCAL_SYNCED_AT, completedAt).apply();
                    } catch (Exception e) {
                        android.util.Log.w("BedtimeAutoUnlock", "Local unlock sync will retry", e);
                    }
                }
                if (needCloud) {
                    try {
                        JSONObject completionBody = new JSONObject()
                            .put("scheduledUnlockAt", completedAt);
                        Net.request("POST", BuildConfig.CLOUD_API_URL, "/api/child/auto-unlock",
                            "x-child-token", cloudToken, completionBody, 3000, 3500);
                        sp.edit().putLong(KEY_CLOUD_SYNCED_AT, completedAt).apply();
                    } catch (Exception e) {
                        android.util.Log.w("BedtimeAutoUnlock", "Cloud unlock sync will retry if the endpoint is available", e);
                    }
                }
            } finally {
                finish(onFinished);
            }
        }, "bedtime-manual-unlock-sync").start();
    }

    private static void finish(Runnable callback) {
        if (callback != null) {
            try { callback.run(); } catch (Exception ignored) {}
        }
    }

    /** Applies Parent state and installs/cancels the alarm using the timestamp from the LAN response. */
    public static boolean applyServerState(Context context, String command, long unlockAt) {
        Context app = context.getApplicationContext();
        String normalized = command == null ? "UNLOCK" : command.trim().toUpperCase(java.util.Locale.US);
        SharedPreferences sp = prefs(app);
        if (!"LOCK".equals(normalized)) {
            clearSchedule(app);
            sp.edit().putBoolean("locked", false).apply();
            BedtimeOverlay.hide(app);
            return false;
        }

        if (unlockAt > 0L && unlockAt <= System.currentTimeMillis()) {
            clearSchedule(app);
            sp.edit().putBoolean("locked", false).putLong(KEY_LAST_COMPLETED_AT, unlockAt).commit();
            BedtimeOverlay.hide(app);
            return false;
        }

        if (unlockAt > 0L) sp.edit().putLong(KEY_LAST_COMPLETED_AT, 0L).apply();
        sp.edit().putBoolean("locked", true).apply();
        if (unlockAt > 0L) schedule(app, unlockAt);
        else clearSchedule(app); // ordinary manual LOCK has no timer
        if (android.provider.Settings.canDrawOverlays(app)) BedtimeOverlay.show(app);
        return true;
    }

    /** Restore the Child-local alarm after a reboot or app replacement. */
    public static void restoreAfterBoot(Context context) {
        Context app = context.getApplicationContext();
        long at = prefs(app).getLong(KEY_UNLOCK_AT, 0L);
        if (at > 0L && System.currentTimeMillis() >= at) {
            expireIfDue(app);
        } else if (at > 0L && prefs(app).getBoolean("locked", false)) {
            // Force a new PendingIntent/AlarmManager registration after boot.
            prefs(app).edit().putBoolean(KEY_EXACT_PERMISSION_MISSING, !canScheduleExactAlarms(app)).apply();
            long saved = prefs(app).getLong(KEY_UNLOCK_AT, 0L);
            prefs(app).edit().putLong(KEY_UNLOCK_AT, 0L).apply();
            schedule(app, saved);
        }
    }

    private static void postPermissionNotification(Context context) {
        Context app = context.getApplicationContext();
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Auto Unlock Permission", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Helps Bedtime unlock the Child at the selected time while the Parent is offline.");
                nm.createNotificationChannel(channel);
            }
            Intent open;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + app.getPackageName()));
            } else {
                open = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + app.getPackageName()));
            }
            PendingIntent content = PendingIntent.getActivity(app, NOTIFICATION_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(app, CHANNEL_ID)
                        .setContentTitle("Allow reliable Auto Unlock")
                        .setContentText("Tap to allow Alarms & reminders for the scheduled unlock time.")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentIntent(content)
                        .setAutoCancel(true)
                        .build();
            } else {
                notification = new Notification.Builder(app)
                        .setContentTitle("Allow reliable Auto Unlock")
                        .setContentText("Tap to allow Alarms & reminders for the scheduled unlock time.")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentIntent(content)
                        .setAutoCancel(true)
                        .build();
            }
            nm.notify(NOTIFICATION_ID, notification);
        } catch (Exception ignored) {}
    }
}
