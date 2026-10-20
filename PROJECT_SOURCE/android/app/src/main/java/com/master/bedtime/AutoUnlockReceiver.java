package com.master.bedtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Fires the Child-local alarm at the Parent-selected Auto Unlock time. */
public final class AutoUnlockReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !AutoUnlockScheduler.ACTION_AUTO_UNLOCK.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("child", Context.MODE_PRIVATE);
        long expectedAt = intent.getLongExtra(AutoUnlockScheduler.EXTRA_UNLOCK_AT, 0L);
        long savedAt = sp.getLong(AutoUnlockScheduler.KEY_UNLOCK_AT, 0L);
        if (savedAt <= 0L || savedAt != expectedAt) return; // ignore an old/replaced alarm
        if (System.currentTimeMillis() < savedAt) {
            AutoUnlockScheduler.clearSchedule(app);
            AutoUnlockScheduler.schedule(app, savedAt);
            return;
        }
        if (!AutoUnlockScheduler.expireIfDue(app)) return;
        PendingResult pending = goAsync();
        AutoUnlockScheduler.syncCompletedUnlock(app, () -> {
            try { pending.finish(); } catch (Exception ignored) {}
        });
    }
}
