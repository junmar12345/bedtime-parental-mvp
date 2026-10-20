package com.master.bedtime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.speech.tts.TextToSpeech;
import android.widget.ScrollView;
import android.widget.Toast;
import java.util.Locale;
import java.util.Random;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import org.json.JSONObject;
import com.master.bedtime.practice.BuildConfig;

public class MainActivity extends Activity {
    private static final int REQUEST_NEARBY_WIFI = 2201;
    private int pendingRole = 0; // 1 = Parent, 2 = Child
    // Welcome + RandomVerse are shown every time the launcher activity opens.
    private TextToSpeech welcomeTts;
    private boolean welcomeTtsReady = false;
    private boolean welcomeScreenShowing = false;
    private String currentVerseSpeech = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        showWelcomeScreen();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void showWelcomeScreen() {
        welcomeScreenShowing = true;

        String[] verse = chooseRandomWelcomeVerse();
        currentVerseSpeech = verse[1] + " " + verse[0] + ".";

        int red = Color.rgb(139, 0, 0);
        int ink = Color.rgb(54, 43, 36);
        int muted = Color.rgb(112, 95, 78);
        int background = Color.rgb(248, 243, 232);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(background);

        TextView brand = welcomeText("BEDTIME PARENTAL", 13, red, true, Gravity.CENTER);
        root.addView(brand, welcomeLp());

        TextView title = welcomeText(
            "Welcome sa Mundo ni Master J", 27, red, true, Gravity.CENTER);
        root.addView(title, welcomeLp());

        TextView subtitle = welcomeText(
            "Gabay para sa mas ligtas, malusog, at balanseng paggamit ng gadgets sa pamilya.",
            16, ink, false, Gravity.CENTER);
        root.addView(subtitle, welcomeLp());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(22));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.WHITE);
        cardBackground.setCornerRadius(dp(20));
        card.setBackground(cardBackground);

        TextView verseHeading = welcomeText(
            "PAALALA NG SALITA NG DIYOS", 14, red, true, Gravity.CENTER);
        card.addView(verseHeading, welcomeLp());

        TextView verseText = welcomeText(
            verse[1], 20, ink, false, Gravity.CENTER);
        verseText.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        card.addView(verseText, welcomeLp());

        TextView reference = welcomeText(
            verse[0], 15, red, true, Gravity.CENTER);
        card.addView(reference, welcomeLp());

        LinearLayout.LayoutParams cardParams =
            new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(22);
        root.addView(card, cardParams);

        TextView reminder = welcomeText(
            "Ang pagmamahal, mabuting halimbawa, at pag-uusap ng pamilya ay mahalaga.",
            14, muted, false, Gravity.CENTER);
        root.addView(reminder, welcomeLp());

        Button listen = new Button(this);
        listen.setText("PAKINGGAN MULI");
        root.addView(listen, welcomeLp());
        listen.setOnClickListener(v -> speakWelcomeVerse());

        Button stop = new Button(this);
        stop.setText("IHINTO ANG AUDIO");
        root.addView(stop, welcomeLp());
        stop.setOnClickListener(v -> {
            if (welcomeTts != null) welcomeTts.stop();
        });

        Button proceed = new Button(this);
        proceed.setText("MAGPATULOY");
        GradientDrawable proceedBackground = new GradientDrawable();
        proceedBackground.setColor(red);
        proceedBackground.setCornerRadius(dp(14));
        proceed.setBackground(proceedBackground);
        proceed.setTextColor(Color.WHITE);
        root.addView(proceed, welcomeLp());
        proceed.setOnClickListener(v -> continueFromWelcome());

        scroll.addView(root);
        setContentView(scroll);

        // Try Filipino first, then Tagalog. Audio failure never blocks Continue.
        welcomeTts = new TextToSpeech(this, status -> {
            if (!welcomeScreenShowing || isFinishing()) return;

            if (status != TextToSpeech.SUCCESS || welcomeTts == null) {
                Toast.makeText(this,
                    "Hindi available ang audio reading sa device na ito.",
                    Toast.LENGTH_LONG).show();
                return;
            }

            int languageStatus = welcomeTts.setLanguage(new Locale("fil", "PH"));
            if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
                languageStatus = welcomeTts.setLanguage(new Locale("tl", "PH"));
            }

            if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
                welcomeTtsReady = false;
                Toast.makeText(this,
                    "Walang Filipino/Tagalog voice. Maaari pa ring magpatuloy.",
                    Toast.LENGTH_LONG).show();
                return;
            }

            welcomeTts.setSpeechRate(0.90f);
            welcomeTtsReady = true;
            speakWelcomeVerse();
        });
    }

    private String[] chooseRandomWelcomeVerse() {
        String[][] verses = {
            {"Kawikaan 22:6",
                "Turuan mo ang bata sa daan na dapat niyang lakaran, at pagka tumanda man siya ay hindi niya hihiwalayan."},
            {"Efeso 6:1",
                "Mga anak, magsitalima kayo sa inyong mga magulang sa Panginoon: sapagka't ito'y matuwid."},
            {"Kawikaan 1:8",
                "Anak ko, dinggin mo ang turo ng iyong ama, at huwag mong pabayaan ang kautusan ng iyong ina."},
            {"Awit 127:3",
                "Narito, ang mga anak ay mana na mula sa Panginoon: at ang bunga ng bahay-bata ay kagantihan niya."},
            {"Efeso 6:4",
                "Huwag ninyong ipamungkahi sa galit ang inyong mga anak: kundi inyong palakihin sila sa aral at pangaral ng Panginoon."},
            {"Colosas 3:20",
                "Mga anak, magsitalima kayo sa inyong mga magulang sa lahat ng mga bagay: sapagka't ito'y nakalulugod sa Panginoon."},
            {"Kawikaan 15:1",
                "Ang malumanay na sagot ay nakapapawi ng poot: nguni't ang salitang mapang-uyam ay nakapupukaw ng galit."},
            {"Josue 1:9",
                "Magpakalakas ka at magpakatapang na mabuti; huwag kang matakot: sapagka't ang Panginoon mong Dios ay sumasaiyo saan ka man pumaroon."}
        };

        android.content.SharedPreferences prefs =
            getSharedPreferences("welcome_bible", MODE_PRIVATE);

        int previous = prefs.getInt("last_verse_index", -1);
        Random random = new Random();
        int index = random.nextInt(verses.length);

        if (verses.length > 1 && index == previous) {
            index = (index + 1 + random.nextInt(verses.length - 1)) % verses.length;
        }

        prefs.edit().putInt("last_verse_index", index).apply();
        return verses[index];
    }

    private void speakWelcomeVerse() {
        if (welcomeTts == null || !welcomeTtsReady) {
            Toast.makeText(this,
                "Hindi pa handa ang Filipino/Tagalog audio voice.",
                Toast.LENGTH_SHORT).show();
            return;
        }
        welcomeTts.speak(
            currentVerseSpeech,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "masterj_welcome_bible_verse"
        );
    }

    private void continueFromWelcome() {
        welcomeScreenShowing = false;
        stopWelcomeTts();
        int savedRole = getSharedPreferences("app_state", MODE_PRIVATE).getInt("active_role", 0);
        if (savedRole == 1 || savedRole == 2) {
            launchRole(savedRole);
            return;
        }
        showRoleSelection();
    }

    private void showRoleSelection() {
        welcomeScreenShowing = false;
        stopWelcomeTts();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("BEDTIME PARENTAL");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(139, 0, 0));
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("ONE APP - PARENT + CHILD");
        sub.setTextSize(16);
        sub.setGravity(Gravity.CENTER);

        Button parent = new Button(this);
        parent.setText("PARENT");

        Button child = new Button(this);
        child.setText("CHILD");

        TextView note = new TextView(this);
        note.setText(
            "Choose the role for this phone. You can use Change Role later. " +
            "Nearby devices permission helps Bedtime find the Parent on local Wi-Fi.");
        note.setPadding(0, 24, 0, 0);

        root.addView(title, lp());
        root.addView(sub, lp());
        root.addView(parent, lp());
        root.addView(child, lp());
        root.addView(note, lp());
        setContentView(root);

        parent.setOnClickListener(v -> requestPermissionThenLaunch(1));
        child.setOnClickListener(v -> requestPermissionThenLaunch(2));
    }

    private TextView welcomeText(
        String text, float size, int color, boolean bold, int gravity) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(gravity);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams welcomeLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(12);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void stopWelcomeTts() {
        if (welcomeTts != null) {
            try {
                welcomeTts.stop();
                welcomeTts.shutdown();
            } catch (Exception ignored) {}
            welcomeTts = null;
        }
        welcomeTtsReady = false;
    }

    @Override protected void onDestroy() {
        welcomeScreenShowing = false;
        stopWelcomeTts();
        super.onDestroy();
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = 18;
        return p;
    }

    private void requestPermissionThenLaunch(int role) {
        pendingRole = role;
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQUEST_NEARBY_WIFI);
            return;
        }
        launchRole(role);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NEARBY_WIFI) return;
        int role = pendingRole;
        pendingRole = 0;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            launchRole(role);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Nearby devices permission")
            .setMessage("Bedtime needs Nearby devices permission to automatically discover the Parent phone on the same Wi-Fi. Without it, local pairing and LOCK/UNLOCK may not work. You can allow it in Android app settings.")
            .setPositiveButton("OPEN SETTINGS", (dialog, which) -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            })
            .setNegativeButton("CONTINUE", (dialog, which) -> launchRole(role))
            .show();
    }

    private void launchRole(int role) {
        // Only one role may host/run on this phone at a time. Otherwise a phone
        // previously used as Parent can keep its local server alive, and the
        // Child discovery flow may pair the phone with itself instead of the
        // actual Parent device.
        getSharedPreferences("app_state", MODE_PRIVATE).edit()
            .putInt("active_role", role)
            .apply();

        Intent currentIntent = getIntent();
        boolean clearOldChildLink = currentIntent != null
            && currentIntent.getBooleanExtra("clear_old_child_link", false);

        if (role == 1) {
            // Switching to Parent: stop any leftover Child heartbeat/overlay.
            try { stopService(new Intent(this, BedtimeMonitorService.class)); } catch (Exception ignored) {}
            try { BedtimeOverlay.hide(this); } catch (Exception ignored) {}
        } else {
            // Switching to Child: stop a previously running Parent LAN server.
            try { LocalParentService.stop(this); } catch (Exception ignored) {}
            // Parent -> Child is a real role change, so do not revive a stale Child pairing.
            if (clearOldChildLink) clearOldChildPairing();
        }
        if (currentIntent != null && currentIntent.hasExtra("clear_old_child_link")) {
            currentIntent.removeExtra("clear_old_child_link");
        }

        Class<?> target = role == 1 ? ParentActivity.class : ChildActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }

    private void clearOldChildPairing() {
        android.content.SharedPreferences childPrefs = getSharedPreferences("child", MODE_PRIVATE);
        final String oldParent = childPrefs.getString("localServer", "");
        final String oldToken = childPrefs.getString("localToken", "");
        final String oldCloudToken = childPrefs.getString("cloudToken", "");
        if ((!oldParent.isEmpty() && !oldToken.isEmpty()) || !oldCloudToken.isEmpty()) {
            // Best-effort remove prior local/cloud pairing without blocking role selection.
            new Thread(() -> {
                try {
                    if (!oldParent.isEmpty() && !oldToken.isEmpty())
                        Net.request("POST", oldParent, "/local/child/unpair", "x-child-local-token", oldToken, new JSONObject());
                } catch (Exception ignored) {}
                try {
                    if (!oldCloudToken.isEmpty())
                        Net.request("POST", BuildConfig.CLOUD_API_URL, "/api/child/unpair", "x-child-token", oldCloudToken, new JSONObject());
                } catch (Exception ignored) {}
            }).start();
        }
        // Preserve deviceKey/name, but discard the old token/address/pairing state.
        childPrefs.edit()
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
    }
}
