package com.aura.audio.model;

import java.util.Base64;

/**
 * Audio track metadata, container properties, and embedded artwork.
 */
public record AudioMetadata(
        String title,
        String artist,
        String album,
        String genre,
        String year,
        String trackNumber,
        String containerFormat,
        int bitRateKbps,
        int sampleRate,
        int bitDepth,
        int channels,
        long durationMs,
        long fileSizeBytes,
        String artworkMimeType,
        byte[] artworkData,
        boolean declaredLossless
) {
    public String getFormattedDuration() {
        long totalSec = durationMs / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format("%d:%02d", min, sec);
    }

    public String getFormattedFileSize() {
        if (fileSizeBytes <= 0) return "0 MB";
        double mb = fileSizeBytes / (1024.0 * 1024.0);
        return String.format("%.2f MB", mb);
    }

    public String getCodecBadge() {
        String depthStr = bitDepth > 0 ? bitDepth + "-bit / " : "";
        String rateStr = String.format("%.1f kHz", sampleRate / 1000.0);
        String rateKbpsStr = bitRateKbps > 0 ? " • " + bitRateKbps + " kbps" : "";
        return containerFormat.toUpperCase() + " " + depthStr + rateStr + rateKbpsStr;
    }

    public String getArtworkDataUri() {
        if (artworkData == null || artworkData.length == 0) {
            return null;
        }
        String mime = (artworkMimeType != null && !artworkMimeType.isBlank()) ? artworkMimeType : "image/jpeg";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(artworkData);
    }
}

