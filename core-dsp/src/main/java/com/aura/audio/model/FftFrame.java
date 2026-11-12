package com.aura.audio.model;

/**
 * Real-time FFT analysis frame representing an audio slice for visualizers and oscilloscopes.
 */
public record FftFrame(
        long timestampMs,
        float[] waveformSamples,
        float[] magnitudes,
        float[] bands64,
        float waveformPeak,
        float waveformRms,
        long positionMs,
        long durationMs
) {
    public static final int NUM_BANDS = 64;

    /**
     * Backward-compatible constructor without explicit waveformSamples.
     */
    public FftFrame(long timestampMs, float[] magnitudes, float[] bands64, float waveformPeak, float waveformRms, long positionMs, long durationMs) {
        this(timestampMs, new float[512], magnitudes, bands64, waveformPeak, waveformRms, positionMs, durationMs);
    }

    /**
     * Creates an empty/silent frame.
     */
    public static FftFrame silent(long positionMs, long durationMs) {
        return new FftFrame(
                System.currentTimeMillis(),
                new float[512],
                new float[256],
                new float[NUM_BANDS],
                0.0f,
                0.0f,
                positionMs,
                durationMs
        );
    }
}
