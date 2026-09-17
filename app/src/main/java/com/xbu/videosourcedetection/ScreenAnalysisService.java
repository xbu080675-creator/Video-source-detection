package com.xbu.videosourcedetection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class ScreenAnalysisService extends Service {
    public static final String ACTION_START = "com.xbu.videosourcedetection.START";
    public static final String ACTION_STOP = "com.xbu.videosourcedetection.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL = "analysis";
    private static final int NOTIFICATION_ID = 2408;

    private HandlerThread imageThread;
    private Handler imageHandler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection projection;
    private OverlayController overlay;
    private ForegroundAppDetector appDetector;
    private final ArrayDeque<QualitySnapshot> history = new ArrayDeque<>();
    private final AtomicReference<RootCodecProbe.Signal> codecSignal = new AtomicReference<>(new RootCodecProbe.Signal(false, 0, 0, null, null));
    private ExecutorService rootExecutor;
    private long lastAnalyzeAt;
    private long lastRootProbeAt;
    private int displayWidth;
    private int displayHeight;
    private int blackFrames;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        overlay = new OverlayController(this);
        appDetector = new ForegroundAppDetector(this);
        rootExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("正在分析视频输出"));
        if (intent == null || !ACTION_START.equals(intent.getAction()) || projection != null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(getMainLooper()));

        startCapture();
        overlay.show();
        return START_NOT_STICKY;
    }

    private void startCapture() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(dm);
        displayWidth = dm.widthPixels;
        displayHeight = dm.heightPixels;
        int density = dm.densityDpi;

        imageThread = new HandlerThread("vsd-image");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        imageReader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImage, imageHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "VideoSourceDetection",
                displayWidth, displayHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, imageHandler);
    }

    private void onImage(ImageReader reader) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            long now = System.currentTimeMillis();
            if (now - lastAnalyzeAt < 650) return;
            lastAnalyzeAt = now;

            QualitySnapshot best = null;
            double[] centers = displayHeight > displayWidth ? new double[]{0.30, 0.50, 0.70} : new double[]{0.35, 0.50, 0.65};
            for (double cy : centers) {
                byte[] luma = extractCropLuma(image, 320, 180, cy);
                QualitySnapshot q = FrameQualityAnalyzer.analyze(luma, 320, 180);
                if (best == null || q.confidence > best.confidence) best = q;
            }
            if (best == null) return;

            if (best.textureScore < 3 && best.detailScore < 3) blackFrames++; else blackFrames = 0;
            history.addLast(best);
            while (history.size() > 24) history.removeFirst();

            if (now - lastRootProbeAt > 3500) {
                lastRootProbeAt = now;
                rootExecutor.submit(() -> codecSignal.set(RootCodecProbe.probe()));
            }
            updateOverlay();
        } catch (Throwable ignored) {
        }
    }

    private byte[] extractCropLuma(Image image, int cropW, int cropH, double centerYFraction) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int iw = image.getWidth();
        int ih = image.getHeight();
        int w = Math.min(cropW, iw - 2);
        int h = Math.min(cropH, ih - 2);
        int startX = Math.max(0, (iw - w) / 2);
        int centerY = (int) Math.round(ih * centerYFraction);
        int startY = Math.max(0, Math.min(ih - h, centerY - h / 2));
        byte[] out = new byte[cropW * cropH];

        for (int y = 0; y < cropH; y++) {
            int sy = startY + Math.min(h - 1, (int) ((long) y * h / cropH));
            for (int x = 0; x < cropW; x++) {
                int sx = startX + Math.min(w - 1, (int) ((long) x * w / cropW));
                int pos = sy * rowStride + sx * pixelStride;
                if (pos + 2 >= buffer.limit()) continue;
                int r = buffer.get(pos) & 0xff;
                int g = buffer.get(pos + 1) & 0xff;
                int b = buffer.get(pos + 2) & 0xff;
                out[y * cropW + x] = (byte) ((77 * r + 150 * g + 29 * b) >> 8);
            }
        }
        return out;
    }

    private void updateOverlay() {
        if (history.isEmpty()) return;
        int d = 0, t = 0, r = 0, c = 0;
        for (QualitySnapshot q : history) {
            d += q.detailScore;
            t += q.textureScore;
            r += q.upscaleRisk;
            c += q.confidence;
        }
        int n = history.size();
        d /= n;
        t /= n;
        r /= n;
        c /= n;

        String app = appDetector.getForegroundLabel();
        RootCodecProbe.Signal signal = codecSignal.get();
        String riskLabel = r >= 67 ? "高" : r >= 38 ? "中" : "低";

        String verify;
        if (blackFrames >= 4) {
            verify = "画面不可采样：可能是 DRM / FLAG_SECURE / 黑场";
        } else if (signal != null && signal.hasVideoSize() && signal.height >= 2000 && Math.min(displayWidth, displayHeight) < 1800) {
            verify = "编码为 4K 级，但屏幕输出不足 2160P；不能仅凭屏幕帧证明原生 4K";
        } else if (r >= 67 && c >= 45) {
            verify = "检测到明显 2× 插值周期：疑似升采样/重建输出";
        } else if (c < 35) {
            verify = "当前画面纹理不足，结论置信度低";
        } else {
            verify = "未发现强 2× 插值指纹；继续多帧采样";
        }

        String text = "片源检测 · LIVE\n"
                + app + "\n"
                + (signal == null ? "系统码流：探测中" : signal.summary()) + "\n"
                + "屏幕输出：" + displayWidth + "×" + displayHeight + "\n"
                + "有效细节：" + d + "/100 · 纹理 " + t + "/100\n"
                + "升采样嫌疑：" + riskLabel + " " + r + "% · 置信 " + c + "%\n"
                + verify;
        overlay.update(text);
    }

    @Override
    public void onDestroy() {
        if (overlay != null) overlay.hide();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (imageThread != null) imageThread.quitSafely();
        if (rootExecutor != null) rootExecutor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "视频片源检测", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("实时分析其他 App 的视频输出质量");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("片源检测运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }
}
