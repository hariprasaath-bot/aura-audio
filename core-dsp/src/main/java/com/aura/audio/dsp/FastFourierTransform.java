package com.aura.audio.dsp;

import org.jtransforms.fft.FloatFFT_1D;

import java.util.Arrays;

/**
 * Fast Fourier Transform utility leveraging JTransforms FloatFFT_1D.
 * Converts time-domain PCM samples into frequency-domain magnitudes and 64 logarithmic equalizer bands.
 */
public final class FastFourierTransform {

    private final int fftSize;
    private final FloatFFT_1D fft;
    private final HannWindow hannWindow;
    private final float[] workingBuffer;
    private final int numPositiveBins;

    // Precalculated 64-band logarithmic frequency boundaries
    private final int[] bandStartBins;
    private final int[] bandEndBins;

    public FastFourierTransform(int fftSize, int sampleRate) {
        this.fftSize = fftSize;
        this.fft = new FloatFFT_1D(fftSize);
        this.hannWindow = new HannWindow(fftSize);
        this.workingBuffer = new float[fftSize];
        this.numPositiveBins = fftSize / 2;

        this.bandStartBins = new int[64];
        this.bandEndBins = new int[64];
        precomputeLogBands(sampleRate);
    }

    private void precomputeLogBands(int sampleRate) {
        double minFreq = 20.0;
        double maxFreq = Math.min(22050.0, sampleRate / 2.0);
        double binResolution = (double) sampleRate / fftSize;

        for (int i = 0; i < 64; i++) {
            double fLow = minFreq * Math.pow(maxFreq / minFreq, (double) i / 64.0);
            double fHigh = minFreq * Math.pow(maxFreq / minFreq, (double) (i + 1) / 64.0);

            int startBin = (int) Math.floor(fLow / binResolution);
            int endBin = (int) Math.ceil(fHigh / binResolution);

            startBin = Math.max(1, Math.min(startBin, numPositiveBins - 1));
            endBin = Math.max(startBin + 1, Math.min(endBin, numPositiveBins));

            bandStartBins[i] = startBin;
            bandEndBins[i] = endBin;
        }
    }

    public int getFftSize() {
        return fftSize;
    }

    public int getNumPositiveBins() {
        return numPositiveBins;
    }

    /**
     * Executes windowed real-forward FFT and writes linear magnitudes into destMagnitudes.
     * destMagnitudes must have length >= numPositiveBins.
     */
    public synchronized void computeMagnitudes(float[] inputPcm, float[] destMagnitudes) {
        hannWindow.apply(inputPcm, workingBuffer);
        fft.realForward(workingBuffer);

        float norm = hannWindow.getEnergyCorrectionFactor() / fftSize;

        // DC bin
        destMagnitudes[0] = Math.abs(workingBuffer[0]) * norm;

        // Positive frequency bins
        for (int k = 1; k < numPositiveBins; k++) {
            float re = workingBuffer[2 * k];
            float im = workingBuffer[2 * k + 1];
            destMagnitudes[k] = (float) (2.0 * Math.sqrt(re * re + im * im) * norm);
        }
    }

    /**
     * Converts linear magnitudes into decibels relative to Full Scale (dBFS).
     * Clamped between -120 dBFS and 0 dBFS.
     */
    public void computeDbfs(float[] linearMagnitudes, float[] destDbfs) {
        int len = Math.min(linearMagnitudes.length, destDbfs.length);
        for (int k = 0; k < len; k++) {
            float mag = linearMagnitudes[k];
            if (mag <= 1e-6f) {
                destDbfs[k] = -120.0f;
            } else {
                float db = (float) (20.0 * Math.log10(mag));
                destDbfs[k] = Math.max(-120.0f, Math.min(0.0f, db));
            }
        }
    }

    /**
     * Aggregates linear magnitudes into 64 perceptual frequency bands normalized [0.0, 1.0].
     */
    public void computeBands64(float[] linearMagnitudes, float[] destBands) {
        Arrays.fill(destBands, 0.0f);
        for (int b = 0; b < 64; b++) {
            int start = bandStartBins[b];
            int end = bandEndBins[b];
            float maxVal = 0.0f;
            float sum = 0.0f;
            for (int k = start; k < end && k < linearMagnitudes.length; k++) {
                float v = linearMagnitudes[k];
                sum += v;
                if (v > maxVal) maxVal = v;
            }
            float avg = (end > start) ? sum / (end - start) : maxVal;
            // Combined peak and average for lively display
            float blended = (maxVal * 0.7f + avg * 0.3f);

            // Perceptual dB curve scaling to 0.0 - 1.0
            float db = (blended > 1e-5f) ? (float) (20.0 * Math.log10(blended)) : -100.0f;
            // Map -70 dBFS -> 0.0, -6 dBFS -> 1.0
            float normalized = (db + 70.0f) / 64.0f;
            destBands[b] = Math.max(0.0f, Math.min(1.0f, normalized));
        }
    }
}

