package com.xbu.videosourcedetection;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

public final class OverlayController {
    private final Context context;
    private final WindowManager windowManager;
    private TextView view;

    public OverlayController(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        if (view != null || !Settings.canDrawOverlays(context)) return;
        view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(11.5f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setText("片源检测 · STARTING");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD91A1A1A);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0x55FFFFFF);
        view.setBackground(bg);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(10);
        lp.y = dp(88);
        windowManager.addView(view, lp);
    }

    public void update(String text) {
        if (view != null) view.post(() -> view.setText(text));
    }

    public void hide() {
        if (view != null) {
            try { windowManager.removeView(view); } catch (Exception ignored) {}
            view = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
