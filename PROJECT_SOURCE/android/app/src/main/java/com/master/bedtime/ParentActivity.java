package com.master.bedtime;

import com.master.bedtime.practice.R;
import com.master.bedtime.practice.BuildConfig;

import android.app.*;
import android.content.*;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import android.util.Base64;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public class ParentActivity extends Activity {

    private EditText server,email,password,pairCode;
    private LinearLayout list;
    private TextView account,status;
    private String token="";
    private String selectedProofBase64="";
    private String selectedProofName="";
    private String selectedProofMimeType="";
    private JSONArray currentKids = new JSONArray();
    private JSONObject currentParentInfo = new JSONObject();
    private volatile JSONArray cachedCloudKids = new JSONArray();
    private volatile long cloudRosterFetchedAt = 0L;
    private volatile long cloudPairSyncStartedAt = 0L;
    private final Handler localRosterHandler = new Handler(Looper.getMainLooper());
    private volatile boolean localRosterRequestInFlight = false;
    private final Runnable localRosterPoll = new Runnable() {
        @Override public void run() {
            if (!token.isEmpty() && list != null) {
                refreshLocalChildrenOnly();
                localRosterHandler.postDelayed(this, 4000L);
            }
        }
    };

    // Live Parent-dashboard countdown for each Child's 24-hour trial.
    private static final long TRIAL_WARNING_WINDOW_MS = 6L * 60L * 60L * 1000L;
    private static final class TrialCountdownState {
        final long trialEndsAt;
        final int amountDue;
        TrialCountdownState(long trialEndsAt, int amountDue) {
            this.trialEndsAt = trialEndsAt;
            this.amountDue = amountDue;
        }
    }
    private final Map<TextView, TrialCountdownState> trialCountdownViews = new LinkedHashMap<>();
    private final Handler trialCountdownHandler = new Handler(Looper.getMainLooper());
    private final Runnable trialCountdownTick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<TextView, TrialCountdownState>> iterator = trialCountdownViews.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TextView, TrialCountdownState> entry = iterator.next();
                TextView view = entry.getKey();
                TrialCountdownState state = entry.getValue();
                if (view == null || view.getParent() == null) {
                    iterator.remove();
                    continue;
                }
                long remaining = state.trialEndsAt - now;
                if (remaining <= 0L) {
                    view.setText("TRIAL EXPIRED • PAYMENT DUE ₱" + state.amountDue);
                    view.setTextColor(RED);
                } else if (remaining <= TRIAL_WARNING_WINDOW_MS) {
                    view.setText("⚠️ TRIAL ENDS IN " + formatTrialCountdown(remaining));
                    view.setTextColor(Color.rgb(220, 125, 25));
                } else {
                    view.setText("⏳ FREE 24-HOUR TRIAL • " + formatTrialCountdown(remaining) + " LEFT");
                    view.setTextColor(GREEN);
                }
            }
            if (!trialCountdownViews.isEmpty() && !isFinishing()) {
                trialCountdownHandler.postDelayed(this, 1000L);
            }
        }
    };
    private static final int PICK_PAYMENT_PROOF=4101;

    private final int BG=Color.rgb(245,247,255);
    private final int BLUE=Color.rgb(55,125,245);
    private final int DEEP=Color.rgb(37,48,105);
    private final int GREEN=Color.rgb(50,205,105);
    private final int RED=Color.rgb(250,92,105);
    private final int TEXT=Color.rgb(40,48,75);
    private final int MUTED=Color.rgb(115,125,150);

    private android.content.SharedPreferences sp(){
        return getSharedPreferences("parent",MODE_PRIVATE);
    }

        @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        String savedToken = sp().getString("token", "");
        String savedName = sp().getString("name", "");
        if (savedToken != null && !savedToken.trim().isEmpty()
                && savedName != null && !savedName.trim().isEmpty()) {
            token = savedToken;
            showDashboard();
            return;
        }
        String registeredEmail = sp().getString("registered_email", "");
        if (registeredEmail != null && !registeredEmail.trim().isEmpty()) {
            showLogin(registeredEmail, "");
            return;
        }
        showParentWelcome();
    }

    private void showParentWelcome(){
        LinearLayout root=page();

        Space top=new Space(this);
        root.addView(top,lp(1,dp(24)));

        LinearLayout hero=new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(20),dp(28),dp(20),dp(28));
        hero.setBackground(gradient(
            Color.rgb(35,56,130),
            Color.rgb(105,70,190),28));
        shadow(hero);

        TextView moon=text("🌙",48,Color.WHITE);
        moon.setGravity(Gravity.CENTER);
        hero.addView(moon);

        TextView family=text("👨‍👩‍👧‍👦",46,Color.WHITE);
        family.setGravity(Gravity.CENTER);
        hero.addView(family);

        TextView title=text("Welcome, Parent",26,Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        hero.addView(title);

        TextView sub=text("Start your family bedtime journey",14,
                Color.rgb(225,230,255));
        sub.setGravity(Gravity.CENTER);
        hero.addView(sub);

        root.addView(hero,margin(-1,-2,0,20));

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20),dp(20),dp(20),dp(20));
        card.setBackground(bg(Color.WHITE,24));
        shadow(card);

        TextView title2=text("Create your Parent account",21,TEXT);
        title2.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        card.addView(title2);

        TextView info=text(
            "Register free. The 24-hour trial starts only after your first Child is successfully paired.",
            13,MUTED);
        card.addView(info,margin(-1,-2,5,15));

        EditText name=new EditText(this);
        name.setHint("Name");
        name.setSingleLine(true);
        name.setPadding(dp(14),0,dp(14),0);
        name.setBackground(bg(Color.rgb(246,248,253),16));
        card.addView(name,margin(-1,dp(52),0,10));

        EditText regEmail=new EditText(this);
        regEmail.setHint("Email");
        regEmail.setInputType(33);
        regEmail.setSingleLine(true);
        regEmail.setPadding(dp(14),0,dp(14),0);
        regEmail.setBackground(bg(Color.rgb(246,248,253),16));
        card.addView(regEmail,margin(-1,dp(52),0,10));

        EditText regPassword=new EditText(this);
        regPassword.setHint("Password");
        regPassword.setInputType(129);
        regPassword.setSingleLine(true);
        regPassword.setPadding(dp(14),0,dp(14),0);
        regPassword.setBackground(bg(Color.rgb(246,248,253),16));
        card.addView(regPassword,margin(-1,dp(52),0,10));

        EditText mobile=new EditText(this);
        mobile.setHint("Mobile");
        mobile.setInputType(3);
        mobile.setSingleLine(true);
        mobile.setPadding(dp(14),0,dp(14),0);
        mobile.setBackground(bg(Color.rgb(246,248,253),16));
        card.addView(mobile,margin(-1,dp(52),0,14));

        Button save=button("Create Account  →");
        save.setBackground(gradient(BLUE,Color.rgb(65,185,255),18));
        card.addView(save,margin(-1,dp(54),0,0));

        TextView result=text("",13,RED);
        result.setGravity(Gravity.CENTER);
        card.addView(result,margin(-1,-2,10,0));

        TextView existing=text("Already registered?  Sign in",
            14,BLUE);
        existing.setGravity(Gravity.CENTER);
        existing.setPadding(0,dp(10),0,dp(4));
        card.addView(existing);

        save.setOnClickListener(v->{
            String n=name.getText().toString().trim();
            String e=regEmail.getText().toString().trim();
            String pw=regPassword.getText().toString();
            String m=mobile.getText().toString().trim();

            if(n.isEmpty() || e.isEmpty() || pw.isEmpty()){
                result.setText("Please complete Name, Email and Password.");
                return;
            }

            save.setEnabled(false);
            result.setText("Creating your account…");

            new Thread(()->{
                try{
                    JSONObject body=new JSONObject()
                        .put("name",n)
                        .put("email",e)
                        .put("password",pw)
                        .put("mobile",m);

                    JSONObject j=Net.request(
                        "POST",
                        getServerUrl(),
                        "/api/register-parent",
                        null,null,body);

                    runOnUiThread(()->{
                        JSONObject parentJson = j.optJSONObject("parent");
                        String recoveryCode = parentJson != null
                            ? parentJson.optString("recoveryCode", "") : "";
                        showRecoveryCodeAfterRegistration(e, recoveryCode);
                    });

                }catch(Exception ex){
                    runOnUiThread(()->{
                        save.setEnabled(true);
                        result.setText(ex.getMessage()!=null
                            ? ex.getMessage() : "Registration failed");
                    });
                }
            }).start();
        });

        existing.setOnClickListener(v->showLogin());

        root.addView(card,margin(-1,-2,0,18));

        TextView note=text(
            "🎉 First Child: 24-hour trial after pairing  •  Second Child: ₱300  •  Further Children: ₱50 each",
            12,MUTED);
        note.setGravity(Gravity.CENTER);
        root.addView(note);
        addChangeRoleButton(root);
    }

    private String getServerUrl(){
        String url = BuildConfig.CLOUD_API_URL == null ? "" : BuildConfig.CLOUD_API_URL.trim();
        return url.replaceAll("/+$", "");
    }

    private String localBase(){
        return "http://127.0.0.1:" + LocalParentServer.PORT;
    }

    private String localAdminToken(){
        return getSharedPreferences("parent_local",MODE_PRIVATE)
            .getString("admin_token", "");
    }

    private JSONObject localRequest(String method, String path, JSONObject body) throws Exception {
        String admin = localAdminToken();
        if(admin.isEmpty()) throw new Exception("Local Parent link is not ready");
        return Net.request(
            method, localBase(), path,
            "x-parent-local-token", admin, body);
    }

    private JSONArray fetchCloudChildren(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - cloudRosterFetchedAt < 10000L) return cachedCloudKids;
        if (token == null || token.isEmpty()) return cachedCloudKids;
        try {
            JSONArray kids = Net.request("GET", getServerUrl(), "/api/children",
                "x-parent-token", token, null).getJSONArray("children");
            cachedCloudKids = kids;
            cloudRosterFetchedAt = now;
            return kids;
        } catch (Exception e) {
            android.util.Log.w("BedtimeParent", "Cloud Child roster unavailable; retaining local/offline roster: " + e.getMessage());
            return cachedCloudKids;
        }
    }

    private void syncLocalPairingsToCloud(JSONArray localKids) {
        long now = System.currentTimeMillis();
        if (token == null || token.isEmpty() || now - cloudPairSyncStartedAt < 30000L) return;
        cloudPairSyncStartedAt = now;
        new Thread(() -> {
            for (int i = 0; i < localKids.length(); i++) {
                JSONObject child = localKids.optJSONObject(i);
                if (child == null || !child.optBoolean("paired", false)) continue;
                String code = child.optString("code", "");
                String localId = child.optString("childId", "");
                if (!code.matches("\\d{6}")) continue;

                // Trial/payment metadata synchronizes independently from Cloud control.
                // This lets Local Wi-Fi remain the command path while the billing ledger
                // is recorded in D1 whenever Internet is available.
                try {
                    JSONObject billingRequest = new JSONObject()
                        .put("childKey", localId.isEmpty() ? code : localId)
                        .put("code", code)
                        .put("name", child.optString("name", "Child"))
                        .put("pairedAt", child.optLong("pairedAt", System.currentTimeMillis()));
                    JSONObject billingResponse = Net.request("POST", getServerUrl(),
                        "/api/billing/children/register", "x-parent-token", token, billingRequest);
                    JSONObject bill = billingResponse.optJSONObject("child");
                    if (bill != null && !localId.isEmpty()) {
                        JSONObject cached = new JSONObject()
                            .put("billingOrder", bill.optInt("childOrder", 0))
                            .put("billingStatus", bill.optString("billingStatus", ""))
                            .put("trialStartedAt", billingTime(bill, "trialStartedAt"))
                            .put("trialEndsAt", billingTime(bill, "trialEndsAt"))
                            .put("paidUntil", billingTime(bill, "paidUntil"))
                            .put("paidAt", billingTime(bill, "paidAt"))
                            .put("paidAmount", bill.optInt("paidAmount", 0))
                            .put("amountDue", bill.optInt("amountDue", 0));
                        localRequest("POST", "/local/children/" + localId + "/billing", cached);
                    }
                } catch (Exception e) {
                    android.util.Log.d("BedtimeParent", "Billing metadata sync deferred until Internet is available: " + e.getMessage());
                }

                // Explicit false means the Child chose local-only control; do not create
                // a Cloud command pairing. Billing metadata above is still allowed to sync.
                if (child.has("cloudFallbackEnabled") && !child.optBoolean("cloudFallbackEnabled", false)) continue;
                try {
                    Net.request("POST", getServerUrl(), "/api/pair", "x-parent-token", token,
                        new JSONObject().put("code", code));
                } catch (Exception e) {
                    android.util.Log.d("BedtimeParent", "Cloud pairing sync deferred: " + e.getMessage());
                }
            }
        }, "bedtime-cloud-pairing-sync").start();
    }

    private long billingTime(JSONObject source, String key) {
        Object value = source == null ? null : source.opt(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null || JSONObject.NULL.equals(value)) return 0L;
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return 0L;
        try { return Long.parseLong(text); } catch (Exception ignored) {}
        try { return java.time.Instant.parse(text).toEpochMilli(); } catch (Exception ignored) {}
        return 0L;
    }

    private long childTime(JSONObject child, String key) {
        return billingTime(child, key);
    }

    private String billingBlockReason(JSONObject child, String command) {
        if (child == null || !"LOCK".equalsIgnoreCase(command)) return "";
        String billStatus = child.optString("billingStatus", "").trim().toUpperCase(Locale.ROOT);
        long at = System.currentTimeMillis();
        long trialEnd = childTime(child, "trialEndsAt");
        long paidUntil = childTime(child, "paidUntil");
        if ("TRIAL".equals(billStatus) && trialEnd > 0L && at >= trialEnd) billStatus = "EXPIRED";
        if ("ACTIVE".equals(billStatus) && paidUntil > 0L && at >= paidUntil) billStatus = "EXPIRED";
        if ("EXPIRED".equals(billStatus) || "PENDING_PAYMENT".equals(billStatus)) {
            int due = child.optInt("amountDue", 0);
            if (due <= 0) due = child.optInt("billingOrder", 0) == 2 ? 300 : (child.optInt("billingOrder", 0) >= 3 ? 50 : 300);
            return "Payment required: ₱" + due + " for " + child.optString("name", "this Child") + " before LOCK control.";
        }
        return "";
    }

    private JSONArray mergeChildLists(JSONArray localKids, JSONArray cloudKids) {
        JSONArray merged = new JSONArray();
        java.util.HashSet<String> matchedCloudIds = new java.util.HashSet<>();
        java.util.HashMap<String, JSONObject> cloudByCode = new java.util.HashMap<>();
        for (int i = 0; i < cloudKids.length(); i++) {
            JSONObject c = cloudKids.optJSONObject(i);
            if (c == null) continue;
            String code = c.optString("code", "");
            if (!code.isEmpty()) cloudByCode.put(code, c);
        }
        for (int i = 0; i < localKids.length(); i++) {
            JSONObject original = localKids.optJSONObject(i);
            if (original == null) continue;
            try {
                JSONObject c = new JSONObject(original.toString());
                String localId = c.optString("childId", "");
                JSONObject cloud = cloudByCode.get(c.optString("code", ""));
                boolean cloudAllowed = !c.has("cloudFallbackEnabled")
                    ? cloud != null // legacy local records keep their prior cloud behavior
                    : c.optBoolean("cloudFallbackEnabled", false);
                c.put("localChildId", localId).put("hasLocal", true);
                if (cloud != null && cloudAllowed) {
                    String cloudId = cloud.optString("childId", "");
                    c.put("cloudChildId", cloudId).put("hasCloud", true)
                        .put("cloudOnline", cloud.optBoolean("online", false));
                    if (!cloudId.isEmpty()) matchedCloudIds.add(cloudId);
                    boolean localOnline = c.optBoolean("online", false);
                    boolean cloudOnline = cloud.optBoolean("online", false);
                    c.put("online", localOnline || cloudOnline);
                    if (cloudOnline && !localOnline) c.put("locked", cloud.optBoolean("locked", false));
                } else {
                    if (cloud != null) matchedCloudIds.add(cloud.optString("childId", ""));
                    c.put("cloudChildId", "").put("hasCloud", false).put("cloudOnline", false);
                }
                merged.put(c);
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < cloudKids.length(); i++) {
            JSONObject cloud = cloudKids.optJSONObject(i);
            if (cloud == null) continue;
            String cloudId = cloud.optString("childId", "");
            if (cloudId.isEmpty() || matchedCloudIds.contains(cloudId)) continue;
            try {
                JSONObject c = new JSONObject(cloud.toString());
                c.put("localChildId", "").put("cloudChildId", cloudId)
                    .put("hasLocal", false).put("hasCloud", true)
                    .put("cloudOnline", cloud.optBoolean("online", false));
                merged.put(c);
            } catch (Exception ignored) {}
        }
        return merged;
    }

    private JSONObject findChildForAction(String id) {
        if (currentKids == null) return null;
        for (int i = 0; i < currentKids.length(); i++) {
            JSONObject c = currentKids.optJSONObject(i);
            if (c != null && (id.equals(c.optString("childId", "")) || id.equals(c.optString("localChildId", "")))) return c;
        }
        return null;
    }

    private String autoUnlockPreferenceKey(String childId) {
        return "autoUnlockAt_" + childId;
    }

    private long selectedAutoUnlockAt(String childId, String command) {
        if (!"LOCK".equalsIgnoreCase(command) || childId == null || childId.trim().isEmpty()) return 0L;
        long selected = sp().getLong(autoUnlockPreferenceKey(childId), 0L);
        if (selected > 0L && selected <= System.currentTimeMillis()) {
            sp().edit().remove(autoUnlockPreferenceKey(childId)).apply();
            return 0L;
        }
        return selected;
    }

    private int sendCommandForChild(JSONObject child, String idFallback, String command) {
        return sendCommandForChild(child, idFallback, command, selectedAutoUnlockAt(idFallback, command));
    }

    private int sendCommandForChild(JSONObject child, String idFallback, String command, long requestedUnlockAt) {
        if (child == null) return 0;
        int sent = 0;
        String localId = child.optString("localChildId", child.optBoolean("hasLocal", true) ? idFallback : "");
        String cloudId = child.optString("cloudChildId", child.optBoolean("hasCloud", false) ? idFallback : "");
        long unlockAt = "LOCK".equalsIgnoreCase(command) && requestedUnlockAt > System.currentTimeMillis()
            ? requestedUnlockAt : 0L;
        JSONObject body;
        try { body = new JSONObject().put("command", command).put("unlockAt", unlockAt); }
        catch (Exception e) { body = new JSONObject(); }
        boolean localDelivered = false;
        if (!localId.isEmpty()) {
            try {
                Net.request("POST", localBase(), "/local/children/" + localId + "/command",
                    "x-parent-local-token", localAdminToken(), body, 1000, 1500);
                sent++;
                localDelivered = true;
            } catch (Exception e) {
                android.util.Log.w("BedtimeParent", "Local command unavailable; checking Cloud fallback: " + e.getMessage());
            }
        }
        // Active-path routing: when local Wi-Fi succeeds, send only locally. Use
        // Cloud only if local is unavailable, including timed Auto Unlock commands.
        boolean timedUnlockRequested = unlockAt > 0L && "LOCK".equalsIgnoreCase(command);
        if (!localDelivered && !cloudId.isEmpty() && token != null && !token.isEmpty()) {
            try {
                Net.request("POST", getServerUrl(), "/api/children/" + cloudId + "/command",
                    "x-parent-token", token, body);
                sent++;
            } catch (Exception e) {
                android.util.Log.w("BedtimeParent", "Cloud fallback command unavailable: " + e.getMessage());
            }
        }
        if (sent > 0 && (unlockAt <= 0L || !"LOCK".equalsIgnoreCase(command)) && idFallback != null && !idFallback.isEmpty()) {
            sp().edit().remove(autoUnlockPreferenceKey(idFallback)).apply();
        }
        return sent;
    }

    private void showLogin(String prefillEmail,String prefillPassword){
        showLogin();
        if(email!=null) email.setText(prefillEmail);
        if(password!=null) password.setText(prefillPassword);
    }

    private int dp(int n){
        return (int)(n*getResources().getDisplayMetrics().density+0.5f);
    }

    private GradientDrawable bg(int color,float radius){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int)radius));
        return g;
    }

    private GradientDrawable gradient(int a,int b,float radius){
        GradientDrawable g=new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{a,b}
        );
        g.setCornerRadius(dp((int)radius));
        return g;
    }

    private TextView text(String s,float size,int color){
        TextView v=new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setFontFeatureSettings("kern");
        return v;
    }

    private Button button(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setMinHeight(dp(48));
        b.setPadding(dp(12),0,dp(12),0);
        return b;
    }

    private LinearLayout page(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16),dp(12),dp(16),dp(20));

        ScrollView sc=new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(root);
        setContentView(sc);
        return root;
    }

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams lp(int w,int h){
        return new LinearLayout.LayoutParams(w,h);
    }

    private LinearLayout.LayoutParams margin(int w,int h,int top,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);
        p.topMargin=dp(top);
        p.bottomMargin=dp(bottom);
        return p;
    }

    private void shadow(View v){
        if(Build.VERSION.SDK_INT>=21){
            v.setElevation(dp(5));
        }
    }

    private TextView icon(String s,int size){
        TextView v=text(s,size,Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    private ImageButton iconButton(int resId,int tint,String description){
        ImageButton v=new ImageButton(this);
        v.setImageResource(resId);
        v.setColorFilter(tint,android.graphics.PorterDuff.Mode.SRC_IN);
        v.setContentDescription(description);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(12),dp(12),dp(12),dp(12));
        v.setBackground(bg(Color.TRANSPARENT,18));
        return v;
    }
    private TextView pill(String s,boolean online){
        TextView v=text((online?"● ":"○ ")+s,12,
                online?Color.rgb(22,150,78):MUTED);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10),dp(5),dp(10),dp(5));
        v.setBackground(bg(
            online?Color.rgb(225,250,235):Color.rgb(240,242,247),20));
        return v;
    }

    private void showLogin(){

        LinearLayout root=page();

        Space top=new Space(this);
        root.addView(top,lp(1,dp(35)));

        LinearLayout hero=new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(20),dp(30),dp(20),dp(30));
        hero.setBackground(gradient(
            Color.rgb(35,56,130),
            Color.rgb(105,70,190),28));
        shadow(hero);

        TextView moon=text("🌙",52,Color.WHITE);
        moon.setGravity(Gravity.CENTER);
        hero.addView(moon);

        TextView family=text("👨‍👩‍👧‍👦",50,Color.WHITE);
        family.setGravity(Gravity.CENTER);
        hero.addView(family);

        TextView title=text("Family Bedtime",28,Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        hero.addView(title);

        TextView sub=text("A calmer night for the whole family",14,
                Color.rgb(225,230,255));
        sub.setGravity(Gravity.CENTER);
        hero.addView(sub);

        root.addView(hero,margin(-1,-2,0,22));

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20),dp(20),dp(20),dp(20));
        card.setBackground(bg(Color.WHITE,24));
        shadow(card);

        TextView ct=text("Parent",22,TEXT);
        ct.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        card.addView(ct);

        TextView cs=text("Sign in to manage your family",14,MUTED);
        card.addView(cs,margin(-1,-2,3,15));

        email=new EditText(this);
        email.setHint("Email");
        email.setSingleLine(true);
        email.setPadding(dp(14),0,dp(14),0);
        email.setBackground(bg(Color.rgb(246,248,253),16));
        card.addView(email,margin(-1,dp(52),0,10));

        password=new EditText(this);
        password.setHint("Password");
        password.setInputType(129);
        password.setSingleLine(true);
        password.setPadding(dp(14),0,dp(14),0);
        password.setBackground(bg(Color.rgb(246,248,253),16));
        card.addView(password,margin(-1,dp(52),0,12));

        server=new EditText(this);
        server.setHint("Server");
        server.setSingleLine(true);
        server.setText(getServerUrl());
        server.setTextSize(12);
        server.setVisibility(View.GONE);
        card.addView(server);

        Button login=button("Continue  →");
        login.setBackground(gradient(BLUE,Color.rgb(65,185,255),18));
        card.addView(login,margin(-1,dp(54),0,0));

        status=text("",13,RED);
        status.setGravity(Gravity.CENTER);
        card.addView(status,margin(-1,-2,8,0));

        login.setOnClickListener(v->login());

        root.addView(card,margin(-1,-2,0,14));

        TextView forgot=text("Forgot password?",14,BLUE);
        forgot.setGravity(Gravity.CENTER);
        forgot.setPadding(0,dp(10),0,dp(6));
        forgot.setOnClickListener(v->showForgotPassword());
        root.addView(forgot);

        TextView trial=text(
            "🎉 FREE 24-HOUR TRIAL AFTER FIRST PAIRING  •  CHILD-BASED BILLING",
            12,DEEP);
        trial.setGravity(Gravity.CENTER);
        trial.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        trial.setPadding(dp(10),dp(8),dp(10),dp(8));
        trial.setBackground(bg(Color.rgb(232,240,255),18));
        root.addView(trial,margin(-1,-2,0,12));

        TextView register=text("New Parent?  Create an account",
            14,BLUE);
        register.setGravity(Gravity.CENTER);
        register.setPadding(0,dp(8),0,dp(8));
        register.setOnClickListener(v->showParentWelcome());
        root.addView(register);

        TextView hint=text("Your family data stays connected to your account.",
                12,MUTED);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);
        addChangeRoleButton(root);
    }

    private void login(){
        status.setText("Connecting…");

        new Thread(()->{
            try{
                JSONObject body=new JSONObject()
                    .put("email",email.getText().toString().trim())
                    .put("password",password.getText().toString());

                JSONObject j=Net.request(
                    "POST",
                    getServerUrl(),
                    "/api/login",
                    null,null,body);

                token=j.getString("token");
                JSONObject lp = j.optJSONObject("parent");

                sp().edit()
                    .putString("token",token)
                    .putString("parentId", lp!=null?lp.optString("parentId","") : "")
                    .putString("name", lp!=null?lp.optString("name","") : "")
                    .putString("email", lp!=null?lp.optString("email","") : email.getText().toString().trim())
                    .putString("mobile", lp!=null?lp.optString("mobile","") : "")
                    .putString("server", getServerUrl())
                    .apply();

                runOnUiThread(this::showDashboard);

            }catch(Exception e){
                String detail = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? "Network request failed" : e.getMessage().trim();
                runOnUiThread(()->status.setText("Unable to connect: " + detail));
            }
        }).start();
    }

    private void showDashboard(){

        LinearLayout root=page();

        // HEADER
        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20),dp(18),dp(20),dp(20));
        header.setBackground(gradient(
            Color.rgb(29,48,115),
            Color.rgb(91,66,176),28));
        shadow(header);

        LinearLayout top=row();

        LinearLayout brand=new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);

        TextView small=text("🌙  FAMILY",12,
                Color.rgb(195,215,255));
        small.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        brand.addView(small);

        TextView title=text("Bedtime",27,Color.WHITE);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        brand.addView(title);

        top.addView(brand,new LinearLayout.LayoutParams(
            0,-2,1));

        ImageButton wallet=iconButton(R.drawable.ic_payment,Color.WHITE,"Family Payment");
        wallet.setBackground(bg(Color.argb(55,255,255,255),18));
        wallet.setOnClickListener(v->showPaymentMenu());
        top.addView(wallet,lp(dp(54),dp(54)));

        ImageButton refresh=iconButton(R.drawable.ic_refresh,Color.WHITE,"Refresh");
        refresh.setBackground(bg(Color.argb(55,255,255,255),18));
        refresh.setOnClickListener(v->refresh());
        LinearLayout.LayoutParams rp=lp(dp(54),dp(54));
        rp.leftMargin=dp(8);
        top.addView(refresh,rp);

        header.addView(top);

        LinearLayout familyBox=new LinearLayout(this);
        familyBox.setGravity(Gravity.CENTER);
        familyBox.setPadding(0,dp(18),0,0);

        TextView fam=text("👨‍👩‍👧‍👦",55,Color.WHITE);
        fam.setGravity(Gravity.CENTER);
        familyBox.addView(fam);

        header.addView(familyBox);

        TextView welcome=text("Good night, family ✨",
            18,Color.WHITE);
        welcome.setGravity(Gravity.CENTER);
        welcome.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(welcome);

        root.addView(header,margin(-1,-2,0,16));

        // FAMILY STATUS
        LinearLayout statusCard=new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(16),dp(13),dp(16),dp(13));
        statusCard.setBackground(bg(Color.WHITE,20));
        shadow(statusCard);

        TextView crown=text("👑",30,TEXT);
        statusCard.addView(crown,lp(dp(48),dp(48)));

        account=text("Family",17,TEXT);
        account.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        statusCard.addView(account,lp(0,dp(48)));
        ((LinearLayout.LayoutParams)account.getLayoutParams()).weight=1;

        TextView check=text("✓",22,Color.WHITE);
        check.setGravity(Gravity.CENTER);
        check.setBackground(bg(GREEN,30));
        statusCard.addView(check,lp(dp(48),dp(48)));

        root.addView(statusCard,margin(-1,-2,0,16));

        // ADD CHILD
        LinearLayout addCard=new LinearLayout(this);
        addCard.setOrientation(LinearLayout.HORIZONTAL);
        addCard.setGravity(Gravity.CENTER_VERTICAL);
        addCard.setPadding(dp(18),dp(15),dp(18),dp(15));
        addCard.setBackground(gradient(
            Color.rgb(224,240,255),
            Color.rgb(239,231,255),22));
        shadow(addCard);

        TextView plus=text("+",42,Color.WHITE);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(gradient(BLUE,
            Color.rgb(105,120,255),60));
        shadow(plus);
        addCard.addView(plus,lp(dp(68),dp(68)));

        LinearLayout addText=new LinearLayout(this);
        addText.setOrientation(LinearLayout.VERTICAL);
        addText.setPadding(dp(16),0,0,0);

        TextView at=text("Add a child",18,TEXT);
        at.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        addText.addView(at);

        TextView as=text("Connect another family device",12,MUTED);
        addText.addView(as);

        addCard.addView(addText,lp(0,-2));
        ((LinearLayout.LayoutParams)addText.getLayoutParams()).weight=1;

        TextView arrow=text("›",34,BLUE);
        arrow.setGravity(Gravity.CENTER);
        addCard.addView(arrow,lp(dp(40),dp(60)));

        addCard.setOnClickListener(v->showAddChild());

        root.addView(addCard,margin(-1,-2,0,18));

        // QUICK LOCK / UNLOCK CONTROLS
        LinearLayout quickTitle=row();
        TextView quickLabel=text("Quick controls",18,TEXT);
        quickLabel.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        quickTitle.addView(quickLabel,lp(0,-2));
        ((LinearLayout.LayoutParams)quickLabel.getLayoutParams()).weight=1;
        TextView quickHint=text("Applies to every connected child",11,MUTED);
        quickTitle.addView(quickHint);
        root.addView(quickTitle,margin(-1,-2,0,8));

        LinearLayout quickControls=row();
        quickControls.setPadding(0,0,0,dp(18));
        Button lockAll=button("🔒  LOCK ALL");
        lockAll.setTextSize(14);
        lockAll.setBackground(gradient(
            Color.rgb(255,92,110),
            Color.rgb(255,125,135),18));
        Button unlockAll=button("🔓  UNLOCK ALL");
        unlockAll.setTextSize(14);
        unlockAll.setBackground(gradient(
            Color.rgb(42,205,103),
            Color.rgb(85,225,125),18));
        quickControls.addView(lockAll,lp(0,dp(54)));
        ((LinearLayout.LayoutParams)lockAll.getLayoutParams()).weight=1;
        quickControls.addView(unlockAll,lp(0,dp(54)));
        LinearLayout.LayoutParams uap=(LinearLayout.LayoutParams)unlockAll.getLayoutParams();
        uap.weight=1;
        uap.leftMargin=dp(8);
        lockAll.setOnClickListener(v->commandAll(currentKids,"LOCK"));
        unlockAll.setOnClickListener(v->commandAll(currentKids,"UNLOCK"));
        root.addView(quickControls,margin(-1,-2,0,0));

        // CHILDREN TITLE
        LinearLayout childTitle=row();

        TextView kids=text("My Children",20,TEXT);
        kids.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        childTitle.addView(kids,lp(0,-2));
        ((LinearLayout.LayoutParams)kids.getLayoutParams()).weight=1;

        TextView count=text("LIVE",11,BLUE);
        count.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        childTitle.addView(count);

        root.addView(childTitle,margin(-1,-2,0,10));

        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        // dashboard-only status field retained for compatibility
        status=text("",1,Color.TRANSPARENT);
        status.setVisibility(View.GONE);

        root.addView(text("  ",1,BG));

        // bottom nav visual
        LinearLayout nav=row();
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8),dp(8),dp(8),dp(8));
        nav.setBackground(bg(Color.WHITE,24));
        shadow(nav);

        ImageButton home=iconButton(R.drawable.ic_home,BLUE,"Home");
        nav.addView(home,lp(0,dp(52)));
        ((LinearLayout.LayoutParams)home.getLayoutParams()).weight=1;

        ImageButton family=iconButton(R.drawable.ic_family,MUTED,"Family Center");
        nav.addView(family,lp(0,dp(52)));
        ((LinearLayout.LayoutParams)family.getLayoutParams()).weight=1;
        family.setOnClickListener(v->showFamilyCenter());

        ImageButton settings=iconButton(R.drawable.ic_settings,MUTED,"Parent Settings");
        nav.addView(settings,lp(0,dp(52)));
        ((LinearLayout.LayoutParams)settings.getLayoutParams()).weight=1;
        settings.setOnClickListener(v->showParentSettings());

        root.addView(nav,margin(-1,-2,15,0));

        try { LocalParentService.start(this); } catch(Exception ignored) {}
        localRosterHandler.removeCallbacks(localRosterPoll);
        localRosterHandler.postDelayed(localRosterPoll, 2500L);
        refresh();
    }

    private void render(JSONObject p,JSONArray kids){

        if(account!=null){
            String name=p.optString("name","Parent");
            account.setText(name);
        }

        if(list==null)return;

        trialCountdownHandler.removeCallbacks(trialCountdownTick);
        trialCountdownViews.clear();
        list.removeAllViews();

        for(int i=0;i<kids.length();i++){

            JSONObject c=kids.optJSONObject(i);
            if(c==null)continue;

            final String cid=c.optString("childId");
            final String name=c.optString("name","Child");
            final boolean online=c.optBoolean("online");
            final boolean locked=c.optBoolean("locked");

            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14),dp(14),dp(14),dp(14));
            card.setBackground(bg(Color.WHITE,24));
            shadow(card);

            // child header
            LinearLayout head=row();

            TextView avatar=text(
                i%2==0?"👦":"👧",35,TEXT);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(bg(
                i%2==0?
                    Color.rgb(220,239,255):
                    Color.rgb(255,231,242),50));
            head.addView(avatar,lp(dp(58),dp(58)));

            LinearLayout info=new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(12),0,dp(8),0);

            TextView nm=text(name,18,TEXT);
            nm.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            info.addView(nm);

            info.addView(pill(
                online?"ONLINE":"OFFLINE",online),
                margin(-2,dp(30),5,0));
            TextView bill = null;
            String billingStatus = c.optString("billingStatus", "").trim().toUpperCase(Locale.ROOT);
            if (!billingStatus.isEmpty()) {
                String billingLabel;
                int billingColor = MUTED;
                long trialEnd = childTime(c, "trialEndsAt");
                long paidUntil = childTime(c, "paidUntil");
                int amountDue = c.optInt("amountDue", 0);
                if ("TRIAL".equals(billingStatus) && trialEnd > 0L && System.currentTimeMillis() >= trialEnd) billingStatus = "EXPIRED";
                if ("ACTIVE".equals(billingStatus) && paidUntil > 0L && System.currentTimeMillis() >= paidUntil) billingStatus = "EXPIRED";
                if ("TRIAL".equals(billingStatus)) {
                    if (trialEnd > 0L) {
                        long remaining = trialEnd - System.currentTimeMillis();
                        billingLabel = "⏳ FREE 24-HOUR TRIAL • " + formatTrialCountdown(remaining) + " LEFT";
                        billingColor = remaining <= TRIAL_WARNING_WINDOW_MS ? Color.rgb(220, 125, 25) : GREEN;
                    } else {
                        billingLabel = "FREE 24-HOUR TRIAL • waiting for start time";
                        billingColor = MUTED;
                    }
                } else if ("ACTIVE".equals(billingStatus)) {
                    billingLabel = paidUntil > 0L ? "PAID • until " + formatAutoUnlockTime(paidUntil) : "PAID";
                    billingColor = GREEN;
                } else if ("EXPIRED".equals(billingStatus) || "PENDING_PAYMENT".equals(billingStatus)) {
                    billingLabel = "PAYMENT DUE ₱" + (amountDue > 0 ? amountDue : (c.optInt("billingOrder", 0) == 2 ? 300 : (c.optInt("billingOrder", 0) >= 3 ? 50 : 300)));
                    billingColor = RED;
                } else {
                    billingLabel = "BILLING: " + billingStatus;
                }
                bill = text(billingLabel, 12, billingColor);
                bill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                bill.setSingleLine(false);
                bill.setMaxLines(2);
                bill.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                bill.setPadding(dp(8), dp(6), dp(8), dp(6));
                bill.setBackground(bg(Color.rgb(245, 247, 250), 12));
                bill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

                if ("TRIAL".equals(billingStatus) && trialEnd > 0L) {
                    int childOrder = c.optInt("billingOrder", 0);
                    int payable = amountDue > 0 ? amountDue : (childOrder == 2 ? 300 : (childOrder >= 3 ? 50 : 300));
                    trialCountdownViews.put(bill, new TrialCountdownState(trialEnd, payable));
                }
            }

            head.addView(info,lp(0,-2));
            ((LinearLayout.LayoutParams)info.getLayoutParams()).weight=1;

            TextView lockState=text(
                locked?"🔒":"🔓",26,TEXT);
            lockState.setGravity(Gravity.CENTER);
            head.addView(lockState,lp(dp(50),dp(50)));

            if(!online){
                ImageButton remove=new ImageButton(this);
                remove.setImageResource(R.drawable.ic_delete);
                remove.setColorFilter(RED);
                remove.setBackgroundColor(Color.TRANSPARENT);
                remove.setPadding(dp(10),dp(10),dp(10),dp(10));
                remove.setContentDescription("Remove offline child");
                remove.setOnClickListener(v->confirmDeleteOfflineChild(cid,name));
                head.addView(remove,lp(dp(48),dp(48)));
            }

            card.addView(head);

            // Billing status/countdown gets its own full-width row.
            if (bill != null) {
                LinearLayout.LayoutParams billingParams =
                    new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                billingParams.topMargin = dp(8);
                billingParams.leftMargin = dp(2);
                billingParams.rightMargin = dp(2);
                card.addView(bill, billingParams);
            }

            // controls
            TextView actionHint=text(
                online?"Control available now":"Control saved; child is offline",
                11, online?GREEN:MUTED);
            actionHint.setPadding(dp(2),dp(10),0,0);
            card.addView(actionHint);
            // CHILD_BACKGROUND_HELP_GUIDE_V1
            Button childBackgroundHelp = button("CHILD PHONE SETTINGS GUIDE");
            childBackgroundHelp.setBackground(bg(Color.rgb(238,242,255),18));
            childBackgroundHelp.setTextColor(DEEP);
            childBackgroundHelp.setOnClickListener(v -> showChildBackgroundHelp(name));
            card.addView(childBackgroundHelp, margin(-1,dp(46),0,8));

            LinearLayout controls=row();
            controls.setPadding(0,dp(8),0,0);

            Button lock=button("🔒  LOCK");
            lock.setTextSize(14);
            lock.setBackground(gradient(
                Color.rgb(255,92,110),
                Color.rgb(255,125,135),18));
            lock.setContentDescription("Lock "+name);

            Button unlock=button("🔓  UNLOCK");
            unlock.setTextSize(14);
            unlock.setBackground(gradient(
                Color.rgb(42,205,103),
                Color.rgb(85,225,125),18));
            unlock.setContentDescription("Unlock "+name);

            controls.addView(lock,lp(0,dp(52)));
            ((LinearLayout.LayoutParams)lock.getLayoutParams()).weight=1;

            controls.addView(unlock,lp(0,dp(52)));
            LinearLayout.LayoutParams up=
                (LinearLayout.LayoutParams)unlock.getLayoutParams();
            up.weight=1;
            up.leftMargin=dp(8);

            lock.setOnClickListener(v->command(cid,"LOCK"));
            unlock.setOnClickListener(v->command(cid,"UNLOCK"));

            card.addView(controls);

            long pendingUnlockAt = sp().getLong(autoUnlockPreferenceKey(cid), 0L);
            long serverUnlockAt = c.optLong("unlockAt", 0L);
            long nowForUnlock = System.currentTimeMillis();
            if (pendingUnlockAt > 0L && pendingUnlockAt <= nowForUnlock) {
                sp().edit().remove(autoUnlockPreferenceKey(cid)).apply();
                pendingUnlockAt = 0L;
            }
            if (serverUnlockAt > 0L && serverUnlockAt <= nowForUnlock) serverUnlockAt = 0L;
            long displayedUnlockAt = pendingUnlockAt > 0L ? pendingUnlockAt : serverUnlockAt;
            boolean timerAlreadyActive = serverUnlockAt > 0L && serverUnlockAt == displayedUnlockAt;
            String autoUnlockLabel = displayedUnlockAt <= 0L
                ? "⏰ Auto Unlock time not set"
                : (timerAlreadyActive
                    ? "⏰ Auto Unlock scheduled: " + formatAutoUnlockTime(displayedUnlockAt)
                    : "⏰ Selected time (press LOCK to apply): " + formatAutoUnlockTime(displayedUnlockAt));
            TextView autoUnlockInfo = text(autoUnlockLabel, 12, displayedUnlockAt > 0L ? GREEN : MUTED);
            autoUnlockInfo.setTypeface(Typeface.DEFAULT, displayedUnlockAt > 0L ? Typeface.BOLD : Typeface.NORMAL);
            card.addView(autoUnlockInfo, margin(-2, dp(18), 0, 0));

            Button chooseUnlockTime = button("⏰  SET / CHANGE AUTO UNLOCK TIME");
            chooseUnlockTime.setTextSize(12);
            chooseUnlockTime.setTextColor(DEEP);
            chooseUnlockTime.setBackground(bg(Color.rgb(238,242,255), 16));
            chooseUnlockTime.setOnClickListener(v -> chooseAutoUnlockTime(cid, name));
            card.addView(chooseUnlockTime, margin(-1, dp(44), 8, 0));

            Button childSettings = button("⚙️  Child Settings / App Protection");
            childSettings.setTextColor(DEEP);
            childSettings.setBackground(bg(Color.rgb(238,242,255),18));
            childSettings.setOnClickListener(v -> showChildSettingsInfo(name));
            card.addView(childSettings, margin(-1, dp(48), 8, 0));

            LinearLayout.LayoutParams cp=
                new LinearLayout.LayoutParams(-1,-2);
            cp.bottomMargin=dp(12);
            list.addView(card,cp);
        }

        if(kids.length()==0){
            LinearLayout empty=new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20),dp(30),dp(20),dp(30));
            empty.setBackground(bg(Color.WHITE,24));
            shadow(empty);

            TextView e=text("👨‍👩‍👧",48,TEXT);
            e.setGravity(Gravity.CENTER);
            empty.addView(e);

            TextView et=text("No children connected yet",16,TEXT);
            et.setGravity(Gravity.CENTER);
            et.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            empty.addView(et);

            TextView es=text("Tap + above to connect a child",13,MUTED);
            es.setGravity(Gravity.CENTER);
            empty.addView(es);

            list.addView(empty);
        }
        if (!trialCountdownViews.isEmpty()) {
            trialCountdownHandler.removeCallbacks(trialCountdownTick);
            trialCountdownHandler.postDelayed(trialCountdownTick, 1000L);
        }
    }

    private String formatTrialCountdown(long remainingMillis) {
        long totalSeconds = Math.max(0L, (remainingMillis + 999L) / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0L) {
            return String.format(Locale.getDefault(), "%dd %02d:%02d:%02d", days, hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", totalSeconds / 3600L, minutes, seconds);
    }

    private String formatAutoUnlockTime(long timestamp) {
        return java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, java.util.Locale.getDefault())
            .format(new java.util.Date(timestamp));
    }

    private void chooseAutoUnlockTime(String childId, String childName) {
        JSONObject child = findChildForAction(childId);
        String billingMessage = billingBlockReason(child, "LOCK");
        if (!billingMessage.isEmpty()) {
            Toast.makeText(this, billingMessage, Toast.LENGTH_LONG).show();
            return;
        }
        if (child != null && !child.optBoolean("hasLocal", true)) {
            Toast.makeText(this,
                "Timed Auto Unlock currently requires a locally paired Child. Cloud-only manual LOCK/UNLOCK remains available.",
                Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar defaultTime = (java.util.Calendar) now.clone();
        defaultTime.add(java.util.Calendar.MINUTE, 30);
        new TimePickerDialog(this, (timeView, hour, minute) -> {
            java.util.Calendar chosen = java.util.Calendar.getInstance();
            chosen.set(java.util.Calendar.HOUR_OF_DAY, hour);
            chosen.set(java.util.Calendar.MINUTE, minute);
            chosen.set(java.util.Calendar.SECOND, 0);
            chosen.set(java.util.Calendar.MILLISECOND, 0);
            if (chosen.getTimeInMillis() <= System.currentTimeMillis() + 1000L) {
                chosen.add(java.util.Calendar.DAY_OF_MONTH, 1);
            }
            long timestamp = chosen.getTimeInMillis();
            sp().edit().putLong(autoUnlockPreferenceKey(childId), timestamp).apply();
            Toast.makeText(this, "Auto Unlock time saved for " + childName + ". Press LOCK when ready.", Toast.LENGTH_LONG).show();
            refresh();
        }, defaultTime.get(java.util.Calendar.HOUR_OF_DAY), defaultTime.get(java.util.Calendar.MINUTE), false).show();
    }

    private void confirmDeleteOfflineChild(String childId,String childName){
        new AlertDialog.Builder(this)
            .setTitle("Remove " + childName + "?")
            .setMessage("This child is offline. Removing will disconnect this Child from your family and revoke its existing pairing token. The Child record is retained in the system history.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("REMOVE",(d,w)->deleteOfflineChild(childId))
            .show();
    }

    private void deleteOfflineChild(String childId){
        new Thread(()->{
            try{
                localRequest("DELETE",
                    "/local/children/"+childId,
                    null);
                runOnUiThread(()->{
                    Toast.makeText(this,"Child removed from Family",Toast.LENGTH_SHORT).show();
                    refresh();
                });
            }catch(Exception e){
                runOnUiThread(()->Toast.makeText(this,
                    e.getMessage()!=null?e.getMessage():"Unable to remove child",Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showFamilyCenter(){
        int total=currentKids!=null?currentKids.length():0;
        int onlineCount=0;
        for(int i=0;i<total;i++) onlineCount += currentKids.optJSONObject(i)!=null && currentKids.optJSONObject(i).optBoolean("online") ? 1 : 0;

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(10),dp(18),0);

        TextView summary=text(total+" child(ren)  •  "+onlineCount+" online",14,MUTED);
        summary.setGravity(Gravity.CENTER);
        box.addView(summary,margin(-1,-2,0,12));

        if(total==0){
            TextView empty=text("No children are connected yet.",15,TEXT);
            empty.setGravity(Gravity.CENTER);
            box.addView(empty,margin(-1,-2,0,12));
        }else{
            for(int i=0;i<total;i++){
                JSONObject c=currentKids.optJSONObject(i);
                if(c==null)continue;
                String n=c.optString("name","Child");
                boolean on=c.optBoolean("online");
                boolean locked=c.optBoolean("locked");
                LinearLayout r=row();
                TextView nm=text((on?"● ":"○ ")+n,15,TEXT);
                nm.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
                r.addView(nm,lp(0,-2));
                ((LinearLayout.LayoutParams)nm.getLayoutParams()).weight=1;
                TextView st=text(locked?"LOCKED":(on?"ONLINE":"OFFLINE"),12,on?GREEN:MUTED);
                r.addView(st);
                box.addView(r,margin(-1,-2,0,8));
            }
        }

        Button add=button("＋  Add Child");
        add.setBackground(gradient(BLUE,Color.rgb(65,185,255),18));
        add.setOnClickListener(v->{ showAddChild(); });
        box.addView(add,margin(-1,dp(50),8,0));

        new AlertDialog.Builder(this)
            .setTitle("👨‍👩‍👧 Family Center")
            .setView(box)
            .setPositiveButton("CLOSE",null)
            .show();
    }

    /** Keep role switching available on registration, sign-in, and dashboard settings. */
    private void addChangeRoleButton(LinearLayout parent){
        Button changeRole=button("🔁  Change Role");
        changeRole.setBackground(bg(Color.rgb(238,242,255),18));
        changeRole.setTextColor(DEEP);
        changeRole.setOnClickListener(v->confirmChangeRole());
        parent.addView(changeRole,margin(-1,dp(48),0,8));
    }

    private void confirmChangeRole(){
        new AlertDialog.Builder(this)
            .setTitle("Change Role")
            .setMessage("You are currently in Parent mode. This will stop the local Parent connection and return you to role selection. Your Parent account and cloud data will remain saved. If you choose Child, the previous Child pairing on this phone will be cleared so it can connect to the right Parent.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("CHANGE ROLE",(d,w)->{
                localRosterHandler.removeCallbacks(localRosterPoll);
                try { LocalParentService.stop(this); } catch(Exception ignored) {}
                getSharedPreferences("app_state",MODE_PRIVATE).edit()
                    .putInt("active_role",0)
                    .apply();
                Intent i=new Intent(this,MainActivity.class);
                i.putExtra("clear_old_child_link",true);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            })
            .show();
    }

    private void showParentSettings(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(6),dp(18),0);

        TextView accountInfo=text(
            "Account\n"+
            sp().getString("name","Parent")+"\n"+
            sp().getString("email","")+"\n"+
            sp().getString("mobile",""),
            14,TEXT);
        box.addView(accountInfo,margin(-1,-2,0,12));

        TextView architecture=text(
            "☁️  Parent account: Cloudflare LIVE\n"+
            "📱  Child control: Local Wi-Fi (automatic discovery)\n"+
            "🌐  No PC IP address is required",
            14,TEXT);
        box.addView(architecture,margin(-1,-2,0,12));

        Button test=button("☁️  Test Cloud Connection");
        test.setBackground(bg(Color.rgb(238,242,255),18));
        test.setTextColor(DEEP);
        test.setOnClickListener(v->testServerConnection(getServerUrl()));
        box.addView(test,margin(-1,dp(48),0,8));

        Button testLocal=button("📱  Test Parent Local Link");
        testLocal.setBackground(bg(Color.rgb(238,242,255),18));
        testLocal.setTextColor(DEEP);
        testLocal.setOnClickListener(v->testLocalConnection());
        box.addView(testLocal,margin(-1,dp(48),0,8));

        Button recovery=button("🔑  View Recovery Code");
        recovery.setBackground(bg(Color.rgb(238,242,255),18));
        recovery.setTextColor(DEEP);
        recovery.setOnClickListener(v->showRecoveryCode());
        box.addView(recovery,margin(-1,dp(48),0,8));

        Button change=button("🔒  Change Password");
        change.setBackground(bg(Color.rgb(238,242,255),18));
        change.setTextColor(DEEP);
        change.setOnClickListener(v->showChangePassword());
        box.addView(change,margin(-1,dp(48),0,8));

        addChangeRoleButton(box);

        Button signout=button("Sign Out");
        signout.setBackground(gradient(RED,Color.rgb(255,125,135),18));
        signout.setOnClickListener(v->{
            localRosterHandler.removeCallbacks(localRosterPoll);
            try { LocalParentService.stop(this); } catch(Exception ignored) {}
            sp().edit().remove("token").apply();
            token="";
            Toast.makeText(this,"Signed out",Toast.LENGTH_SHORT).show();
            showLogin();
        });
        box.addView(signout,margin(-1,dp(48),0,0));

        new AlertDialog.Builder(this)
            .setTitle("⚙ Parent Settings")
            .setView(box)
            .setPositiveButton("CLOSE",null)
            .show();
    }

    private void testServerConnection(String base){
        if(base==null || base.isEmpty()){
            Toast.makeText(this,"Enter a server address first",Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(()->{
            try{
                Net.request("GET",base,"/api/health",null,null,null);
                runOnUiThread(()->Toast.makeText(this,"✓ Connection successful",Toast.LENGTH_SHORT).show());
            }catch(Exception e){
                runOnUiThread(()->Toast.makeText(this,"Connection failed: "+(e.getMessage()!=null?e.getMessage():"Unknown error"),Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void testLocalConnection(){
        try { LocalParentService.start(this); } catch(Exception ignored) {}
        new Thread(()->{
            Exception last = null;
            for(int attempt=0; attempt<8; attempt++){
                try{
                    JSONObject health = localRequest("GET","/local/health",null);
                    if(health.optBoolean("ok",false)){
                        runOnUiThread(()->Toast.makeText(this,"✓ Parent local link is READY on port 8080",Toast.LENGTH_LONG).show());
                        return;
                    }
                    last = new Exception("Health endpoint did not return ok=true");
                }catch(Exception e){ last = e; }
                try{ Thread.sleep(350L); }catch(InterruptedException stop){ Thread.currentThread().interrupt(); break; }
            }
            String detail = last != null && last.getMessage()!=null ? last.getMessage() : "No health response";
            runOnUiThread(()->Toast.makeText(this,"⚠ Parent local link is NOT ready: " + detail,Toast.LENGTH_LONG).show());
        }).start();
    }

    private void showRecoveryCode(){
        new Thread(()->{
            try{
                JSONObject j=Net.request("GET",getServerUrl(),"/api/parent/recovery-code","x-parent-token",token,null);
                String code=j.optString("recoveryCode","");
                runOnUiThread(()->new AlertDialog.Builder(this)
                    .setTitle("🔑 Recovery Code")
                    .setMessage("Keep this code somewhere safe. It is required to reset your Parent password if you forget it.\n\n"+code)
                    .setPositiveButton("OK",null)
                    .show());
            }catch(Exception e){
                runOnUiThread(()->Toast.makeText(this,"Unable to load recovery code",Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showRecoveryCodeAfterRegistration(String prefillEmail,String recoveryCode){
                sp().edit().putString("registered_email", prefillEmail).apply();
        String message="Account created successfully.\n\nSave your Recovery Code:\n\n"+recoveryCode+"\n\nYou will need this code if you forget your password.";
        new AlertDialog.Builder(this)
            .setTitle("✓ Parent Account Created")
            .setMessage(message)
            .setPositiveButton("CONTINUE TO SIGN IN",(d,w)->{ showLogin(); if(email!=null) email.setText(prefillEmail); })
            .setCancelable(false)
            .show();
    }

    private void showForgotPassword(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(4),dp(18),0);
        EditText e=new EditText(this); e.setHint("Registered email"); e.setSingleLine(true); e.setInputType(33); box.addView(e,margin(-1,dp(52),0,8));
        EditText m=new EditText(this); m.setHint("Mobile number"); m.setSingleLine(true); m.setInputType(3); box.addView(m,margin(-1,dp(52),0,8));
        EditText c=new EditText(this); c.setHint("Recovery code"); c.setSingleLine(true); c.setAllCaps(true); box.addView(c,margin(-1,dp(52),0,8));
        EditText np=new EditText(this); np.setHint("New password"); np.setSingleLine(true); np.setInputType(129); box.addView(np,margin(-1,dp(52),0,8));
        EditText cp=new EditText(this); cp.setHint("Confirm new password"); cp.setSingleLine(true); cp.setInputType(129); box.addView(cp,margin(-1,dp(52),0,0));

        AlertDialog d=new AlertDialog.Builder(this)
            .setTitle("Reset Parent Password")
            .setView(box)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("RESET PASSWORD",null)
            .create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String p1=np.getText().toString(), p2=cp.getText().toString();
            if(p1.length()<8){np.setError("At least 8 characters");return;}
            if(!p1.equals(p2)){cp.setError("Passwords do not match");return;}
            new Thread(()->{
                try{
                    Net.request("POST",getServerUrl(),"/api/parent/forgot-password",null,null,
                        new JSONObject().put("email",e.getText().toString().trim())
                            .put("mobile",m.getText().toString().trim())
                            .put("recoveryCode",c.getText().toString().trim())
                            .put("newPassword",p1));
                    runOnUiThread(()->{d.dismiss(); Toast.makeText(this,"Password reset successfully. Please sign in again.",Toast.LENGTH_LONG).show(); showLogin();});
                }catch(Exception ex){runOnUiThread(()->Toast.makeText(this,ex.getMessage()!=null?ex.getMessage():"Password reset failed",Toast.LENGTH_LONG).show());}
            }).start();
        }));
        d.show();
    }

    private void showChangePassword(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(4),dp(18),0);
        EditText old=new EditText(this); old.setHint("Current password"); old.setSingleLine(true); old.setInputType(129); box.addView(old,margin(-1,dp(52),0,8));
        EditText np=new EditText(this); np.setHint("New password"); np.setSingleLine(true); np.setInputType(129); box.addView(np,margin(-1,dp(52),0,8));
        EditText cp=new EditText(this); cp.setHint("Confirm new password"); cp.setSingleLine(true); cp.setInputType(129); box.addView(cp,margin(-1,dp(52),0,0));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Change Password").setView(box).setNegativeButton("CANCEL",null).setPositiveButton("SAVE",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String p1=np.getText().toString(), p2=cp.getText().toString();
            if(p1.length()<8){np.setError("At least 8 characters");return;}
            if(!p1.equals(p2)){cp.setError("Passwords do not match");return;}
            new Thread(()->{
                try{
                    JSONObject j=Net.request("POST",getServerUrl(),"/api/parent/change-password","x-parent-token",token,
                        new JSONObject().put("currentPassword",old.getText().toString()).put("newPassword",p1));
                    token=j.getString("token"); sp().edit().putString("token",token).apply();
                    runOnUiThread(()->{d.dismiss(); Toast.makeText(this,"Password changed",Toast.LENGTH_SHORT).show();});
                }catch(Exception ex){runOnUiThread(()->Toast.makeText(this,ex.getMessage()!=null?ex.getMessage():"Password change failed",Toast.LENGTH_LONG).show());}
            }).start();
        }));
        d.show();
    }

    private void showChildBackgroundHelp(String childName) {
        String message =
            "Gawin ang mga hakbang na ito sa mismong CHILD PHONE ("
            + childName + "), hindi sa Parent phone.\n\n"
            + "TECNO / HiOS:\n"
            + "1. Buksan ang Settings.\n"
            + "2. Piliin ang Battery.\n"
            + "3. Buksan ang Battery Lab.\n"
            + "4. Piliin ang Battery Saving Settings.\n"
            + "5. I-OFF ang switch na \"Power Saving Management For Apps\".\n\n"
            + "Dagdag na setting:\n"
            + "App Info > Battery Usage > Unrestricted.\n\n"
            + "Pagkatapos, buksan ang Child app at tiyaking ONLINE sa Parent.\n"
            + "Huwag i-Force stop ang app.\n\n"
            + "Tandaan: Ang Parent phone ay hindi direktang makapagbubukas ng "
            + "system settings sa hiwalay na Child phone. Sundan ang gabay "
            + "na ito gamit ang mismong Child phone.";

        new AlertDialog.Builder(this)
            .setTitle("Ayusin ang Child Phone")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }
    private void showChildSettingsInfo(String childName){
        new AlertDialog.Builder(this)
            .setTitle("⚙️ Child Settings")
            .setMessage(
                "App Protection is configured on the Child phone. " +
                "Open the Child app, then tap Settings → App Protection / Hide App Settings.\n\n" +
                "This Parent option is only a quick guide/status entry; it does not change Android system settings remotely."
            )
            .setPositiveButton("OK", null)
            .show();
    }

    private void showAddChild(){

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(8),dp(20),0);

        TextView icon=text("👶",45,TEXT);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);

        TextView title=text("Connect Child",21,TEXT);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        box.addView(title);

        TextView info=text(
            "Enter the 6-digit code shown on the child's phone. Local Wi-Fi is primary; Cloud is available only if the Child enabled the optional fallback.",
            13,MUTED);
        info.setGravity(Gravity.CENTER);
        box.addView(info);

        pairCode=new EditText(this);
        pairCode.setHint("6-digit code");
        pairCode.setInputType(2);
        pairCode.setGravity(Gravity.CENTER);
        pairCode.setTextSize(24);
        pairCode.setLetterSpacing(.12f);
        pairCode.setBackground(bg(Color.rgb(245,247,253),18));
        box.addView(pairCode,margin(-1,dp(60),15,5));

        AlertDialog d=new AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("CONNECT",null)
            .create();

        d.setOnShowListener(x->{
            d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v->{
                    addChild();
                    d.dismiss();
                });
        });

        d.show();
    }

    private void addChild(){
        String code=pairCode.getText().toString().trim();
        if(code.length()!=6){
            Toast.makeText(this, "Enter the 6-digit child code", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(()->{
            boolean localPaired = false;
            boolean cloudPaired = false;
            boolean cloudAllowed = false;
            String localError = "";
            String cloudError = "";
            try {
                JSONObject localResult = localRequest("POST", "/local/pair", new JSONObject().put("code", code));
                localPaired = true;
                JSONObject localChild = localResult.optJSONObject("child");
                // Explicit opt-in is required for a local Child to use both channels.
                // If no local Child exists, this may be a Cloud-only Child and Cloud pairing is needed.
                cloudAllowed = localChild != null && (!localChild.has("cloudFallbackEnabled")
                    || localChild.optBoolean("cloudFallbackEnabled", false));
            } catch(Exception e) { localError = e.getMessage() == null ? "Local pairing unavailable" : e.getMessage(); }
            if (!localPaired || cloudAllowed) {
                try {
                    Net.request("POST", getServerUrl(), "/api/pair", "x-parent-token", token,
                        new JSONObject().put("code", code));
                    cloudPaired = true;
                } catch(Exception e) { cloudError = e.getMessage() == null ? "Cloud pairing unavailable" : e.getMessage(); }
            }
            final boolean lp = localPaired, cp = cloudPaired;
            final String detail = !localError.isEmpty() && !cloudError.isEmpty() ? (localError + " / " + cloudError) : (!localError.isEmpty() ? localError : cloudError);
            runOnUiThread(()->{
                if(lp || cp) {
                    String message = lp && cp ? "✓ Child connected locally and through Cloud fallback"
                        : (lp ? "✓ Child connected on Local Wi-Fi (Cloud fallback not enabled)"
                            : "✓ Child connected through Cloud (internet required)");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    refresh();
                } else {
                    Toast.makeText(this, "Unable to connect Child: " + detail, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void commandAll(JSONArray kids,String cmd){
        if(kids==null || kids.length()==0){
            Toast.makeText(this, "No child is connected yet", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(()->{
            int success=0;
            for(int i=0;i<kids.length();i++){
                JSONObject c=kids.optJSONObject(i);
                if(c==null) continue;
                String id=c.optString("childId", "");
                success += sendCommandForChild(c, id, cmd) > 0 ? 1 : 0;
            }
            final int count=success;
            runOnUiThread(()->{
                Toast.makeText(this,
                    count>0 ? (cmd.equals("LOCK") ? "🔒 Lock sent to "+count+" child(ren)" : "🔓 Unlock sent to "+count+" child(ren)")
                        : "No command could be delivered. Check local Wi-Fi or cloud internet.",
                    Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private void command(String cid,String cmd){
        JSONObject knownChild = findChildForAction(cid);
        String billingMessage = billingBlockReason(knownChild, cmd);
        if (!billingMessage.isEmpty()) {
            Toast.makeText(this, billingMessage, Toast.LENGTH_LONG).show();
            return;
        }
        long selectedUnlockAt = selectedAutoUnlockAt(cid, cmd);
        new Thread(()->{
            JSONObject child = findChildForAction(cid);
            int count = sendCommandForChild(child, cid, cmd, selectedUnlockAt);
            final int delivered = count;
            runOnUiThread(()->{
                String message;
                if (delivered <= 0) {
                    message = selectedUnlockAt > 0L
                        ? "Auto Unlock was not applied. Reconnect the locally paired Child and retry."
                        : "Command failed. Check local Wi-Fi or cloud internet.";
                } else if ("LOCK".equalsIgnoreCase(cmd) && selectedUnlockAt > 0L) {
                    message = "🔒 Child locked. Auto Unlock: " + formatAutoUnlockTime(selectedUnlockAt);
                } else {
                    message = "LOCK".equalsIgnoreCase(cmd)
                        ? "🔒 Bedtime lock sent"
                        : "🔓 Child unlocked; Auto Unlock schedule cancelled";
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private void refreshLocalChildrenOnly(){
        if (localRosterRequestInFlight || token.isEmpty() || list == null || currentParentInfo.length() == 0) return;
        localRosterRequestInFlight = true;
        new Thread(() -> {
            JSONArray localKids = new JSONArray();
            try { localKids = localRequest("GET", "/local/children", null).getJSONArray("children"); }
            catch (Exception e) { android.util.Log.w("BedtimeParent", "Local roster unavailable: " + e.getMessage()); }
            syncLocalPairingsToCloud(localKids);
            JSONArray cloudKids = fetchCloudChildren(false);
            JSONArray kids = mergeChildLists(localKids, cloudKids);
            JSONObject parentInfo = currentParentInfo;
            JSONArray finalKids = kids;
            runOnUiThread(() -> {
                if (!isFinishing() && !token.isEmpty() && list != null) {
                    currentKids = finalKids;
                    render(parentInfo, finalKids);
                }
            });
            localRosterRequestInFlight = false;
        }, "bedtime-parent-roster-refresh").start();
    }

    private void refresh(){

        new Thread(()->{
            try{
                // Account/subscription comes from Cloudflare.
                JSONObject me=Net.request(
                    "GET",getServerUrl(),"/api/me",
                    "x-parent-token",token,null);

                JSONObject p=me.getJSONObject("parent");
                JSONArray kids = new JSONArray();
                Exception localError = null;

                // Child roster/status/control comes from the Parent phone's local Wi-Fi server.
                // Foreground service startup is asynchronous, so retry long enough for it to bind.
                for(int attempt=0; attempt<8; attempt++){
                    try{
                        kids = localRequest("GET","/local/children",null).getJSONArray("children");
                        localError = null;
                        break;
                    }catch(Exception e){
                        localError = e;
                        if(attempt < 7){
                            try{ Thread.sleep(350L); }
                            catch(InterruptedException stop){ Thread.currentThread().interrupt(); break; }
                        }
                    }
                }

                syncLocalPairingsToCloud(kids);
                JSONArray cloudKids = fetchCloudChildren(true);
                JSONArray combinedKids = mergeChildLists(kids, cloudKids);
                final JSONArray finalKids = combinedKids;
                final Exception finalLocalError = localError;
                runOnUiThread(()->{
                    currentParentInfo = p;
                    currentKids = finalKids;
                    render(p,finalKids);
                    if(finalLocalError != null && cloudKids.length() == 0){
                        Toast.makeText(this,
                            "Local link unavailable. Cloud connection requires internet; same-Wi-Fi control can work offline.",
                            Toast.LENGTH_LONG).show();
                    }
                });

            }catch(Exception e){
                runOnUiThread(()->{
                    if(account!=null) account.setText("Parent");
                    Toast.makeText(this,
                        "Cloud connection unavailable",
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showPaymentMenu(){

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(8),dp(20),0);

        TextView ic=text("💳",45,TEXT);
        ic.setGravity(Gravity.CENTER);
        box.addView(ic);

        TextView title=text("Family Payment",21,TEXT);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        box.addView(title);

        TextView info=text(
            "First Child: free for 24 hours after pairing.\nSecond Child: ₱300. First Child becomes ₱100 if the second was paid during the first trial; otherwise ₱300 after trial expiry. Third and later Children: ₱50 each.\n\nPayment is checked by the server; do not submit duplicate payments while under review.",
            13,MUTED);
        info.setGravity(Gravity.CENTER);
        box.addView(info,margin(-1,-2,8,10));
        new Thread(() -> {
            try {
                JSONObject billing = Net.request("GET", getServerUrl(), "/api/billing/summary", "x-parent-token", token, null).optJSONObject("billing");
                if (billing == null) return;
                JSONObject due = billing.optJSONObject("nextDue");
                String current;
                if (due != null) {
                    current = "NEXT PAYMENT: ₱" + due.optInt("amountDue", 0) + " for " + due.optString("name", "Child") + "\n\n";
                } else if (billing.optBoolean("trialActive", false)) {
                    JSONObject trial = billing.optJSONObject("trialChild");
                    current = "FREE TRIAL ACTIVE" + (trial != null && !trial.optString("trialEndsAt", "").isEmpty() ? " until " + trial.optString("trialEndsAt") : "") + "\n\n";
                } else if (billing.optInt("childCount", 0) == 0) {
                    current = "No Child paired yet. Trial has not started.\n\n";
                } else {
                    current = "No payment due now.\n\n";
                }
                final String display = current;
                runOnUiThread(() -> info.setText(display + "First Child: free for 24 hours after pairing. Second Child: ₱300. First Child becomes ₱100 if the second was paid during the first trial; otherwise ₱300 after trial expiry. Third and later Children: ₱50 each.\n\nPayment is checked by the server; do not submit duplicate payments while under review."));
            } catch (Exception e) {
                android.util.Log.d("BedtimeParent", "Billing summary unavailable: " + e.getMessage());
            }
        }, "bedtime-billing-summary").start();

        Button checkout=button("🔒  Pay Securely with PayMongo");
        checkout.setBackground(gradient(Color.rgb(24,145,91),Color.rgb(70,205,132),18));
        box.addView(checkout,margin(-1,dp(52),8,8));

        Button qr=button("💳  Show Payment QR / Manual Transfer");
        qr.setBackground(gradient(BLUE,Color.rgb(90,150,255),18));
        box.addView(qr,margin(-1,dp(52),8,8));
        qr.setVisibility(android.view.View.GONE);

        Button submit=button("🧾  Payment Details / Submit Proof");
        submit.setBackground(gradient(Color.rgb(80,190,120),Color.rgb(50,215,130),18));
        box.addView(submit,margin(-1,dp(52),0,0));
        submit.setVisibility(android.view.View.GONE);

        AlertDialog d=new AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("CLOSE",null)
            .create();

        checkout.setOnClickListener(v->{ d.dismiss(); startPayMongoCheckout(); });
        qr.setOnClickListener(v->{ d.dismiss(); showQr(); });
        submit.setOnClickListener(v->{ d.dismiss(); showPaymentForm(); });
        d.show();
    }

    private void showPaymentForm(){
        selectedProofBase64="";
        selectedProofName="";
        selectedProofMimeType="";

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(8),dp(20),0);

        TextView instruction=text(
            "After making your payment, enter your transaction/reference number and upload your proof of payment.\n\nPlease wait a few minutes for system verification after submitting.",
            13,MUTED);
        box.addView(instruction,margin(-1,-2,0,12));

        EditText reference=new EditText(this);
        reference.setHint("Transaction / Reference Number");
        reference.setSingleLine(true);
        reference.setPadding(dp(14),0,dp(14),0);
        reference.setBackground(bg(Color.rgb(246,248,253),16));
        box.addView(reference,margin(-1,dp(52),0,10));

        Button proof=button("📷  Select Proof of Payment");
        proof.setBackground(gradient(BLUE,Color.rgb(90,150,255),18));
        box.addView(proof,margin(-1,dp(52),0,6));

        TextView proofName=text("No proof selected",12,MUTED);
        proofName.setGravity(Gravity.CENTER);
        box.addView(proofName,margin(-1,-2,0,10));

        TextView note=text(
            "Payment will stay UNDER REVIEW while the system processes the submitted details.\nDo not submit another payment.",
            12,MUTED);
        note.setGravity(Gravity.CENTER);
        box.addView(note);

        AlertDialog d=new AlertDialog.Builder(this)
            .setTitle("🧾 Payment Proof")
            .setView(box)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("SUBMIT PAYMENT",null)
            .create();

        proof.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i,PICK_PAYMENT_PROOF);
        });

        d.setOnShowListener(x->{
            Button ok=d.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v->{
                String ref=reference.getText().toString().trim();
                if(ref.isEmpty()){
                    reference.setError("Enter the transaction/reference number");
                    return;
                }
                if(selectedProofBase64.isEmpty()){
                    Toast.makeText(this,"Please select your proof of payment.",Toast.LENGTH_LONG).show();
                    return;
                }
                d.dismiss();
                submitPayment(ref,selectedProofName,selectedProofBase64);
            });
        });
        d.show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_PAYMENT_PROOF || resultCode!=RESULT_OK || data==null || data.getData()==null)return;
        try{
            Uri uri=data.getData();
            InputStream in=getContentResolver().openInputStream(uri);
            if(in==null)throw new Exception("Unable to open image");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[8192]; int n,total=0;
            while((n=in.read(buf))!=-1){
                total+=n;
                if(total>5*1024*1024)throw new Exception("Proof image must not exceed 5 MB");
                out.write(buf,0,n);
            }
            in.close();
            selectedProofBase64=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
            String name=uri.getLastPathSegment();
            selectedProofName=(name==null||name.isEmpty())?"payment-proof.jpg":name;
            String mime=getContentResolver().getType(uri);
            if(mime==null || mime.isEmpty()){
                String lower=selectedProofName.toLowerCase(Locale.ROOT);
                mime=lower.endsWith(".png")?"image/png":(lower.endsWith(".webp")?"image/webp":"image/jpeg");
            }
            selectedProofMimeType=mime.toLowerCase(Locale.ROOT);
            Toast.makeText(this,"✓ Proof selected",Toast.LENGTH_SHORT).show();
        }catch(Exception e){
            selectedProofBase64="";
            selectedProofName="";
            selectedProofMimeType="";
            Toast.makeText(this,e.getMessage()!=null?e.getMessage():"Unable to read proof",Toast.LENGTH_LONG).show();
        }
    }

    private void showQr(){

        ImageView qrImage=new ImageView(this);
        qrImage.setImageResource(
            R.drawable.payment_qr_instapay);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrImage.setAdjustViewBounds(true);

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(12),dp(12),dp(12),dp(12));

        TextView label=text(
            "Scan the QR, then submit proof. The server calculates the correct Child fee (₱300, ₱100 or ₱50).",
            16,TEXT);
        label.setGravity(Gravity.CENTER);
        box.addView(label);

        box.addView(qrImage,
            new LinearLayout.LayoutParams(
                -1,dp(360)));

        new AlertDialog.Builder(this)
            .setTitle("💳 Payment")
            .setView(box)
            .setPositiveButton("CLOSE",null)
            .show();
    }

    private void startPayMongoCheckout(){
        new Thread(()->{
            try{
                JSONObject result=Net.request(
                    "POST",getServerUrl(),"/api/payment/checkout",
                    "x-parent-token",token,new JSONObject());
                String checkoutUrl=result.optString("checkoutUrl","").trim();
                JSONObject payment=result.optJSONObject("payment");
                String paymentId=payment!=null?payment.optString("paymentId",""):"";
                int amount=payment!=null?payment.optInt("amount",0):0;
                if(checkoutUrl.isEmpty())throw new Exception("Checkout URL was not returned by the server.");
                Uri uri=Uri.parse(checkoutUrl);
                if(!"https".equalsIgnoreCase(uri.getScheme()) || !"checkout.paymongo.com".equalsIgnoreCase(uri.getHost())){
                    throw new Exception("The server returned an untrusted checkout URL. Please contact support.");
                }
                if(!paymentId.isEmpty())sp().edit().putString("pendingPaymentId",paymentId).apply();
                runOnUiThread(()->{
                    Toast.makeText(this,"Opening secure PayMongo checkout…",Toast.LENGTH_LONG).show();
                    try{
                        Intent browser=new Intent(Intent.ACTION_VIEW,uri);
                        startActivity(browser);
                        new AlertDialog.Builder(this)
                            .setTitle("🔒 PayMongo Checkout")
                            .setMessage("Complete the payment in the secure PayMongo page.\n\n"+
                                (amount>0?"Amount: ₱"+amount+"\n\n":"")+
                                "The account will activate only after the server verifies PayMongo’s signed payment webhook. Returning from the checkout page alone does not activate the account.")
                            .setPositiveButton("OK",null).show();
                    }catch(Exception e){
                        Toast.makeText(this,"Unable to open checkout: "+(e.getMessage()!=null?e.getMessage():"Unknown error"),Toast.LENGTH_LONG).show();
                    }
                });
            }catch(Exception e){
                String message=e.getMessage()!=null?e.getMessage():"PayMongo checkout is unavailable.";
                runOnUiThread(()->new AlertDialog.Builder(this)
                    .setTitle("PayMongo Checkout")
                    .setMessage(message+"\n\nNo payment was marked as approved by this action.")
                    .setPositiveButton("OK",null).show());
            }
        },"bedtime-paymongo-checkout").start();
    }

    private void submitPayment(String reference,String proofName,String proofBase64){
        new Thread(()->{
            try{
                JSONObject body=new JSONObject()
                    .put("reference",reference)
                    .put("amount",300)
                    .put("proofName",proofName)
                    .put("proofMimeType",selectedProofMimeType)
                    .put("proofBase64",proofBase64);
                JSONObject j=Net.request(
                    "POST",getServerUrl(),
                    "/api/payment/submit","x-parent-token",token,body);
                JSONObject payment = j.optJSONObject("payment");
                String paymentId = payment != null ? payment.optString("paymentId", "") : "";
                String childName = payment != null ? payment.optString("childName", "Child") : "Child";
                int amount = payment != null ? payment.optInt("amount", 0) : 0;
                sp().edit().putString("pendingPaymentId",paymentId).apply();
                runOnUiThread(()->showPaymentPending(amount, childName));
            }catch(Exception e){
                runOnUiThread(()->Toast.makeText(this,
                    e.getMessage()!=null?e.getMessage():"Payment unavailable",Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showPaymentPending(int amount, String childName){
        new AlertDialog.Builder(this)
            .setTitle("⏳ Payment Under Review")
            .setMessage("Payment submitted for " + childName + (amount > 0 ? " (₱" + amount + ")" : "") + ".\n\nPlease wait while the system verifies your payment.\n\nDo not submit another payment for the same Child while it is under review.")
            .setPositiveButton("OK",null)
            .show();
    }

    @Override protected void onDestroy(){
        localRosterHandler.removeCallbacks(localRosterPoll);
        trialCountdownHandler.removeCallbacks(trialCountdownTick);
        trialCountdownViews.clear();
        super.onDestroy();
    }

}
