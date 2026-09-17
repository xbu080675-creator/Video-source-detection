package com.xbu.videosourcedetection;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort optional probe. The app remains fully usable without root. */
public final class RootCodecProbe {
    private static final Pattern WIDTH = Pattern.compile("(?i)(?:width|video[-_ ]?width)\\s*[=:]\\s*(\\d{3,5})");
    private static final Pattern HEIGHT = Pattern.compile("(?i)(?:height|video[-_ ]?height)\\s*[=:]\\s*(\\d{3,5})");
    private static final Pattern MIME = Pattern.compile("(?i)(video/[a-z0-9._+-]+)");
    private static final Pattern FPS = Pattern.compile("(?i)(?:frame[-_ ]?rate|fps)\\s*[=:]\\s*([0-9.]{1,6})");

    public static final class Signal {
        public final boolean root;
        public final int width;
        public final int height;
        public final String mime;
        public final String fps;

        Signal(boolean root, int width, int height, String mime, String fps) {
            this.root = root;
            this.width = width;
            this.height = height;
            this.mime = mime;
            this.fps = fps;
        }

        public boolean hasVideoSize() { return width > 0 && height > 0; }

        public String summary() {
            if (!root) return "系统码流：无 Root / 无可读 codec 会话";
            if (!hasVideoSize()) return "系统码流：Root 可用，但未解析到活动视频尺寸";
            StringBuilder b = new StringBuilder("系统码流：").append(width).append('×').append(height);
            if (mime != null) b.append(" · ").append(mime);
            if (fps != null) b.append(" · ").append(fps).append("fps");
            b.append(" [ROOT]");
            return b.toString();
        }
    }

    private RootCodecProbe() {}

    public static Signal probe() {
        String output = runRoot("(dumpsys media.codec 2>/dev/null; dumpsys media.metrics 2>/dev/null) | tail -n 2500");
        if (output == null) return new Signal(false, 0, 0, null, null);

        int bestW = 0, bestH = 0;
        String mime = null, fps = null;
        String[] lines = output.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher wm = WIDTH.matcher(line);
            if (!wm.find()) continue;
            int w = safeInt(wm.group(1));
            int h = 0;
            String localMime = null;
            String localFps = null;
            for (int j = Math.max(0, i - 8); j <= Math.min(lines.length - 1, i + 12); j++) {
                Matcher hm = HEIGHT.matcher(lines[j]);
                if (hm.find()) h = Math.max(h, safeInt(hm.group(1)));
                Matcher mm = MIME.matcher(lines[j]);
                if (mm.find()) localMime = mm.group(1).toLowerCase(Locale.ROOT);
                Matcher fm = FPS.matcher(lines[j]);
                if (fm.find()) localFps = fm.group(1);
            }
            if (w > 0 && h > 0 && (long) w * h > (long) bestW * bestH) {
                bestW = w;
                bestH = h;
                mime = localMime;
                fps = localFps;
            }
        }
        return new Signal(true, bestW, bestH, mime, fps);
    }

    private static String runRoot(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null && out.length() < 1_500_000) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out.toString() : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
