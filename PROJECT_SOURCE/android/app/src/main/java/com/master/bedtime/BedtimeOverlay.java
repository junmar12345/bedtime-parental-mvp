package com.master.bedtime;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

public final class BedtimeOverlay {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View view;

    private BedtimeOverlay() {}

    public static void show(Context context) {
        Context app = context.getApplicationContext();

        MAIN.post(() -> {
            synchronized (BedtimeOverlay.class) {
                if (view != null) return;
                if (!Settings.canDrawOverlays(app)) return;

                try {
                    WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    if (wm == null) return;

                    FrameLayout root = new FrameLayout(app);
                    root.setClickable(true);
                    root.setFocusable(true);
                    root.setFocusableInTouchMode(true);
                    root.setOnTouchListener((v, event) -> true);
                    root.setOnKeyListener((v, keyCode, event) -> true);

                    ImageView image = new ImageView(app);
                    image.setImageResource(com.master.bedtime.practice.R.drawable.cracked_screen_hd);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setAdjustViewBounds(false);
                    image.setClickable(true);
                    image.setFocusable(true);
                    image.setOnTouchListener((v, event) -> true);

                    root.addView(image, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));

                    root.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

                    int flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN;

                    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            flags,
                            PixelFormat.OPAQUE);

                    p.gravity = Gravity.TOP | Gravity.START;

                    wm.addView(root, p);
                    view = root;
                } catch (Exception ignored) {
                    view = null;
                }
            }
        });
    }

    public static void hide(Context context) {
        Context app = context.getApplicationContext();

        MAIN.post(() -> {
            synchronized (BedtimeOverlay.class) {
                if (view == null) return;

                try {
                    WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) wm.removeView(view);
                } catch (Exception ignored) {
                } finally {
                    view = null;
                }
            }
        });
    }
}
