package com.aura.audio.model;

/**
 * Quality verdict assigned by the True Lossless Analyzer.
 */
public enum Verdict {
    /**
     * Standard-resolution lossless audio (44.1 kHz / 48 kHz).
     * Content reaches natural acoustic ceiling (~21-22 kHz); normal for CD-quality source, not a hi-res format.
     */
    STANDARD_RESOLUTION("Standard-Resolution File", "#F59E0B", "Standard-resolution file. Content reaches normal frequency range for this format; not a hi-res format."),

    /**
     * High-resolution studio master (>48 kHz sample rate, 24-bit) with genuine ultrasonic acoustic harmonics.
     */
    STUDIO_HI_RES("High-Resolution File", "#06B6D4", "High-sample-rate audio with authentic ultrasonic energy past 24 kHz."),

    /**
     * Fake lossless transcode: file container is lossless (FLAC/WAV/ALAC) or Hi-Res,
     * but audio contains a sharp low-pass cutoff or missing ultrasonic harmonics typical of MP3/AAC.
     */
    SUSPECTED_UPSCALED_LOSSY("Suspected Upscaled Lossy (Fake Lossless)", "#EF4444", "Container is lossless, but a severe brickwall cutoff was detected."),

    /**
     * Standard lossy file (MP3, AAC, OGG) with expected compression characteristics.
     */
    GENUINE_LOSSY("Lossy Compressed File", "#F97316", "File container is lossy; frequency rolloff matches encoder profile."),

    /**
     * Backward-compatible alias for standard lossless.
     */
    GENUINE_LOSSLESS("Standard-Resolution File", "#F59E0B", "Standard-resolution file. Content reaches natural frequency range for this format; not a hi-res format.");

    private final String displayName;
    private final String hexColor;
    private final String description;

    Verdict(String displayName, String hexColor, String description) {
        this.displayName = displayName;
        this.hexColor = hexColor;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getHexColor() {
        return hexColor;
    }

    public String getDescription() {
        return description;
    }

    public int getResolutionTier() {
        return switch (this) {
            case STUDIO_HI_RES -> 3;
            case STANDARD_RESOLUTION, GENUINE_LOSSLESS -> 2;
            default -> 1;
        };
    }
}

