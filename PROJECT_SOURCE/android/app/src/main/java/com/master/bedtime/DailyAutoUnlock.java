package com.master.bedtime;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import com.master.bedtime.practice.BuildConfig;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Daily 05:00 auto-unlock implemented with standard Android APIs.
 * No manufacturer/model checks are used, so the same logic applies to Android phones
 * from different vendors. A service-loop check complements AlarmManager if an alarm is deferred.
 */
public final class DailyAutoUnlock {
    private static final String TAG = "BedtimeDailyUnlock";
    private static final Object LOCK = new Object();
    private static final LocalTime UNLOCK_TIME = LocalTime.of(5, 0);
    private static final int ALARM_REQUEST_CODE = 50500;
    private static final String ACTION = "com.master.bedtime.DAILY_AUTO_UNLOCK";

    private DailyAutoUnlock() {}

    public static void scheduleNext(Context context) {
        try {
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) return;
            Intent intent = new Intent(context, DailyAutoUnlockReceiver.class).setAction(ACTION);
            PendingIntent pending = PendingIntent.getBroadcast(
                context.getApplicationContext(), ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            ZonedDateTime now = ZonedDateTime.now();
            ZonedDateTime next = now.toLocalDate().atTime(UNLOCK_TIME).atZone(now.getZone());
            if (!next.isAfter(now)) {
                next = now.toLocalDate().plusDays(1).atTime(UNLOCK_TIME).atZone(now.getZone());
            }
            long triggerAt = next.toInstant().toEpochMilli();
            // Inexact-while-idle avoids requiring the special exact-alarm access setting.
            // The active foreground service also checks the local clock on its poll loop.
            if (Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            else alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            Log.i(TAG, "Next daily auto-unlock scheduled for " + next);
        } catch (Exception e) {
            Log.w(TAG, "Could not schedule daily auto-unlock; service-loop check remains enabled", e);
        }
    }

    public static boolean isAfterUnlockTime() {
        return !LocalTime.now().isBefore(UNLOCK_TIME);
    }

    public static String todayKey() {
        return LocalDate.now().toString();
    }

    /** Unlock this Child phone locally once per device-local calendar day after 05:00. */
    public static boolean applyChildIfDue(Context context) {
        if (!isAfterUnlockTime()) return false;
        synchronized (LOCK) {
            SharedPreferences child = context.getSharedPreferences("child", Context.MODE_PRIVATE);
            String today = todayKey();
            if (today.equals(child.getString("dailyAutoUnlockDate", ""))) return false;

            long unlockInstant = ZonedDateTime.now().toLocalDate().atTime(UNLOCK_TIME)
                .atZone(ZonedDateTime.now().getZone()).toInstant().toEpochMilli();
            boolean locked = child.getBoolean("locked", false);
            long lastCommandAt = child.getLong("lastCommandAt", 0L);
            // If the Parent manually locked after today's 05:00 unlock time, do not
            // override that newer command when the Child service starts late.
            if (locked && lastCommandAt >= unlockInstant) {
                boolean marked = child.edit().putString("dailyAutoUnlockDate", today).commit();
                if (marked) Log.i(TAG, "Keeping a newer manual LOCK sent after 05:00 for " + today);
                return false;
            }

            boolean saved = child.edit()
                .putBoolean("locked", false)
                .putString("dailyAutoUnlockDate", today)
                .commit();
            if (!saved) return false;
            BedtimeOverlay.hide(context);
            Intent changed = new Intent(BedtimeMonitorService.ACTION_CHILD_STATE_CHANGED);
            changed.setPackage(context.getPackageName());
            context.sendBroadcast(changed);
            Log.i(TAG, "Child local lock cleared for " + today + " at/after 05:00");
            return true;
        }
    }

    /**
     * Keep cloud command state aligned with the local 05:00 unlock. The cloud Worker
     * must include /api/child/auto-unlock; failures are retried at one-minute intervals
     * while the foreground service is running. Local unlock does not depend on cloud.
     */
    public static void syncCloudAutoUnlockIfDue(Context context) {
        if (!isAfterUnlockTime()) return;
        SharedPreferences child = context.getSharedPreferences("child", Context.MODE_PRIVATE);
        boolean cloudEnabled = child.contains("cloudFallbackEnabled")
            ? child.getBoolean("cloudFallbackEnabled", false)
            : !child.getString("cloudToken", "").isEmpty();
        if (!cloudEnabled) return;
        String today = todayKey();
        if (!today.equals(child.getString("dailyAutoUnlockDate", ""))) return;
        if (today.equals(child.getString("dailyAutoUnlockCloudSyncedDate", ""))) return;
        long now = System.currentTimeMillis();
        long lastAttempt = child.getLong("dailyAutoUnlockCloudLastAttemptAt", 0L);
        if (now - lastAttempt < 60000L) return;
        String token = child.getString("cloudToken", "");
        if (token == null || token.isEmpty()) return;
        if (!child.edit().putLong("dailyAutoUnlockCloudLastAttemptAt", now).commit()) return;
        new Thread(() -> {
            try {
                Net.request("POST", BuildConfig.CLOUD_API_URL, "/api/child/auto-unlock",
                    "x-child-token", token, new JSONObject());
                child.edit()
                    .putString("dailyAutoUnlockCloudSyncedDate", today)
                    .putBoolean("cloudConnected", true)
                    .putLong("cloudLastSeenAt", System.currentTimeMillis())
                    .apply();
                Log.i(TAG, "Daily auto-unlock synced to cloud for " + today);
            } catch (Exception e) {
                Log.w(TAG, "Cloud daily auto-unlock sync failed; local state remains unlocked and cloud sync will retry", e);
            }
        }, "bedtime-cloud-daily-unlock").start();
    }

    /**
     * Unlock every Parent-local roster entry once per calendar day after 05:00.
     * This keeps the next heartbeat from immediately re-locking a Child with stale Parent state.
     */
    public static boolean applyParentIfDue(Context context) {
        if (!isAfterUnlockTime()) return false;
        synchronized (LOCK) {
            SharedPreferences parent = context.getSharedPreferences("parent", Context.MODE_PRIVATE);
            String parentId = parent.getString("parentId", "");
            if (parentId == null) parentId = "";
            String suffix = parentId.trim().isEmpty() ? "" : "_" + parentId.trim();
            String childrenKey = "children_json" + (suffix.isEmpty() ? "" : suffix);
            String dateKey = "dailyAutoUnlockDate" + suffix;
            String today = todayKey();

            SharedPreferences roster = context.getSharedPreferences("parent_local", Context.MODE_PRIVATE);
            if (today.equals(roster.getString(dateKey, ""))) return false;

            String raw = roster.getString(childrenKey, null);
            if (raw == null) {
                // Match the Parent server's non-destructive legacy roster migration behavior.
                raw = roster.getString("children_json", "[]");
            }
            JSONArray children;
            try { children = new JSONArray(raw == null ? "[]" : raw); }
            catch (Exception e) {
                Log.w(TAG, "Cannot process Parent roster for daily unlock; retrying on next request", e);
                return false;
            }

            long unlockInstant = ZonedDateTime.now().toLocalDate().atTime(UNLOCK_TIME)
                .atZone(ZonedDateTime.now().getZone()).toInstant().toEpochMilli();
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child == null) continue;
                boolean locked = child.optBoolean("locked", false);
                long lastCommandAt = child.optLong("lastCommandAt", 0L);
                if (!(locked && lastCommandAt >= unlockInstant)) {
                    try { child.put("command", "UNLOCK"); } catch (Exception ignored) {}
                    try { child.put("locked", false); } catch (Exception ignored) {}
                }
                try { child.put("lastAutoUnlockDate", today); } catch (Exception ignored) {}
            }
            boolean saved = roster.edit()
                .putString(childrenKey, children.toString())
                .putString(dateKey, today)
                .commit();
            if (saved) Log.i(TAG, "Parent roster auto-unlocked for " + today + " at/after 05:00");
            return saved;
        }
    }
}
