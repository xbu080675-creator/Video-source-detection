package com.xbu.videosourcedetection;

/**
 * Lightweight spatial-frequency heuristics for rendered video frames.
 *
 * This deliberately does not claim to recover the original master resolution.
 * It estimates how much independent high-frequency detail survives in the rendered frame
 * and looks for a 2x interpolation phase pattern commonly left by 1080p -> 2160p scaling.
 */
public final class FrameQualityAnalyzer {
    private FrameQualityAnalyzer() {}

    public static QualitySnapshot analyze(byte[] y, int width, int height) {
        if (y == null || width < 16 || height < 16 || y.length < width * height) {
            return new QualitySnapshot(0, 0, 0, 0, 0, 0, true);
        }

        long sum = 0;
        long sumSq = 0;
        for (byte value : y) {
            int v = value & 0xff;
            sum += v;
            sumSq += (long) v * v;
        }
        double n = y.length;
        double mean = sum / n;
        double variance = Math.max(0.0, (sumSq / n) - mean * mean);
        double std = Math.sqrt(variance);

        double gradSum = 0;
        double lapSum = 0;
        long edgeCount = 0;
        long samples = 0;

        double oddH = 0, evenH = 0, oddV = 0, evenV = 0;
        long oddHC = 0, evenHC = 0, oddVC = 0, evenVC = 0;

        for (int py = 1; py < height - 1; py++) {
            int row = py * width;
            for (int px = 1; px < width - 1; px++) {
                int i = row + px;
                int c = y[i] & 0xff;
                int l = y[i - 1] & 0xff;
                int r = y[i + 1] & 0xff;
                int u = y[i - width] & 0xff;
                int d = y[i + width] & 0xff;

                int gx = Math.abs(r - l);
                int gy = Math.abs(d - u);
                double grad = (gx + gy) * 0.5;
                gradSum += grad;
                if (grad > 20) edgeCount++;

                int lap = Math.abs(4 * c - l - r - u - d);
                lapSum += lap;
                samples++;

                int secondH = Math.abs(2 * c - l - r);
                int secondV = Math.abs(2 * c - u - d);
                if ((px & 1) == 0) { evenH += secondH; evenHC++; }
                else { oddH += secondH; oddHC++; }
                if ((py & 1) == 0) { evenV += secondV; evenVC++; }
                else { oddV += secondV; oddVC++; }
            }
        }

        double gradMean = samples == 0 ? 0 : gradSum / samples;
        double lapMean = samples == 0 ? 0 : lapSum / samples;
        double edgeDensity = samples == 0 ? 0 : (double) edgeCount / samples;

        double hEven = evenH / Math.max(1, evenHC);
        double hOdd = oddH / Math.max(1, oddHC);
        double vEven = evenV / Math.max(1, evenVC);
        double vOdd = oddV / Math.max(1, oddVC);
        double phaseH = phaseImbalance(hEven, hOdd);
        double phaseV = phaseImbalance(vEven, vOdd);
        double phase2x = Math.max(phaseH, phaseV);

        double detailNorm = (lapMean / (std + 3.0)) * 42.0 + edgeDensity * 80.0;
        int detailScore = clamp((int) Math.round(detailNorm), 0, 100);
        int textureScore = clamp((int) Math.round((std / 48.0) * 100.0), 0, 100);

        boolean lowInfo = std < 7.0 || edgeDensity < 0.006;
        int risk = clamp((int) Math.round(phase2x * 135.0), 0, 100);
        if (detailScore > 82 && risk < 18) risk = 18;
        if (lowInfo) risk = Math.min(risk, 35);

        int confidence = clamp((int) Math.round(textureScore * 0.65 + Math.min(100, edgeDensity * 1200) * 0.35), 0, 100);
        if (lowInfo) confidence = Math.min(confidence, 28);

        return new QualitySnapshot(detailScore, textureScore, risk, confidence, phase2x, lapMean, lowInfo);
    }

    private static double phaseImbalance(double a, double b) {
        double max = Math.max(a, b);
        double min = Math.min(a, b);
        if (max < 0.5) return 0.0;
        return 1.0 - (min / max);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
