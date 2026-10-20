package com.master.bedtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;
import com.master.bedtime.practice.BuildConfig;

/** Alarm receiver for the daily 05:00 unlock. It uses standard Android APIs only. */
public class DailyAutoUnlockReceiver extends BroadcastReceiver {
    private static final String TAG = "BedtimeDailyReceiver";
    private static final String ACTION = "com.master.bedtime.DAILY_AUTO_UNLOCK";

    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        DailyAutoUnlock.scheduleNext(app);
        int role = app.getSharedPreferences("app_state", Context.MODE_PRIVATE).getInt("active_role", 0);
        if (role == 1) {
            DailyAutoUnlock.applyParentIfDue(app);
            return;
        }
        if (role != 2) return;

        DailyAutoUnlock.applyChildIfDue(app);
        // Best-effort heartbeat wakes the Parent-local route, which applies the same
        // date-guarded unlock to the Parent's saved roster before answering the request.
        SharedPreferences child = app.getSharedPreferences("child", Context.MODE_PRIVATE);
        String token = child.getString("localToken", "");
        String base = child.getString("localServer", "");
        boolean cloudEnabled = child.contains("cloudFallbackEnabled")
            ? child.getBoolean("cloudFallbackEnabled", false)
            : !child.getString("cloudToken", "").isEmpty();
        String cloudToken = cloudEnabled ? child.getString("cloudToken", "") : "";
        if (token.isEmpty() && cloudToken.isEmpty()) return;

        // Keep a sticky foreground monitor for cloud-only Child sessions too. This is
        // the fallback when there is no reachable local Parent server at 05:00.
        try {
            Intent service = new Intent(app, BedtimeMonitorService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(service);
            else app.startService(service);
        } catch (Exception e) {
            Log.w(TAG, "Could not start Child monitor after the daily alarm", e);
        }

        PendingResult pendingResult = goAsync();
        new PendingWork().start(app, base, token, cloudToken, pendingResult);
    }

    private static final class PendingWork {
        void start(Context context, String base, String token, String cloudToken, PendingResult pendingResult) {
            Thread worker = new Thread(() -> {
                SharedPreferences prefs = context.getSharedPreferences("child", Context.MODE_PRIVATE);
                try {
                    if (!base.isEmpty() && !token.isEmpty()) {
                        JSONObject response = Net.request(
                            "POST", base, "/local/child/heartbeat",
                            "x-child-local-token", token, new JSONObject()
                        );
                        boolean locked = "LOCK".equalsIgnoreCase(response.optString("command", "UNLOCK"));
                        prefs.edit()
                            .putBoolean("locked", locked)
                            .putBoolean("paired", response.optBoolean("parentPaired", false))
                            .putLong("lastCommandAt", response.optLong("lastCommandAt", prefs.getLong("lastCommandAt", 0L)))
                            .putLong("localLastSeenAt", System.currentTimeMillis())
                            .apply();
                        if (locked) {
                            if (Settings.canDrawOverlays(context)) BedtimeOverlay.show(context);
                        } else {
                            BedtimeOverlay.hide(context);
                        }
                    }
                } catch (Exception e) {
                    Log.i(TAG, "Parent not reachable at auto-unlock time; local Child unlock remains applied", e);
                }
                try {
                    String today = DailyAutoUnlock.todayKey();
                    if (!cloudToken.isEmpty() && !today.equals(prefs.getString("dailyAutoUnlockCloudSyncedDate", ""))) {
                        Net.request("POST", BuildConfig.CLOUD_API_URL, "/api/child/auto-unlock",
                            "x-child-token", cloudToken, new JSONObject());
                        prefs.edit().putString("dailyAutoUnlockCloudSyncedDate", today)
                            .putLong("cloudLastSeenAt", System.currentTimeMillis()).putBoolean("cloudConnected", true).apply();
                    }
                } catch (Exception e) {
                    Log.i(TAG, "Cloud daily unlock sync deferred; Child service will retry", e);
                }
                try {
                    Intent changed = new Intent(BedtimeMonitorService.ACTION_CHILD_STATE_CHANGED);
                    changed.setPackage(context.getPackageName());
                    context.sendBroadcast(changed);
                } catch (Exception ignored) {}
                try { pendingResult.finish(); } catch (Exception ignored) {}
            }, "bedtime-daily-unlock-sync");
            worker.start();
        }
    }
}
