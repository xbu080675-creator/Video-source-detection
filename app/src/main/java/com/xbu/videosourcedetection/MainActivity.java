package com.xbu.videosourcedetection;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 3401;
    private static final int REQ_NOTIFY = 3402;
    private MediaProjectionManager projectionManager;
    private TextView permissionState;
    private EditText urlInput;
    private TextView urlResult;
    private ExecutorService networkExecutor;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        networkExecutor = Executors.newSingleThreadExecutor();
        setContentView(buildUi());
        handleSharedText(getIntent());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedText(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionState();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(40));
        root.setBackgroundColor(0xfff5f5f7);
        scroll.addView(root);

        TextView title = text("片源检测", 30, true, 0xff111111);
        root.addView(title);
        TextView sub = text("优先针对在线视频 / 视频 App：实时分析编码信号、屏幕输出和有效细节，识别“4K 信号但低细节”的升采样风险。", 15, false, 0xff555555);
        sub.setPadding(0, dp(8), 0, dp(22));
        root.addView(sub);

        permissionState = cardText();
        root.addView(permissionState);

        Button overlayButton = button("授权悬浮窗");
        overlayButton.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(overlayButton);

        Button usageButton = button("授权前台 App 识别");
        usageButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        root.addView(usageButton);

        Button start = button("开始实时检测");
        start.setBackgroundColor(0xff111111);
        start.setTextColor(Color.WHITE);
        start.setOnClickListener(v -> startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE));
        root.addView(start);

        Button stop = button("停止实时检测");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, ScreenAnalysisService.class);
            i.setAction(ScreenAnalysisService.ACTION_STOP);
            startService(i);
        });
        root.addView(stop);

        TextView note = text("说明：普通 Android App 无法直接读取另一个 App 的私有 MediaCodec 实例。无 Root 模式分析最终渲染画面的细节与插值指纹；Root 可用时会额外尝试读取系统 codec/media metrics。DRM 安全画面可能无法捕获。", 13, false, 0xff666666);
        note.setPadding(0, dp(12), 0, dp(24));
        root.addView(note);

        TextView linkTitle = text("在线视频清单分析", 20, true, 0xff111111);
        root.addView(linkTitle);
        TextView linkSub = text("可粘贴 HLS (.m3u8)、DASH (.mpd) 或网页分享链接。它能列出编码档位，但编码 2160P 本身不等于原生 4K。", 14, false, 0xff666666);
        linkSub.setPadding(0, dp(6), 0, dp(10));
        root.addView(linkSub);

        urlInput = new EditText(this);
        urlInput.setHint("https://…");
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        root.addView(urlInput, new LinearLayout.LayoutParams(-1, -2));

        Button analyzeUrl = button("分析链接 / 清单");
        analyzeUrl.setOnClickListener(v -> analyzeUrl());
        root.addView(analyzeUrl);
        urlResult = cardText();
        urlResult.setText("尚未分析链接。你也可以从浏览器或视频 App 的“分享”菜单把链接发到本 App。 ");
        root.addView(urlResult);
        return scroll;
    }

    private void analyzeUrl() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            urlResult.setText("请输入 http/https 链接。 ");
            return;
        }
        urlResult.setText("正在读取媒体清单…");
        networkExecutor.submit(() -> {
            try {
                String result = ManifestAnalyzer.analyze(url);
                runOnUiThread(() -> urlResult.setText(result));
            } catch (Exception e) {
                runOnUiThread(() -> urlResult.setText("分析失败：" + e.getClass().getSimpleName() + " · " + String.valueOf(e.getMessage())));
            }
        });
    }

    private void handleSharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) return;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared != null && urlInput != null) {
            int http = shared.indexOf("http");
            urlInput.setText(http >= 0 ? shared.substring(http).trim() : shared.trim());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE || resultCode != RESULT_OK || data == null) return;
        Intent service = new Intent(this, ScreenAnalysisService.class);
        service.setAction(ScreenAnalysisService.ACTION_START);
        service.putExtra(ScreenAnalysisService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(ScreenAnalysisService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        permissionState.setText("实时检测已启动。现在切到视频 App 播放内容，悬浮窗会持续采样。 ");
    }

    private void updatePermissionState() {
        boolean overlay = Settings.canDrawOverlays(this);
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        boolean usage = mode == AppOpsManager.MODE_ALLOWED;
        permissionState.setText("悬浮窗：" + (overlay ? "已授权" : "未授权")
                + "\n前台 App 识别：" + (usage ? "已授权" : "未授权")
                + "\nRoot：可选，不是启动条件");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.setMargins(0, dp(7), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView cardText() {
        TextView t = text("", 14, false, 0xff222222);
        t.setBackgroundColor(Color.WHITE);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String value, float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
