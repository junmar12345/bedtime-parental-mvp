package com.master.bedtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BedtimeBootReceiver";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        DailyAutoUnlock.scheduleNext(context);
        int role = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .getInt("active_role", 0);
        try {
            if (role == 2) {
                // The current Child session uses localToken (not the legacy token key).
                android.content.SharedPreferences childPrefs = context.getSharedPreferences("child", Context.MODE_PRIVATE);
                String localToken = childPrefs.getString("localToken", "");
                boolean cloudEnabled = childPrefs.contains("cloudFallbackEnabled")
                    ? childPrefs.getBoolean("cloudFallbackEnabled", false)
                    : !childPrefs.getString("cloudToken", "").isEmpty();
                String cloudToken = cloudEnabled ? childPrefs.getString("cloudToken", "") : "";
                if (localToken.isEmpty() && cloudToken.isEmpty()) return;
                AutoUnlockScheduler.restoreAfterBoot(context);
                Intent service = new Intent(context, BedtimeMonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
                else context.startService(service);
                Log.i(TAG, "Restored Child monitor after boot/update");
            } else if (role == 1) {
                // Keep the Parent LAN endpoint available after reboot when this phone
                // was last configured as Parent and has a saved Parent session.
                String token = context.getSharedPreferences("parent", Context.MODE_PRIVATE)
                    .getString("token", "");
                if (token.isEmpty()) return;
                Intent service = new Intent(context, LocalParentService.class);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
                else context.startService(service);
                Log.i(TAG, "Restored Parent local link after boot/update");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not restore the selected role service", e);
        }
    }
}
