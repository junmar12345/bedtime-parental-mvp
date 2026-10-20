package com.master.bedtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;
import com.master.bedtime.practice.BuildConfig;

import java.util.UUID;

public class BedtimeMonitorService extends Service {
    private static final String TAG = "BedtimeMonitor";
    private static final String CHANNEL = "bedtime_monitor";
    public static final String ACTION_CHILD_REPAIRED = "com.master.bedtime.CHILD_REPAIRED";
    public static final String ACTION_CHILD_STATE_CHANGED = "com.master.bedtime.CHILD_STATE_CHANGED";
    // A 2-second heartbeat is responsive to Parent commands without opening two
    // local HTTP requests every second as the previous 500 ms loop did.
    private static final long POLL_MS = 1500L;

    private volatile boolean running;
    private volatile boolean recoveringPair;
    private volatile Thread worker;
    private volatile LocalParentDiscovery discovery;
    private PowerManager.WakeLock wakeLock;
    private long lastCloudPollAt = 0L;
    private long lastCloudRegisterAttemptAt = 0L;

    private SharedPreferences sp() {
        return getSharedPreferences("child", MODE_PRIVATE);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification n = new Notification.Builder(this, CHANNEL)
            .setContentTitle("Bedtime monitor active")
            .setContentText("Local Wi-Fi primary; optional Cloud fallback when enabled")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(7, n);
        }

