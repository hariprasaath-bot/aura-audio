package com.aura.audio.decoder;

import com.aura.audio.model.AudioMetadata;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Set;

/**
 * Robust audio metadata extractor using jaudiotagger with fallback heuristics.
 */
public final class AudioMetadataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(AudioMetadataExtractor.class);

    private static final Set<String> LOSSLESS_CONTAINERS = Set.of(
            "FLAC", "WAV", "WAVE", "AIFF", "AIF", "ALAC", "PCM", "APE", "WV"
    );

    private AudioMetadataExtractor() {}

    public static AudioMetadata extract(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Target file does not exist: " + file);
        }

        String fileName = file.getName();
        String extension = getFileExtension(fileName).toUpperCase(Locale.ROOT);
        long fileSize = file.length();

        String title = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String artist = "Unknown Artist";
        String album = "Unknown Album";
        String genre = "Unknown";
        String year = "";
        String trackNumber = "";
        String container = extension.isEmpty() ? "UNKNOWN" : extension;
        int bitRateKbps = 0;
        int sampleRate = 44100;
        int bitDepth = 16;
        int channels = 2;
        long durationMs = 0;
        String artworkMime = null;
        byte[] artworkBytes = null;

        try {
            AudioFile audioFile = AudioFileIO.read(file);
            AudioHeader header = audioFile.getAudioHeader();
            if (header != null) {
                sampleRate = header.getSampleRateAsNumber();
                bitRateKbps = (int) header.getBitRateAsNumber();
                bitDepth = header.getBitsPerSample();
                if (bitDepth <= 0) {
                    bitDepth = extension.equals("FLAC") ? 16 : 16; // reasonable baseline
                }
                String chStr = header.getChannels();
                if (chStr != null && !chStr.isBlank()) {
                    if (chStr.equalsIgnoreCase("Mono")) {
                        channels = 1;
                    } else if (chStr.toLowerCase(Locale.ROOT).contains("stereo")) {
                        channels = 2;
                    } else {
                        try {
                            String digits = chStr.replaceAll("[^0-9]", "");
                            if (!digits.isEmpty()) {
                                channels = Integer.parseInt(digits);
                            }
                        } catch (Exception ignored) {
                            channels = 2;
                        }
                    }
                }
                durationMs = header.getPreciseTrackLength() > 0
                        ? (long) (header.getPreciseTrackLength() * 1000.0)
                        : (long) header.getTrackLength() * 1000L;
                if (header.getFormat() != null && !header.getFormat().isBlank()) {
                    container = header.getFormat();
                }
            }

            Tag tag = audioFile.getTag();
            if (tag != null) {
                String t = tag.getFirst(FieldKey.TITLE);
                if (t != null && !t.isBlank()) title = t.trim();

                String a = tag.getFirst(FieldKey.ARTIST);
                if (a != null && !a.isBlank()) artist = a.trim();

                String alb = tag.getFirst(FieldKey.ALBUM);
                if (alb != null && !alb.isBlank()) album = alb.trim();

                String g = tag.getFirst(FieldKey.GENRE);
                if (g != null && !g.isBlank()) genre = g.trim();

                String y = tag.getFirst(FieldKey.YEAR);
                if (y != null && !y.isBlank()) year = y.trim();

                String trk = tag.getFirst(FieldKey.TRACK);
                if (trk != null && !trk.isBlank()) trackNumber = trk.trim();

                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null && artwork.getBinaryData() != null) {
                    artworkBytes = artwork.getBinaryData();
                    artworkMime = artwork.getMimeType();
                }
            }
        } catch (Exception e) {
            logger.warn("jaudiotagger metadata extraction fallback for {}: {}", fileName, e.getMessage());
        }

        boolean declaredLossless = LOSSLESS_CONTAINERS.contains(container.toUpperCase(Locale.ROOT))
                || LOSSLESS_CONTAINERS.contains(extension);

        // If bitrate wasn't populated from header, compute from file size and duration
        if (bitRateKbps <= 0 && durationMs > 0) {
            bitRateKbps = (int) ((fileSize * 8) / durationMs);
        }

        return new AudioMetadata(
                title,
                artist,
                album,
                genre,
                year,
                trackNumber,
                container,
                bitRateKbps,
                sampleRate,
                bitDepth,
                channels,
                durationMs,
                fileSize,
                artworkMime,
                artworkBytes,
                declaredLossless
        );
    }

    private static String getFileExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }
}

