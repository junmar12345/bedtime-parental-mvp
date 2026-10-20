package com.master.bedtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import org.json.JSONObject;
import com.master.bedtime.practice.BuildConfig;

import java.util.UUID;

public class ChildActivity extends Activity {
    private EditText name;
    private TextView code, status, state, stateIcon, connection, discoveryStatus;
    private CheckBox cloudFallbackCheck;
    private Button cloudFallbackButton;
    private String localToken = "", childId = "", localServer = "";
    private LocalParentDiscovery discovery;
    private boolean childRepairReceiverRegistered;
    private final BroadcastReceiver childRepairReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if (BedtimeMonitorService.ACTION_CHILD_REPAIRED.equals(action)) {
                localToken = sp().getString("localToken", "");
                childId = sp().getString("childId", "");
                localServer = sp().getString("localServer", "");
                if (code != null) code.setText(sp().getString("code", "------"));
                if (connection != null) connection.setText("🟢 LOCAL WI-FI CONNECTED");
            }
            // Lock-state broadcasts refresh both status and whichever connection is healthy.
            updateStatus();
            refreshConnectionLabel();
        }
    };

    private android.content.SharedPreferences sp(){
        return getSharedPreferences("child", MODE_PRIVATE);
    }

    @Override protected void onStart() {
        super.onStart();
        try {
            IntentFilter filter = new IntentFilter(BedtimeMonitorService.ACTION_CHILD_REPAIRED);
            filter.addAction(BedtimeMonitorService.ACTION_CHILD_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(childRepairReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(childRepairReceiver, filter);
            }
            childRepairReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    @Override protected void onStop() {
        if (childRepairReceiverRegistered) {
            try { unregisterReceiver(childRepairReceiver); } catch (Exception ignored) {}
            childRepairReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        localToken = sp().getString("localToken", "");
        childId = sp().getString("childId", "");
        localServer = sp().getString("localServer", "");
        if(localToken.isEmpty() && sp().getString("cloudToken", "").isEmpty()) showSetup(); else showChild();
    }

    @Override protected void onResume(){
        super.onResume();
        localToken = sp().getString("localToken", "");
        localServer = sp().getString("localServer", "");
        if(!localToken.isEmpty() || !sp().getString("cloudToken", "").isEmpty()){
            startMonitor();
            checkLocalConnection();
        } else if(discovery == null) {
            // showSetup() already starts discovery on first launch. Restart it only
            // after returning from another app/screen, not on the same initial resume.
            startDiscovery();
        }
    }

    @Override protected void onPause(){
        super.onPause();
        stopDiscovery();
    }

    private TextView text(String s, float size, int color){
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(0, 6, 0, 6);
        return v;
    }

    private LinearLayout column(){
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(22, 24, 22, 24);
        r.setBackgroundColor(Color.rgb(246, 248, 252));
        scroll.addView(r, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        return r;
    }

    private GradientDrawable bg(int color, float radius){
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(radius); return g;
    }

    private LinearLayout card(){
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(22, 20, 22, 20);
        c.setBackground(bg(Color.WHITE, 34));
        c.setElevation(5);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 10, 0, 10);
        c.setLayoutParams(p);
        return c;
    }

    private Button button(String s){
        Button b = new Button(this);
        b.setText(s); b.setTextSize(14); b.setAllCaps(false);
        return b;
    }

    private void showSetup(){
        stopDiscovery();
        LinearLayout r = column();

        TextView moon = text("🌙", 54, Color.rgb(38, 52, 88));
        moon.setGravity(Gravity.CENTER); r.addView(moon);
        TextView title = text("Connect to Parent", 28, Color.rgb(30, 42, 70));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setGravity(Gravity.CENTER); r.addView(title);
        TextView sub = text("Local Wi-Fi is primary. The app can switch to Cloud automatically when the local Parent cannot be reached.", 16, Color.rgb(102, 113, 135));
        sub.setGravity(Gravity.CENTER); r.addView(sub);

        LinearLayout c = card();
        TextView h = text("👋  CHILD SETUP", 19, Color.rgb(45, 57, 82));
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD); c.addView(h);
        c.addView(text("Local Wi-Fi is used first whenever the Parent is reachable. If not, Cloud fallback can connect over the internet. Both phones need internet for Cloud.", 15, Color.rgb(95, 105, 125)));

        cloudFallbackCheck = new CheckBox(this);
        cloudFallbackCheck.setText("☁️ Enable automatic Cloud fallback");
        cloudFallbackCheck.setTextSize(15);
        boolean cloudDefault = sp().contains("cloudFallbackEnabled")
            ? sp().getBoolean("cloudFallbackEnabled", true)
            : true;
        cloudFallbackCheck.setChecked(cloudDefault);
        c.addView(cloudFallbackCheck);
        c.addView(text("Recommended: ON. Local Wi-Fi stays primary; Cloud is used only when local control is unavailable. Turning this off keeps the Child local-only.", 12, Color.rgb(120, 129, 147)));

        name = new EditText(this);
        name.setHint("Child name"); name.setSingleLine(true);
        String savedName = sp().getString("name", "");
        if(!savedName.isEmpty()) name.setText(savedName);
        c.addView(name);

        discoveryStatus = text("🔎 Searching for Parent on this Wi-Fi…", 14, Color.rgb(95, 105, 125));
        discoveryStatus.setGravity(Gravity.CENTER);
        c.addView(discoveryStatus);

        Button retry = button("🔄  FIND PARENT AGAIN");
        c.addView(retry);
        retry.setOnClickListener(v -> startDiscovery());

        Button create = button("✨  CREATE MY CODE");
        c.addView(create);
        status = text("", 14, Color.rgb(95, 105, 125)); status.setGravity(Gravity.CENTER); c.addView(status);
        Button roleChange = button("🔁  Change Role");
        c.addView(roleChange);
        roleChange.setOnClickListener(v -> confirmChangeRole());
        r.addView(c);
        create.setOnClickListener(v -> register());

        startDiscovery();
    }

    private String deviceKey(){
        String k = sp().getString("deviceKey", "");
        if(k.isEmpty()){
            k = "device_" + UUID.randomUUID().toString().replace("-", "");
            sp().edit().putString("deviceKey", k).apply();
        }
        return k;
    }

    private void startDiscovery(){
        stopDiscovery();
        discovery = new LocalParentDiscovery(this, new LocalParentDiscovery.Listener() {
            @Override public void onSearching() {
                runOnUiThread(() -> {
                    if(discoveryStatus != null) discoveryStatus.setText("🔎 Searching for Parent on this Wi-Fi…");
                });
            }
            @Override public void onFound(String baseUrl) {
                localServer = baseUrl;
                sp().edit().putString("localServer", baseUrl).apply();
                runOnUiThread(() -> {
                    if(discoveryStatus != null) discoveryStatus.setText("🟢 Parent found. Ready to connect.");
                    if(connection != null) connection.setText("🟢 LOCAL WI-FI CONNECTED");
                });
            }
            @Override public void onLost() {
                runOnUiThread(() -> {
                    if(discoveryStatus != null) discoveryStatus.setText("🟡 Parent not found on this Wi-Fi. Cloud fallback is optional.");
                    if(connection != null) connection.setText(cloudRecentlySeen() ? "☁️ CLOUD CONNECTED • LOCAL WI-FI UNAVAILABLE" : "🟡 SEARCHING FOR PARENT…");
                });
            }
            @Override public void onFailed(String message) {
                runOnUiThread(() -> {
                    if(discoveryStatus != null) discoveryStatus.setText("⚠️ " + message);
                    if(connection != null) connection.setText("⚠️ PARENT NOT FOUND YET");
                });
            }
        });
        discovery.start();
    }

    private void stopDiscovery(){
        if(discovery != null){
            discovery.stop();
            discovery = null;
        }
    }

    private boolean cloudFallbackEnabled() {
        if (sp().contains("cloudFallbackEnabled")) return sp().getBoolean("cloudFallbackEnabled", true);
        // Local Wi-Fi remains primary; new installs enable Cloud fallback so control
        // can automatically continue when the Parent is not reachable on this LAN.
        return true;
    }

    private void register(){
        String childName = name == null ? "" : name.getText().toString().trim();
        if(childName.isEmpty()){
            status.setText("Please enter your name first.");
            return;
        }
        final boolean requestCloud = cloudFallbackCheck != null
            ? cloudFallbackCheck.isChecked() : cloudFallbackEnabled();
        sp().edit().putBoolean("cloudFallbackEnabled", requestCloud).apply();
        status.setText(requestCloud
            ? "Setting up Local Wi-Fi + optional Cloud fallback…"
            : "Setting up Local Wi-Fi connection…");
        final String base = localServer == null ? "" : localServer.trim();
        new Thread(() -> {
            String newLocalToken = "";
            String newLocalChildId = "";
            String newLocalCode = "";
            String newCloudToken = sp().getString("cloudToken", "");
            String newCloudChildId = sp().getString("cloudChildId", "");
            String newCloudCode = sp().getString("cloudCode", "");
            String localError = "";
            String cloudError = "";
            String preferredLocalCode = sp().getString("localCode", "");
            if (!preferredLocalCode.matches("\\d{6}")) {
                String oldCode = sp().getString("code", "");
                if (oldCode.matches("\\d{6}")) preferredLocalCode = oldCode;
            }

            // Local Wi-Fi remains the primary path and is always attempted first.
            if(!base.isEmpty()){
                try {
                    JSONObject localBody = new JSONObject()
                        .put("name", childName)
                        .put("deviceKey", deviceKey())
                        .put("cloudFallbackEnabled", requestCloud);
                    if(preferredLocalCode.matches("\\d{6}")) localBody.put("code", preferredLocalCode);
                    JSONObject localResponse = Net.request("POST", base, "/local/child/register", null, null, localBody);
                    JSONObject localChild = localResponse.getJSONObject("child");
                    newLocalToken = localResponse.getString("token");
                    newLocalChildId = localChild.optString("childId", "");
                    newLocalCode = localChild.optString("code", "");
                    sp().edit()
                        .putString("localToken", newLocalToken)
                        .putString("childId", newLocalChildId)
                        .putString("localServer", base)
                        .putString("name", localChild.optString("name", childName))
                        .putString("localCode", newLocalCode)
                        .putString("code", newLocalCode)
                        .putBoolean("paired", localChild.optBoolean("paired", false))
                        .putBoolean("locked", localChild.optBoolean("locked", false))
                        .commit();
                } catch(Exception e) {
                    localError = e.getMessage() == null ? "Local registration failed" : e.getMessage();
                }
            } else {
                localError = "Parent not found on this Wi-Fi";
            }

            // Local Wi-Fi remains primary. Cloud registration supports automatic fallback
            // and also lets the Parent sync billing metadata without routing LAN commands to Cloud.
            if(requestCloud && (newCloudToken.isEmpty() || newCloudCode.isEmpty())){
                try {
                    JSONObject cloudBody = new JSONObject()
                        .put("name", childName)
                        .put("deviceKey", deviceKey());
                    String preferredCode = !newLocalCode.isEmpty() ? newLocalCode : preferredLocalCode;
                    if(preferredCode.matches("\\d{6}")) cloudBody.put("code", preferredCode);
                    JSONObject cloudResponse = Net.request("POST", BuildConfig.CLOUD_API_URL,
                        "/api/child/register", null, null, cloudBody);
                    JSONObject cloudChild = cloudResponse.getJSONObject("child");
                    newCloudToken = cloudResponse.getString("token");
                    newCloudChildId = cloudChild.optString("childId", "");
                    newCloudCode = cloudChild.optString("code", "");
                    sp().edit()
                        .putString("cloudToken", newCloudToken)
                        .putString("cloudChildId", newCloudChildId)
                        .putString("cloudCode", newCloudCode)
                        .commit();
                } catch(Exception e) {
                    cloudError = e.getMessage() == null ? "Cloud fallback could not be registered" : e.getMessage();
                }
            }

            boolean cloudConfigured = requestCloud && cloudError.isEmpty()
                && !newCloudToken.isEmpty() && newCloudCode.matches("\\d{6}");
            boolean codeCompatible = newLocalToken.isEmpty() || !cloudConfigured
                || newLocalCode.isEmpty() || newLocalCode.equals(newCloudCode);
            if (cloudConfigured && !codeCompatible) {
                cloudError = "Cloud code does not match the local code. Cloud fallback was disabled to avoid duplicate Child entries.";
            }

            if(newLocalToken.isEmpty() && (!requestCloud || newCloudToken.isEmpty())){
                String details = !localError.isEmpty() ? localError : cloudError;
                if(requestCloud && !cloudError.isEmpty()) details = cloudError;
                if(details.isEmpty()) details = "No Parent was found on this Wi-Fi. Cloud fallback will be attempted when enabled and internet is available.";
                final String errorText = details;
                runOnUiThread(() -> {
                    if(status != null) status.setText("Could not connect: " + errorText + ". Check your connection and retry.");
                    if(!base.isEmpty()) startDiscovery();
                });
                return;
            }

            String displayCode = !newLocalCode.isEmpty() ? newLocalCode : newCloudCode;
            if(displayCode.isEmpty()) displayCode = sp().getString("code", "------");
            final String finalDisplayCode = displayCode;
            final String finalLocalToken = newLocalToken;
            final String finalLocalChildId = newLocalChildId;
            final String finalLocalCode = newLocalCode;
            final String finalCloudToken = newCloudToken;
            final String finalCloudChildId = newCloudChildId;
            final String finalCloudCode = newCloudCode;
            final boolean finalCloudEnabled = requestCloud && cloudConfigured && codeCompatible;
            final String finalCloudError = cloudError;
            sp().edit()
                .putString("name", childName)
                .putString("code", finalDisplayCode)
                .putString("localCode", finalLocalCode.isEmpty() ? preferredLocalCode : finalLocalCode)
                .putString("localToken", finalLocalToken)
                .putString("childId", finalLocalChildId)
                .putString("localServer", finalLocalToken.isEmpty() ? "" : base)
                .putString("cloudToken", finalCloudToken)
                .putString("cloudChildId", finalCloudChildId)
                .putString("cloudCode", finalCloudCode)
                .putBoolean("cloudFallbackEnabled", finalCloudEnabled)
                .putBoolean("paired", sp().getBoolean("paired", false))
                .commit();

            runOnUiThread(() -> {
                localToken = finalLocalToken;
                childId = finalLocalChildId;
                localServer = sp().getString("localServer", "");
                showChild();
                if (connection != null && !finalLocalToken.isEmpty()) {
                    connection.setText(finalCloudEnabled
                        ? "🟢 LOCAL WI-FI PRIMARY • CLOUD FALLBACK ON"
                        : "🟢 LOCAL WI-FI ONLY");
                } else if (connection != null && !finalCloudToken.isEmpty() && finalCloudEnabled) {
                    connection.setText("☁️ CLOUD FALLBACK • INTERNET REQUIRED");
                }
                if (status != null && requestCloud && !finalCloudError.isEmpty()) {
                    status.setText("Local connection is saved. Cloud fallback could not be enabled yet: " + finalCloudError);
                }
            });
        }, "bedtime-register-local-optional-cloud").start();
    }

    private void showChild(){
        stopDiscovery();
        LinearLayout r = column();

        LinearLayout hero = card();
        hero.setGravity(Gravity.CENTER);
        TextView moon = text("🌙✨", 48, Color.rgb(49, 63, 99)); moon.setGravity(Gravity.CENTER); hero.addView(moon);
        TextView hello = text("Good evening, " + sp().getString("name", "there") + "!", 24, Color.rgb(35, 47, 76));
        hello.setTypeface(Typeface.DEFAULT, Typeface.BOLD); hello.setGravity(Gravity.CENTER); hero.addView(hello);
        connection = text("🟢 LOCAL WI-FI", 14, Color.rgb(46, 150, 91)); connection.setGravity(Gravity.CENTER); hero.addView(connection);
        r.addView(hero);

        // Keep the role switch near the top; the old button sat below multiple settings
        // and could be clipped on smaller phones because the page was not scrollable.
        Button changeRole = button("🔁  Change Role (Parent / Child)");
        r.addView(changeRole);
        changeRole.setOnClickListener(v -> confirmChangeRole());

        LinearLayout stateCard = card(); stateCard.setGravity(Gravity.CENTER);
        stateIcon = text(sp().getBoolean("locked", false) ? "🔒" : "🔓", 54, Color.rgb(45, 58, 90));
        stateIcon.setGravity(Gravity.CENTER); stateCard.addView(stateIcon);
        state = text(sp().getBoolean("locked", false) ? "BEDTIME LOCKED" : "YOU'RE FREE", 23, Color.rgb(35, 47, 76));
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD); state.setGravity(Gravity.CENTER); stateCard.addView(state);
        status = text("", 14, Color.rgb(102, 113, 135)); status.setGravity(Gravity.CENTER); stateCard.addView(status);
        r.addView(stateCard);

        LinearLayout codeCard = card();
        TextView codeTitle = text("🔑  MY CONNECTION", 16, Color.rgb(88, 99, 122));
        codeTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); codeCard.addView(codeTitle);
        code = text(sp().getString("code", "------"), 36, Color.rgb(39, 51, 83));
        code.setTypeface(Typeface.DEFAULT, Typeface.BOLD); code.setGravity(Gravity.CENTER); code.setLetterSpacing(.12f); codeCard.addView(code);
        TextView hint = text("Give this code to your Parent", 13, Color.rgb(120, 129, 147)); hint.setGravity(Gravity.CENTER); codeCard.addView(hint);
        r.addView(codeCard);

        LinearLayout actions = card();
        TextView settingsTitle = text("⚙️  SETTINGS", 16, Color.rgb(88, 99, 122));
        settingsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); actions.addView(settingsTitle);

        Button protection = button("🔒  App Protection / Hide App Settings");
        actions.addView(protection);
        protection.setOnClickListener(v -> showAppProtection());

        Button permission = button("🛡️  Bedtime Permission");
        actions.addView(permission);
        permission.setOnClickListener(v -> openOverlaySettings());

        Button re = button("⚙️  Change Parent");
        actions.addView(re);
        re.setOnClickListener(v -> confirmUnpair());

        cloudFallbackButton = button(cloudFallbackButtonLabel());
        actions.addView(cloudFallbackButton);
        cloudFallbackButton.setOnClickListener(v -> showCloudFallbackOptions());

        Button backgroundHelp = button("⚡  Background Reliability Settings");
        actions.addView(backgroundHelp);
        backgroundHelp.setOnClickListener(v -> showBackgroundReliabilityHelp());

        r.addView(actions);
        updateStatus(); startMonitor(); checkLocalConnection();
    }

    private String cloudFallbackButtonLabel() {
        return cloudFallbackEnabled() ? "☁️ Cloud Fallback: ON" : "☁️ Cloud Fallback: OFF (Local only)";
    }

    private void showCloudFallbackOptions() {
        String[] options = {
            "Local Wi-Fi only (Recommended)",
            "Local Wi-Fi + Cloud fallback (Internet required)"
        };
        int selected = cloudFallbackEnabled() ? 1 : 0;
        new AlertDialog.Builder(this)
            .setTitle("Connection Preference")
            .setSingleChoiceItems(options, selected, (dialog, which) -> {
                dialog.dismiss();
                boolean enable = which == 1;
                if (enable == cloudFallbackEnabled()) return;
                configureCloudFallback(enable);
            })
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private void configureCloudFallback(boolean enabled) {
        if (!enabled) {
            if (status != null) status.setText("Disabling Cloud fallback; keeping Local Wi-Fi pairing…");
            new Thread(() -> {
                String base = sp().getString("localServer", "");
                String localTokenValue = sp().getString("localToken", "");
                try {
                    if (!base.isEmpty() && !localTokenValue.isEmpty()) {
                        JSONObject localBody = new JSONObject().put("cloudFallbackEnabled", false);
                        Net.request("POST", base, "/local/child/heartbeat",
                            "x-child-local-token", localTokenValue, localBody);
                    }
                } catch (Exception localUpdateError) {
                    // The monitor will retry syncing this preference when local Wi-Fi is reachable.
                    android.util.Log.w("BedtimeChild", "Cloud disabled; local Parent preference sync will retry: " + localUpdateError.getMessage());
                }
                // Keep both pairing tokens intact. Turning Cloud off only changes routing preference.
                final String savedLocalToken = localTokenValue;
                sp().edit()
                    .putBoolean("cloudFallbackEnabled", false)
                    .putBoolean("cloudConnected", false)
                    .apply();
                runOnUiThread(() -> {
                    localToken = savedLocalToken;
                    if (cloudFallbackButton != null) cloudFallbackButton.setText(cloudFallbackButtonLabel());
                    refreshConnectionLabel();
                    boolean hasLocal = !savedLocalToken.isEmpty();
                    if (status != null) status.setText(hasLocal
                        ? "Cloud fallback is off. Local Wi-Fi remains the primary connection."
                        : "Cloud fallback is off. Reconnect to the Parent on Local Wi-Fi to control this Child.");
                    Toast.makeText(this, hasLocal
                        ? "Cloud fallback disabled. Local Wi-Fi pairing remains enabled."
                        : "Cloud disabled. This Child needs Local Wi-Fi pairing to remain connected.", Toast.LENGTH_LONG).show();
                });
            }, "bedtime-disable-cloud-fallback").start();
            return;
        }

        if (status != null) status.setText("Enabling optional Cloud fallback…");
        new Thread(() -> {
            String localCode = sp().getString("localCode", "");
            if (!localCode.matches("\\d{6}")) {
                String current = sp().getString("code", "");
                if (current.matches("\\d{6}")) localCode = current;
            }
            String cloudToken = sp().getString("cloudToken", "");
            String cloudChildId = sp().getString("cloudChildId", "");
            String cloudCode = sp().getString("cloudCode", "");
            String newLocalToken = sp().getString("localToken", "");
            String localBase = sp().getString("localServer", "");
            String errorText = "";
            try {
                if (cloudToken.isEmpty() || cloudCode.isEmpty()) {
                    JSONObject cloudBody = new JSONObject()
                        .put("name", sp().getString("name", "Child"))
                        .put("deviceKey", deviceKey());
                    if (localCode.matches("\\d{6}")) cloudBody.put("code", localCode);
                    JSONObject cloudResponse = Net.request("POST", BuildConfig.CLOUD_API_URL,
                        "/api/child/register", null, null, cloudBody);
                    JSONObject child = cloudResponse.getJSONObject("child");
                    cloudToken = cloudResponse.getString("token");
                    cloudChildId = child.optString("childId", "");
                    cloudCode = child.optString("code", "");
                }

                if (cloudToken.isEmpty() || !cloudCode.matches("\\d{6}")) {
                    throw new Exception("Cloud registration did not return a valid Child token and 6-digit code.");
                }
                if (!newLocalToken.isEmpty() && localCode.matches("\\d{6}")
                    && !cloudCode.equals(localCode)) {
                    throw new Exception("Cloud code does not match this local Child code. To avoid duplicate entries, keep Local Wi-Fi only for this pairing.");
                }

                // Advertise opt-in to the existing Parent-local roster without re-registering
                // the Child or rotating its local token. If the Parent is temporarily unreachable,
                // the monitor will retry this metadata sync in its next local heartbeat.
                if (!newLocalToken.isEmpty() && !localBase.isEmpty()) {
                    try {
                        JSONObject localBody = new JSONObject().put("cloudFallbackEnabled", true);
                        Net.request("POST", localBase, "/local/child/heartbeat",
                            "x-child-local-token", newLocalToken, localBody);
                    } catch (Exception localUpdateError) {
                        android.util.Log.w("BedtimeChild", "Cloud enabled; local Parent preference sync will retry: " + localUpdateError.getMessage());
                    }
                }

                final String savedLocalToken = newLocalToken;
                final String savedCloudToken = cloudToken;
                final String savedCloudChildId = cloudChildId;
                final String savedCloudCode = cloudCode;
                final String displayCode = localCode.matches("\\d{6}") ? localCode : cloudCode;
                sp().edit()
                    .putBoolean("cloudFallbackEnabled", true)
                    .putString("cloudToken", savedCloudToken)
                    .putString("cloudChildId", savedCloudChildId)
                    .putString("cloudCode", savedCloudCode)
                    .putString("localToken", savedLocalToken)
                    .putString("localCode", localCode)
                    .putString("code", displayCode)
                    .commit();
                runOnUiThread(() -> {
                    localToken = savedLocalToken;
                    localServer = sp().getString("localServer", "");
                    if (code != null) code.setText(displayCode);
                    if (cloudFallbackButton != null) cloudFallbackButton.setText(cloudFallbackButtonLabel());
                    if (status != null) status.setText("Cloud fallback is enabled. Local Wi-Fi remains the primary connection.");
                    startMonitor();
                    refreshConnectionLabel();
                    Toast.makeText(this, "Cloud fallback enabled. Local Wi-Fi remains primary.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                errorText = e.getMessage() == null ? "Cloud registration failed" : e.getMessage();
                final String message = errorText;
                runOnUiThread(() -> {
                    if (status != null) status.setText("Cloud fallback was not enabled: " + message);
                    Toast.makeText(this, "Cloud fallback not enabled. Local Wi-Fi is unchanged.", Toast.LENGTH_LONG).show();
                });
            }
        }, "bedtime-enable-cloud-fallback").start();
    }

    private void checkLocalConnection(){
        String base = sp().getString("localServer", "");
        if(base.isEmpty()){
            if(connection != null) connection.setText(cloudRecentlySeen() ? "☁️ CLOUD CONNECTED • LOCAL WI-FI UNAVAILABLE" : "☁️ CLOUD / LOCAL CONNECTION CHECKING…");
            startDiscovery();
            return;
        }
        new Thread(() -> {
            try{
                Net.request("GET", base, "/local/health", null, null, null);
                runOnUiThread(() -> {
                    if(connection != null) connection.setText(cloudFallbackEnabled()
                        ? "🟢 LOCAL WI-FI PRIMARY • CLOUD FALLBACK ON"
                        : "🟢 LOCAL WI-FI CONNECTED");
                });
            }catch(Exception e){
                runOnUiThread(() -> {
                    if(connection != null) connection.setText(cloudRecentlySeen() ? "☁️ CLOUD CONNECTED • LOCAL WI-FI UNAVAILABLE" : "🟡 SEARCHING FOR PARENT…");
                    startDiscovery();
                });
            }
        }).start();
    }

    private void refreshConnectionLabel(){
        if(connection == null) return;
        long now = System.currentTimeMillis();
        long localSeen = sp().getLong("localLastSeenAt", 0L);
        boolean localOk = !sp().getString("localToken", "").isEmpty() && localSeen > 0L && now - localSeen < 15000L;
        if(localOk){
            connection.setText(cloudFallbackEnabled()
                ? "🟢 LOCAL WI-FI PRIMARY • CLOUD FALLBACK ON"
                : "🟢 LOCAL WI-FI CONNECTED");
            return;
        }
        if(cloudRecentlySeen()){
            connection.setText("☁️ CLOUD CONNECTED • INTERNET MODE");
            return;
        }
        connection.setText("🟡 RECONNECTING • LOCAL / CLOUD");
    }

    private boolean cloudRecentlySeen(){
        long seen = sp().getLong("cloudLastSeenAt", 0L);
        return cloudFallbackEnabled() && !sp().getString("cloudToken", "").isEmpty()
            && seen > 0L && System.currentTimeMillis() - seen < 45000L;
    }

    private void updateStatus(){
        boolean locked = sp().getBoolean("locked", false);
        boolean paired = sp().getBoolean("paired", false);
        if(state != null){
            state.setText(locked ? "BEDTIME LOCKED" : "YOU'RE FREE");
            state.setTextColor(locked ? Color.rgb(181, 67, 67) : Color.rgb(35, 47, 76));
        }
        if(stateIcon != null) stateIcon.setText(locked ? "🔒" : "🔓");
        if(status != null){
            status.setText(locked
                ? "Time to rest. Sweet dreams! 💤"
                : ((paired || (cloudFallbackEnabled() && sp().getBoolean("cloudPaired", false)))
                    ? (cloudFallbackEnabled()
                        ? "Local Wi-Fi is primary. Optional Cloud fallback is enabled."
                        : "Parent controls this Child over Local Wi-Fi.")
                    : "Give your connection code to your Parent."));
        }
    }

    private void showBackgroundReliabilityHelp(){
        new AlertDialog.Builder(this)
            .setTitle("Keep Child Connected")
            .setMessage(
                "For more reliable background monitoring, open this app's Android settings and set Battery usage to Unrestricted / Allow background activity if your phone offers it. " +
                "On some brands, also enable Auto-start or Background start. Keep Bedtime notifications enabled. " +
                "Swiping the app away should not normally stop its foreground service, but Android Force stop, a powered-off phone, or an OEM battery manager can still stop background work."
            )
            .setNegativeButton("CLOSE", null)
            .setPositiveButton("OPEN APP SETTINGS", (d,w) -> openAppSettings())
            .show();
    }

    private void showAppProtection(){
        new AlertDialog.Builder(this)
            .setTitle("🔒 App Protection")
            .setMessage(
                "This opens the Android system settings for this Bedtime app. " +
                "On phones that provide a Hide Apps / App Hiding feature, use that option there. " +
                "Android does not provide one universal public screen for Hide Apps, so the exact location depends on the phone brand."
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("OPEN SETTINGS", (d,w) -> openAppSettings())
            .show();
    }

    private void openAppSettings(){
        try{
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:"+getPackageName()));
            startActivity(i);
        }catch(Exception e){
            try{ startActivity(new Intent(Settings.ACTION_SETTINGS)); }catch(Exception ignored){}
        }
    }

    private void openOverlaySettings(){
        try{ startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()))); }
        catch(Exception e){ startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
    }

    private void startMonitor(){
        if(localToken.isEmpty() && (!cloudFallbackEnabled() || sp().getString("cloudToken", "").isEmpty())) return;
        try{
            Intent i = new Intent(this, BedtimeMonitorService.class);
            if(Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }catch(Exception ignored){}
    }

    private void confirmChangeRole(){
        new AlertDialog.Builder(this)
            .setTitle("Change Role")
            .setMessage("This will disconnect this phone from the current Parent, stop Child monitoring, and return you to role selection. Your Child pairing on this phone will be cleared, but the Parent account on the other phone will not be deleted.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CHANGE ROLE", (d,w) -> changeRoleToChooser())
            .show();
    }

    private void changeRoleToChooser(){
        final String base = sp().getString("localServer", "");
        final String t = sp().getString("localToken", "");
        // Stop local Child behavior immediately; cleanup of the old Parent is best-effort.
        stopDiscovery();
        try { stopService(new Intent(this, BedtimeMonitorService.class)); } catch (Exception ignored) {}
        try { BedtimeOverlay.hide(this); } catch (Exception ignored) {}
        getSharedPreferences("app_state", MODE_PRIVATE).edit()
            .putInt("active_role", 0)
            .apply();
        Toast.makeText(this, "Changing role…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                if (!base.isEmpty() && !t.isEmpty()) {
                    Net.request("POST", base, "/local/child/unpair", "x-child-local-token", t, new JSONObject(), 800, 1000);
                }
            } catch (Exception ignored) {
                // Local cleanup is best-effort; never block Change Role on an unavailable Wi-Fi Parent.
            }
            try {
                String cloud = sp().getString("cloudToken", "");
                if (!cloud.isEmpty()) Net.request("POST", BuildConfig.CLOUD_API_URL, "/api/child/unpair", "x-child-token", cloud, new JSONObject(), 1800, 2200);
            } catch (Exception ignored) {
                // Role switching must still work if Cloud is unavailable.
            }
            runOnUiThread(() -> {
                sp().edit()
                    .remove("localToken")
                    .remove("childId")
                    .remove("localServer")
                    .remove("cloudToken")
                    .remove("cloudChildId")
                    .remove("cloudCode")
                    .remove("localCode")
                    .remove("cloudPaired")
                    .remove("cloudLastSeenAt")
                    .remove("code")
                    .remove("paired")
                    .remove("locked")
                    .remove("token")
                    .remove("server")
                    .apply();
                localToken = "";
                childId = "";
                localServer = "";
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            });
        }).start();
    }

    private void confirmUnpair(){
        new AlertDialog.Builder(this)
            .setTitle("Change Parent?")
            .setMessage("This will disconnect this Child from the current Parent. Your local connection can then be paired with another Parent.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CHANGE", (d,w) -> unpair()).show();
    }

    private void unpair(){
        final String base = sp().getString("localServer", "");
        final String t = sp().getString("localToken", "");
        new Thread(() -> {
            try{
                if(!base.isEmpty() && !t.isEmpty()){
                    Net.request("POST", base, "/local/child/unpair", "x-child-local-token", t, new JSONObject());
                }
            }catch(Exception ignored){}
            try {
                String cloud = sp().getString("cloudToken", "");
                if(!cloud.isEmpty()) Net.request("POST", BuildConfig.CLOUD_API_URL, "/api/child/unpair", "x-child-token", cloud, new JSONObject());
            } catch(Exception ignored){}
            runOnUiThread(() -> {
                try{ stopService(new Intent(this, BedtimeMonitorService.class)); }catch(Exception ignored){}
                BedtimeOverlay.hide(this);
                sp().edit()
                    .remove("localToken")
                    .remove("childId")
                    .remove("localServer")
                    .remove("cloudToken")
                    .remove("cloudChildId")
                    .remove("cloudCode")
                    .remove("localCode")
                    .remove("cloudPaired")
                    .remove("cloudLastSeenAt")
                    .remove("code")
                    .remove("paired")
                    .remove("locked")
                    .remove("token")
                    .remove("server")
                    .apply();
                localToken=""; childId=""; localServer="";
                showSetup();
            });
        }).start();
    }
}
