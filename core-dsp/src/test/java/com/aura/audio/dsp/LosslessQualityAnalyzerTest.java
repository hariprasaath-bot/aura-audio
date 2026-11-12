package com.aura.audio.dsp;

import com.aura.audio.model.AudioMetadata;
import com.aura.audio.model.AudioSampleBuffer;
import com.aura.audio.model.QualityReport;
import com.aura.audio.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LosslessQualityAnalyzerTest {

    @Test
    @DisplayName("Detects 128 kbps MP3 brickwall cutoff (~16 kHz) in a fake FLAC transcode")
    void testDetectsFakeLosslessWith16kHzCutoff() {
        int sampleRate = 44100;
        int durationSec = 4;
        int totalFrames = sampleRate * durationSec;

        float[] samples = new float[totalFrames];

        // Generate synthetic audio with content strictly up to 15.8 kHz (typical 128 kbps MP3 low-pass cutoff)
        for (int i = 0; i < totalFrames; i++) {
            double t = (double) i / sampleRate;
            double sig = 0.0;
            for (int f = 200; f <= 15800; f += 400) {
                double amp = 0.4 / (1.0 + (f / 4000.0));
                sig += amp * Math.sin(2.0 * Math.PI * f * t);
            }
            samples[i] = (float) sig * 0.15f;
        }

        AudioSampleBuffer buffer = new AudioSampleBuffer(
                samples,
                new float[][]{samples, samples},
                sampleRate,
                2,
                16,
                totalFrames
        );

        AudioMetadata fakeFlacMetadata = new AudioMetadata(
                "Fake 128k Upscale",
                "Encoder Upscaler",
                "Fake Album",
                "Test",
                "2026",
                "1",
                "FLAC",
                950,
                sampleRate,
                16,
                2,
                durationSec * 1000L,
                4_000_000L,
                null,
                null,
                true // Stored in lossless FLAC container
        );

        LosslessQualityAnalyzer analyzer = new LosslessQualityAnalyzer();
        QualityReport report = analyzer.analyze(buffer, fakeFlacMetadata);

        assertNotNull(report);
        assertEquals(Verdict.SUSPECTED_UPSCALED_LOSSY, report.verdict(),
                "Analyzer must flag a track whose acoustic energy cuts off around 16 kHz in a FLAC container as SUSPECTED_UPSCALED_LOSSY.");
        assertTrue(report.cutoffFrequencyHz() <= 17000 && report.cutoffFrequencyHz() >= 15000,
                "Detected cutoff should be around 16 kHz (actual: " + report.cutoffFrequencyHz() + " Hz)");
        assertTrue(report.estimatedOriginalBitrate().contains("128 kbps"),
                "Estimated bitrate should identify ~128 kbps transcode (actual: " + report.estimatedOriginalBitrate() + ")");
    }

    @Test
    @DisplayName("Identifies genuine lossless CD audio extending to Nyquist")
    void testIdentifiesGenuineLossless() {
        int sampleRate = 44100;
        int durationSec = 4;
        int totalFrames = sampleRate * durationSec;

        float[] samples = new float[totalFrames];

        // Generate rich acoustic multi-harmonic spectrum smoothly extending up to 21.5 kHz
        for (int i = 0; i < totalFrames; i++) {
            double t = (double) i / sampleRate;
            double sig = 0.0;
            for (int f = 100; f <= 21500; f += 300) {
                double amp = 0.5 / (1.0 + (f / 3000.0));
                sig += amp * Math.sin(2.0 * Math.PI * f * t);
            }
            samples[i] = (float) sig * 0.15f;
        }

        AudioSampleBuffer buffer = new AudioSampleBuffer(
                samples,
                new float[][]{samples, samples},
                sampleRate,
                2,
                16,
                totalFrames
        );

        AudioMetadata genuineFlacMeta = new AudioMetadata(
                "True Lossless Master",
                "Audiophile Artist",
                "Reference Album",
                "Acoustic",
                "2026",
                "1",
                "FLAC",
                1024,
                sampleRate,
                16,
                2,
                durationSec * 1000L,
                5_000_000L,
                null,
                null,
                true
        );

        LosslessQualityAnalyzer analyzer = new LosslessQualityAnalyzer();
        QualityReport report = analyzer.analyze(buffer, genuineFlacMeta);

        assertNotNull(report);
        assertTrue(report.verdict() == Verdict.STANDARD_RESOLUTION || report.verdict() == Verdict.GENUINE_LOSSLESS,
                "Acoustic material extending to 21.5 kHz must be identified as Standard-Resolution Lossless.");
        assertTrue(report.cutoffFrequencyHz() >= 20500,
                "Cutoff should be near Nyquist (>20.5 kHz, actual: " + report.cutoffFrequencyHz() + " Hz)");
    }
}
