package com.aura.audio.model;

/**
 * Normalized 32-bit floating-point PCM audio buffer (-1.0f to 1.0f).
 */
public record AudioSampleBuffer(
        float[] monoSamples,
        float[][] channelSamples,
        int sampleRate,
        int channels,
        int bitDepth,
        long totalFrames
) {
    public double getDurationSeconds() {
        return (double) totalFrames / sampleRate;
    }

    public long getDurationMs() {
        return (long) (getDurationSeconds() * 1000.0);
    }

    /**
     * Extracts a window of samples starting at frameIndex into dest.
     * Fills with zero if out of bounds.
     */
    public void getWindow(int startFrame, float[] dest) {
        int length = dest.length;
        for (int i = 0; i < length; i++) {
            int frame = startFrame + i;
            if (frame >= 0 && frame < monoSamples.length) {
                dest[i] = monoSamples[frame];
            } else {
                dest[i] = 0.0f;
            }
        }
    }
}

