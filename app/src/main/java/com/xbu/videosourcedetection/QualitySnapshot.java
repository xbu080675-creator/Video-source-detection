package com.xbu.videosourcedetection;

public final class QualitySnapshot {
    public final int detailScore;
    public final int textureScore;
    public final int upscaleRisk;
    public final int confidence;
    public final double phase2x;
    public final double laplacian;
    public final boolean lowInformation;

    public QualitySnapshot(int detailScore, int textureScore, int upscaleRisk, int confidence,
                           double phase2x, double laplacian, boolean lowInformation) {
        this.detailScore = detailScore;
        this.textureScore = textureScore;
        this.upscaleRisk = upscaleRisk;
        this.confidence = confidence;
        this.phase2x = phase2x;
        this.laplacian = laplacian;
        this.lowInformation = lowInformation;
    }
}
