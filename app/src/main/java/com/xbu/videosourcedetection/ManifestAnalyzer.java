package com.xbu.videosourcedetection;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ManifestAnalyzer {
    private static final Pattern RES = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODECS = Pattern.compile("CODECS=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern MPD_REP = Pattern.compile("<Representation\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9:_-]+)=\"([^\"]*)\"");
    private static final Pattern MANIFEST_URL = Pattern.compile("https?[^\\s\"']+?\\.(?:m3u8|mpd)(?:\\?[^\\s\"']*)?", Pattern.CASE_INSENSITIVE);

    private ManifestAnalyzer() {}

    public static String analyze(String inputUrl) throws Exception {
        String body = fetch(inputUrl);
        String lower = inputUrl.toLowerCase(Locale.ROOT);
        if (body.contains("#EXTM3U") || lower.contains(".m3u8")) return parseHls(body);
        if (body.contains("<MPD") || lower.contains(".mpd")) return parseMpd(body);

        Matcher found = MANIFEST_URL.matcher(body.replace("\\u0026", "&").replace("&amp;", "&"));
        if (found.find()) {
            String manifest = found.group();
            return "网页中发现媒体清单：\n" + manifest + "\n\n" + analyze(manifest);
        }
        return "这是普通网页/分享链接，没有直接暴露 HLS/DASH 清单。\n"
                + "很多视频 App 的真实播放地址需要登录态、签名或应用内部请求；实时检测请使用主界面的屏幕分析模式。";
    }

    private static String parseHls(String text) {
        String[] lines = text.split("\\r?\\n");
        List<Variant> variants = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            Matcher rm = RES.matcher(line);
            int w = 0, h = 0;
            if (rm.find()) { w = Integer.parseInt(rm.group(1)); h = Integer.parseInt(rm.group(2)); }
            long bw = 0;
            Matcher bm = BANDWIDTH.matcher(line);
            if (bm.find()) bw = Long.parseLong(bm.group(1));
            String codec = null;
            Matcher cm = CODECS.matcher(line);
            if (cm.find()) codec = cm.group(1);
            variants.add(new Variant(w, h, bw, codec));
        }
        if (variants.isEmpty()) return "HLS 媒体清单已识别，但没有 Variant 分辨率信息（可能是单码率媒体清单）。";
        variants.sort(Comparator.comparingLong((Variant v) -> (long) v.width * v.height).reversed());
        StringBuilder b = new StringBuilder("HLS 可用档位：\n");
        int count = 0;
        for (Variant v : variants) {
            if (count++ >= 12) break;
            b.append(v.width > 0 ? v.width + "×" + v.height : "未知尺寸");
            if (v.bandwidth > 0) b.append(" · ").append(String.format(Locale.ROOT, "%.1f Mbps", v.bandwidth / 1_000_000.0));
            if (v.codec != null) b.append(" · ").append(v.codec);
            b.append('\n');
        }
        b.append("\n注意：清单里的 2160P 只证明编码信号是 2160P，不证明它不是从 1080P/2K 升采样。仍需结合实时细节分析。 ");
        return b.toString();
    }

    private static String parseMpd(String text) {
        Matcher rep = MPD_REP.matcher(text);
        List<Variant> variants = new ArrayList<>();
        while (rep.find()) {
            String attrs = rep.group(1);
            Matcher am = ATTR.matcher(attrs);
            int w = 0, h = 0;
            long bw = 0;
            String codec = null;
            while (am.find()) {
                String k = am.group(1).toLowerCase(Locale.ROOT);
                String v = am.group(2);
                if (k.equals("width")) w = safeInt(v);
                else if (k.equals("height")) h = safeInt(v);
                else if (k.equals("bandwidth")) bw = safeLong(v);
                else if (k.equals("codecs")) codec = v;
            }
            if (w > 0 || h > 0) variants.add(new Variant(w, h, bw, codec));
        }
        if (variants.isEmpty()) return "DASH MPD 已识别，但没有在 Representation 上解析到明确分辨率。";
        variants.sort(Comparator.comparingLong((Variant v) -> (long) v.width * v.height).reversed());
        StringBuilder b = new StringBuilder("DASH 可用档位：\n");
        int count = 0;
        for (Variant v : variants) {
            if (count++ >= 12) break;
            b.append(v.width).append('×').append(v.height);
            if (v.bandwidth > 0) b.append(" · ").append(String.format(Locale.ROOT, "%.1f Mbps", v.bandwidth / 1_000_000.0));
            if (v.codec != null) b.append(" · ").append(v.codec);
            b.append('\n');
        }
        b.append("\n编码档位 ≠ 原生细节等级；升采样判断由实时帧分析给出。 ");
        return b.toString();
    }

    private static String fetch(String inputUrl) throws Exception {
        URL url = new URL(inputUrl.trim());
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(9000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "VideoSourceDetection/0.1 Android");
        c.setRequestProperty("Accept", "*/*");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null && b.length() < 2_000_000) b.append(line).append('\n');
            return b.toString();
        } finally {
            c.disconnect();
        }
    }

    private static int safeInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    private static long safeLong(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    private static final class Variant {
        final int width, height;
        final long bandwidth;
        final String codec;
        Variant(int width, int height, long bandwidth, String codec) {
            this.width = width; this.height = height; this.bandwidth = bandwidth; this.codec = codec;
        }
    }
}
