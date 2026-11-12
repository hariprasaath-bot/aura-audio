package com.aura.audio.model;

import java.util.List;

/**
 * Structured DSP Lossless Verification and Forensic Quality Report.
 */
public record QualityReport(
        Verdict verdict,
        int cutoffFrequencyHz,
        int nyquistFrequencyHz,
        String estimatedOriginalBitrate,
        double confidenceScore,
        double dynamicRangeScore,
        double peakDbfs,
        double rmsDbfs,
        float[] frequencyBins,
        float[] averagePsdDbfs,
        float[] peakPsdDbfs,
        List<String> forensicDetails,
        AudioMetadata metadata
) {
    public String getFormattedCutoff() {
        if (cutoffFrequencyHz >= nyquistFrequencyHz - 500) {
            return String.format("Full Spectrum (>= %.1f kHz)", nyquistFrequencyHz / 1000.0);
        }
        return String.format("%.1f kHz", cutoffFrequencyHz / 1000.0);
    }

    public String getDynamicRangeGrade() {
        if (dynamicRangeScore >= 14.0) return "DR" + Math.round(dynamicRangeScore) + " (Audiophile / Pristine)";
        if (dynamicRangeScore >= 10.0) return "DR" + Math.round(dynamicRangeScore) + " (Good Dynamics)";
        if (dynamicRangeScore >= 7.0) return "DR" + Math.round(dynamicRangeScore) + " (Moderate Compression)";
        return "DR" + Math.round(dynamicRangeScore) + " (Heavy Limiting / Brickwalled Loudness)";
    }

    public String getContentReachesFormatted() {
        return String.format("%.1f kHz", cutoffFrequencyHz / 1000.0);
    }

    public String getContentCapacityFormatted() {
        return String.format("%.1f kHz", nyquistFrequencyHz / 1000.0);
    }

    public String getFileFormatSummary() {
        double rateKhz = metadata != null ? metadata.sampleRate() / 1000.0 : 44.1;
        return String.format("%.1f kHz", rateKhz);
    }

    public String getFileFormatDetails() {
        if (metadata == null) return "Unknown format";
        int bits = metadata.bitDepth() > 0 ? metadata.bitDepth() : 16;
        int channels = metadata.channels() > 0 ? metadata.channels() : 2;
        double durSec = metadata.durationMs() / 1000.0;
        String size = metadata.getFormattedFileSize();
        int kbps = metadata.bitRateKbps();
        return String.format("%d-bit · %d ch · %.1f s · %s · %,d kbps average", bits, channels, durSec, size, kbps);
    }

    public String getVerdictSubText() {
        double reachKhz = cutoffFrequencyHz / 1000.0;
        double rateKhz = (metadata != null ? metadata.sampleRate() : 44100) / 1000.0;
        if (verdict == Verdict.STANDARD_RESOLUTION || verdict == Verdict.GENUINE_LOSSLESS) {
            return String.format("Content reaches %.1f kHz, normal for a %.1f kHz file.", reachKhz, rateKhz);
        } else if (verdict == Verdict.STUDIO_HI_RES) {
            return String.format("Content reaches %.1f kHz, authentic high-resolution audio.", reachKhz);
        } else if (verdict == Verdict.SUSPECTED_UPSCALED_LOSSY) {
            return String.format("Acoustic content cut off at %.1f kHz, suspected lossy upscale.", reachKhz);
        } else {
            return String.format("Content reaches %.1f kHz, expected for lossy format.", reachKhz);
        }
    }

    public String getExplanatoryNote() {
        double reachKhz = cutoffFrequencyHz / 1000.0;
        double nyqKhz = nyquistFrequencyHz / 1000.0;
        double rateKhz = (metadata != null ? metadata.sampleRate() : 44100) / 1000.0;

        if (verdict == Verdict.STANDARD_RESOLUTION || verdict == Verdict.GENUINE_LOSSLESS) {
            return String.format("Standard-resolution file (%.1f kHz). It can hold content up to %.2f kHz, and this one reaches %.1f kHz. Not a hi-res format.\n\nLog frequency x dBFS level. Whole-file peak-hold analysis. Any high-frequency energy anywhere in the file shows up. A file made from a %.1f kHz source usually stops in a sharp cutoff near 21-22 kHz; content past that is what a native high-rate recording can add.", rateKhz, nyqKhz, reachKhz, rateKhz);
        } else if (verdict == Verdict.STUDIO_HI_RES) {
            return String.format("High-resolution studio master (%.1f kHz). Content reaches %.1f kHz with authentic ultrasonic overtones extending past 24 kHz. Conforms to native studio high-rate recording specifications.", rateKhz, reachKhz);
        } else if (verdict == Verdict.SUSPECTED_UPSCALED_LOSSY) {
            return String.format("Suspected upscaled lossy transcode. The container format is lossless, but acoustic energy stops abruptly at %.1f kHz with steep brickwall attenuation, characteristic of an MP3/AAC compression profile.", reachKhz);
        } else {
            return String.format("Lossy compressed audio file. Acoustic energy rolls off at %.1f kHz conforming to standard perceptual lossy encoder parameters.", reachKhz);
        }
    }
}