        DailyAutoUnlock.scheduleNext(this);
        DailyAutoUnlock.applyChildIfDue(this);
        AutoUnlockScheduler.restoreAfterBoot(this);
        AutoUnlockScheduler.expireIfDue(this);
        acquireWakeLock();
        if (sp().getBoolean("locked", false) && Settings.canDrawOverlays(this)) {
            BedtimeOverlay.show(this);
        }
        startWorkerIfNeeded();
    }

    private synchronized void startWorkerIfNeeded() {
        if (worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread(this::pollLoop, "bedtime-command-monitor");
        worker.start();
        Log.i(TAG, "Child monitor worker started/recovered");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Bedtime Monitor", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bedtime:ChildMonitor");
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire partial wake lock; foreground service will still run", e);
        }
    }

    private String ensureDeviceKey() {
        String key = sp().getString("deviceKey", "");
        if (key == null || key.trim().isEmpty()) {
            key = "device_" + UUID.randomUUID().toString().replace("-", "");
            sp().edit().putString("deviceKey", key).commit();
        }
        return key;
    }

    private void discoverParent() {
        synchronized (this) {
            if (!running || recoveringPair || discovery != null) return;
        }

        LocalParentDiscovery search = new LocalParentDiscovery(this, new LocalParentDiscovery.Listener() {
            @Override public void onSearching() {}

            @Override public void onFound(String baseUrl) {
                LocalParentDiscovery completed;
                synchronized (BedtimeMonitorService.this) {
                    recoveringPair = true;
                    completed = discovery;
                    discovery = null;
                }
                try { if (completed != null) completed.stop(); } catch (Exception ignored) {}
                // Re-register on the freshly discovered Parent; the old token is scoped
                // to the old Parent phone and cannot be used for the new local roster.
                new Thread(() -> registerWithParent(baseUrl), "bedtime-child-repair").start();
            }

            @Override public void onLost() {}

            @Override public void onFailed(String message) {
                LocalParentDiscovery failed;
                synchronized (BedtimeMonitorService.this) {
                    failed = discovery;
                    discovery = null;
                }
                try { if (failed != null) failed.stop(); } catch (Exception ignored) {}
                Log.w(TAG, "Parent discovery: " + message);
            }
        });

        synchronized (this) {
            if (!running || recoveringPair || discovery != null) {
                search.stop();
                return;
            }
            discovery = search;
        }
        search.start();
    }

    private void registerWithParent(String baseUrl) {
        try {
            String childName = sp().getString("name", "Child");
            if (childName == null || childName.trim().isEmpty()) childName = "Child";
            JSONObject registration = new JSONObject()
                .put("name", childName.trim())
                .put("deviceKey", ensureDeviceKey())
                .put("cloudFallbackEnabled", sp().getBoolean("cloudFallbackEnabled", !sp().getString("cloudToken", "").isEmpty()));
            String preferredCode = sp().getString("localCode", sp().getString("code", ""));
            if (preferredCode != null && preferredCode.matches("\\d{6}")) registration.put("code", preferredCode);
            JSONObject response = Net.request(
                "POST", baseUrl, "/local/child/register", null, null, registration
            );
            JSONObject child = response.getJSONObject("child");
            String newToken = response.getString("token");
            sp().edit()
                .putString("localToken", newToken)
                .putString("childId", child.optString("childId", ""))
                .putString("localServer", baseUrl)
                .putString("name", child.optString("name", childName.trim()))
                .putString("code", child.optString("code", ""))
                .putBoolean("paired", child.optBoolean("paired", false))
                .putBoolean("locked", child.optBoolean("locked", false))
                .remove("token")
                .remove("server")
                .commit();

            Log.i(TAG, "Child session registered with discovered Parent at " + baseUrl);
            Intent update = new Intent(ACTION_CHILD_REPAIRED);
            update.setPackage(getPackageName());
            sendBroadcast(update);
        } catch (Exception e) {
            Log.w(TAG, "Could not register Child with discovered Parent " + baseUrl, e);
        } finally {
            recoveringPair = false;
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                DailyAutoUnlock.applyChildIfDue(this);
                DailyAutoUnlock.syncCloudAutoUnlockIfDue(this);
                AutoUnlockScheduler.expireIfDue(this);
                AutoUnlockScheduler.syncCompletedUnlock(this);
                if (recoveringPair) {
                    sleep(POLL_MS);
                    continue;
                }

                SharedPreferences prefs = sp();
                String token = prefs.getString("localToken", "");
                String base = prefs.getString("localServer", "");
                boolean cloudFallbackEnabled = prefs.contains("cloudFallbackEnabled")
                    ? prefs.getBoolean("cloudFallbackEnabled", false)
                    : !prefs.getString("cloudToken", "").isEmpty();
                String cloudToken = cloudFallbackEnabled ? prefs.getString("cloudToken", "") : "";
                long now = System.currentTimeMillis();
                if (token.isEmpty() && cloudToken.isEmpty()) {
                    stopSelf();
                    return;
                }

                boolean localOk = false;
                boolean localLocked = prefs.getBoolean("locked", false);
                String localCommand = "UNLOCK";
                long localUnlockAt = 0L;
                boolean localPaired = prefs.getBoolean("paired", false);
                if (!token.isEmpty() && !base.isEmpty()) {
                    try {
                        JSONObject local = Net.request(
                            "POST", base, "/local/child/heartbeat",
                            "x-child-local-token", token,
                            new JSONObject().put("cloudFallbackEnabled", cloudFallbackEnabled),
                            900, 1200
                        );
                        localCommand = local.optString("command", "UNLOCK");
                        localUnlockAt = local.optLong("unlockAt", 0L);
                        localLocked = "LOCK".equalsIgnoreCase(localCommand);
                        localPaired = local.optBoolean("parentPaired", false);
                        localOk = true;
                        long localCommandAt = local.optLong("lastCommandAt", prefs.getLong("lastCommandAt", 0L));
                        AutoUnlockScheduler.noteCommandTimestamp(this, localCommandAt);
                        prefs.edit().putLong("localLastSeenAt", now)
                            .putLong("lastCommandAt", localCommandAt)
                            .putBoolean("paired", localPaired).apply();
                    } catch (Exception localError) {
                        Log.w(TAG, "Local Wi-Fi heartbeat failed; cloud fallback will be tried", localError);
                        discoverParent();
                    }
                } else if (token.isEmpty() || base.isEmpty()) {
                    // Even in cloud mode, continue looking for a nearby Parent so that
                    // local/offline communication becomes available when both share Wi-Fi.
                    discoverParent();
                }

                boolean cloudOk = false;
                boolean cloudLocked = prefs.getBoolean("locked", false);
                boolean cloudPaired = prefs.getBoolean("cloudPaired", false);
                long cloudCheckAt = System.currentTimeMillis();
                // Poll Cloud quickly while the local Parent is unreachable/unpaired. When
                // local Wi-Fi is healthy, keep Cloud as a low-frequency fallback check.
                long cloudPollInterval = (localOk && localPaired) ? 5000L : 1500L;
                if (!cloudToken.isEmpty() && cloudCheckAt - lastCloudPollAt >= cloudPollInterval) {
                    lastCloudPollAt = cloudCheckAt;
                    try {
                        JSONObject cloud = Net.request(
                            "POST", BuildConfig.CLOUD_API_URL, "/api/child/heartbeat",
                            "x-child-token", cloudToken, new JSONObject(),
                            3000, 3500
                        );
                        String cloudCommand = cloud.optString("command", "UNLOCK");
                        cloudLocked = "LOCK".equalsIgnoreCase(cloudCommand);
                        long cloudCommandAt = cloud.optLong("lastCommandAt", 0L);
                        AutoUnlockScheduler.noteCommandTimestamp(this, cloudCommandAt);
                        if (cloud.has("unlockAt")) {
                            cloudLocked = AutoUnlockScheduler.applyServerState(this, cloudCommand, cloud.optLong("unlockAt", 0L));
                        } else if (cloudLocked && AutoUnlockScheduler.shouldIgnoreStaleCloudLock(this, cloudCommandAt)) {
                            cloudLocked = false;
                        } else if (!cloudLocked && cloudCommandAt > prefs.getLong(AutoUnlockScheduler.KEY_UNLOCK_AT, 0L)) {
                            AutoUnlockScheduler.applyServerState(this, "UNLOCK", 0L);
                        }
                        // The cloud Worker returns parentId as null when unpaired.
                        cloudPaired = cloud.has("parentId")
                            ? (!cloud.isNull("parentId") && !cloud.optString("parentId", "").isEmpty())
                            : cloud.optBoolean("parentPaired", false);
                        prefs.edit()
                            .putLong("cloudLastSeenAt", now)
                            .putLong("lastCommandAt", Math.max(prefs.getLong("lastCommandAt", 0L), cloud.optLong("lastCommandAt", 0L)))
                            .putBoolean("cloudConnected", true)
                            .putBoolean("cloudPaired", cloudPaired)
                            .apply();
                        cloudOk = true;
                    } catch (Exception cloudError) {
                        prefs.edit().putBoolean("cloudConnected", false).apply();
                        Log.w(TAG, "Cloud heartbeat unavailable; using local Wi-Fi if reachable", cloudError);
                        // If cloud registration never succeeded while offline, retry using
                        // the already displayed local code so the Parent still pairs once.
                        maybeRegisterCloud(prefs, now);
                    }
                }

                // Local Wi-Fi is authoritative whenever the local Parent is reachable and
                // paired. Cloud is an optional fallback if local Wi-Fi is unavailable.
                if (localOk && localPaired) {
                    localLocked = AutoUnlockScheduler.applyServerState(this, localCommand, localUnlockAt);
                    applyLockState(localLocked);
                } else if (cloudFallbackEnabled && cloudOk && cloudPaired) {
                    applyLockState(cloudLocked);
                } else if (localOk) {
                    applyLockState(localLocked);
                } else if (cloudFallbackEnabled && cloudOk) {
                    // A cloud-only Child stays unlocked until the Parent pairs the code.
                    applyLockState(cloudLocked);
                }

                // Only retry Cloud registration when the user opted into Cloud fallback.
                if (cloudFallbackEnabled && cloudToken.isEmpty()) maybeRegisterCloud(prefs, now);
            } catch (Exception e) {
                Log.w(TAG, "Child monitor iteration failed", e);
            }
            sleep(POLL_MS);
        }
    }

    private void applyLockState(boolean locked) {
        sp().edit().putBoolean("locked", locked).apply();
        if (locked) {
            if (Settings.canDrawOverlays(this)) BedtimeOverlay.show(this);
        } else {
            BedtimeOverlay.hide(this);
        }
        Intent stateUpdate = new Intent(ACTION_CHILD_STATE_CHANGED);
        stateUpdate.setPackage(getPackageName());
        sendBroadcast(stateUpdate);
    }

    private void maybeRegisterCloud(SharedPreferences prefs, long now) {
        if (!prefs.getBoolean("cloudFallbackEnabled", !prefs.getString("cloudToken", "").isEmpty())) return;
        if (now - lastCloudRegisterAttemptAt < 30000L) return;
        String currentToken = prefs.getString("cloudToken", "");
        String currentCode = prefs.getString("localCode", prefs.getString("code", ""));
        if (!currentToken.isEmpty() || !currentCode.matches("\\d{6}")) return;
        lastCloudRegisterAttemptAt = now;
        try {
            String name = prefs.getString("name", "Child");
            JSONObject body = new JSONObject().put("name", name == null || name.trim().isEmpty() ? "Child" : name.trim())
                .put("code", currentCode)
                .put("deviceKey", ensureDeviceKey())
                .put("cloudFallbackEnabled", true);
            JSONObject response = Net.request("POST", BuildConfig.CLOUD_API_URL, "/api/child/register", null, null, body, 3000, 3500);
            JSONObject child = response.getJSONObject("child");
            String cloudToken = response.getString("token");
            String cloudCode = child.optString("code", currentCode);
            if (!currentCode.equals(cloudCode)) {
                Log.w(TAG, "Cloud code differs from local code; keeping the current local code to preserve offline pairing");
                return;
            }
            prefs.edit()
                .putString("cloudToken", cloudToken)
                .putString("cloudChildId", child.optString("childId", ""))
                .putString("cloudCode", cloudCode)
                .apply();
            Log.i(TAG, "Cloud registration completed using the existing local Child code");
        } catch (Exception e) {
            Log.w(TAG, "Cloud registration retry failed; local Wi-Fi mode remains available", e);
        }
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // If an OEM allows the Service to live but its worker thread has stopped,
        // restart the worker on the next service start request.
        startWorkerIfNeeded();
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Some OEM launchers stop foreground services when their recent-app task is
        // swiped away. Reassert the sticky service while a Child session still exists.
        boolean cloudEnabled = sp().contains("cloudFallbackEnabled")
            ? sp().getBoolean("cloudFallbackEnabled", false)
            : !sp().getString("cloudToken", "").isEmpty();
        if (!sp().getString("localToken", "").isEmpty()
            || (cloudEnabled && !sp().getString("cloudToken", "").isEmpty())) {
            try {
                Intent restart = new Intent(getApplicationContext(), BedtimeMonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart);
                else startService(restart);
            } catch (Exception e) {
                Log.w(TAG, "OEM task-removal restart request was rejected", e);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        Thread activeWorker = worker;
        if (activeWorker != null) activeWorker.interrupt();
        LocalParentDiscovery activeDiscovery = discovery;
        if (activeDiscovery != null) {
            try { activeDiscovery.stop(); } catch (Exception ignored) {}
            discovery = null;
        }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Exception ignored) {}
        wakeLock = null;
        BedtimeOverlay.hide(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
