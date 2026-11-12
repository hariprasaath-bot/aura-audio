package com.aura.audio.dsp;

/**
 * Pre-computed Hann (Hanning) window with energy correction factor.
 * Formula: w[n] = 0.5 * (1 - cos(2 * pi * n / (N - 1)))
 */
public final class HannWindow {

    private final int windowSize;
    private final float[] coefficients;
    private final float energyCorrectionFactor;

    public HannWindow(int windowSize) {
        if (windowSize <= 0 || (windowSize & (windowSize - 1)) != 0) {
            throw new IllegalArgumentException("Window size must be a power of two: " + windowSize);
        }
        this.windowSize = windowSize;
        this.coefficients = new float[windowSize];

        double sumSquares = 0.0;
        for (int n = 0; n < windowSize; n++) {
            double val = 0.5 * (1.0 - Math.cos((2.0 * Math.PI * n) / (windowSize - 1)));
            coefficients[n] = (float) val;
            sumSquares += val * val;
        }

        // Energy normalization so that sinusoidal peak dBFS matches the sine wave amplitude
        this.energyCorrectionFactor = (float) Math.sqrt((double) windowSize / sumSquares);
    }

    public int getWindowSize() {
        return windowSize;
    }

    public float[] getCoefficients() {
        return coefficients;
    }

    /**
     * Applies window coefficients into dest array: dest[i] = src[i] * w[i].
     */
    public void apply(float[] src, float[] dest) {
        int len = Math.min(windowSize, Math.min(src.length, dest.length));
        for (int i = 0; i < len; i++) {
            dest[i] = src[i] * coefficients[i];
        }
    }

    public float getEnergyCorrectionFactor() {
        return energyCorrectionFactor;
    }
}

