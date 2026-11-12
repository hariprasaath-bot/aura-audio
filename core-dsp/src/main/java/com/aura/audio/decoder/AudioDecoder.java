package com.aura.audio.decoder;

import com.aura.audio.model.AudioSampleBuffer;
import org.jflac.FLACDecoder;
import org.jflac.PCMProcessor;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * High-performance audio decoder supporting FLAC, WAV, MP3, AIFF with
 * multi-channel to mono downmixing and 32-bit float PCM normalization.
 */
public final class AudioDecoder {

    private static final Logger logger = LoggerFactory.getLogger(AudioDecoder.class);

    private AudioDecoder() {}

    /**
     * Opens a decoded PCM audio stream ready for SourceDataLine playback.
     */
    public static AudioInputStream openPcmStream(File file) throws Exception {
        AudioInputStream sourceStream = AudioSystem.getAudioInputStream(file);
        AudioFormat baseFormat = sourceStream.getFormat();

        if (baseFormat.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && !baseFormat.isBigEndian()) {
            return sourceStream;
        }

        // Convert to standard signed PCM (preserving bit depth if available, default to 16-bit)
        float sampleRate = baseFormat.getSampleRate();
        int channels = baseFormat.getChannels();
        if (channels <= 0) channels = 2;
        if (sampleRate <= 0) sampleRate = 44100.0f;

        int bitDepth = baseFormat.getSampleSizeInBits();
        if (bitDepth <= 0) {
            bitDepth = 16;
        }
        int bytesPerSample = (bitDepth + 7) / 8;
        int frameSize = channels * bytesPerSample;

        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitDepth,
                channels,
                frameSize,
                sampleRate,
                false
        );

        try {
            return AudioSystem.getAudioInputStream(targetFormat, sourceStream);
        } catch (IllegalArgumentException e) {
            // Fall back to 16-bit PCM if native bit depth conversion is unsupported
            if (bitDepth != 16) {
                AudioFormat fallbackFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        sampleRate,
                        16,
                        channels,
                        channels * 2,
                        sampleRate,
                        false
                );
                return AudioSystem.getAudioInputStream(fallbackFormat, sourceStream);
            }
            throw e;
        }
    }

    /**
     * Decodes an audio file into a 32-bit floating point AudioSampleBuffer.
     */
    public static AudioSampleBuffer decodeToBuffer(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".flac")) {
            try {
                return decodeFlac(file);
            } catch (Exception e) {
                logger.warn("Native jFLAC decode failed for {}, attempting AudioSystem fallback: {}", file.getName(), e.getMessage());
            }
        }

        return decodeViaAudioSystem(file);
    }

    private static AudioSampleBuffer decodeViaAudioSystem(File file) throws Exception {
        try (AudioInputStream pcmStream = openPcmStream(file)) {
            AudioFormat format = pcmStream.getFormat();
            int channels = format.getChannels();
            int sampleRate = (int) format.getSampleRate();
            int sampleSizeInBits = format.getSampleSizeInBits();
            boolean bigEndian = format.isBigEndian();

            byte[] buffer = new byte[8192];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int read;
            while ((read = pcmStream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }

            byte[] pcmBytes = baos.toByteArray();
            return convertPcmBytesToBuffer(pcmBytes, sampleRate, channels, sampleSizeInBits, bigEndian);
        }
    }

    private static AudioSampleBuffer decodeFlac(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            FLACDecoder decoder = new FLACDecoder(fis);
            decoder.readMetadata();
            StreamInfo streamInfo = decoder.getStreamInfo();

            int sampleRate = streamInfo.getSampleRate();
            int channels = streamInfo.getChannels();
            int bitDepth = streamInfo.getBitsPerSample();

            List<float[]> channelBuffers = new ArrayList<>(channels);
            for (int ch = 0; ch < channels; ch++) {
                channelBuffers.add(new float[0]);
            }

            // Custom accumulator for jFLAC PCM data
            FlacSampleAccumulator accumulator = new FlacSampleAccumulator(channels, bitDepth);
            decoder.addPCMProcessor(accumulator);
            decoder.decodeFrames();

            float[][] channelData = accumulator.getChannelData();
            long totalFrames = accumulator.getTotalFrames();

            float[] monoSamples = new float[(int) totalFrames];
            for (int i = 0; i < totalFrames; i++) {
                float sum = 0.0f;
                for (int ch = 0; ch < channels; ch++) {
                    sum += channelData[ch][i];
                }
                monoSamples[i] = sum / channels;
            }

            return new AudioSampleBuffer(monoSamples, channelData, sampleRate, channels, bitDepth, totalFrames);
        }
    }

    private static AudioSampleBuffer convertPcmBytesToBuffer(
            byte[] pcmBytes,
            int sampleRate,
            int channels,
            int bitDepth,
            boolean bigEndian
    ) {
        int bytesPerSample = (bitDepth + 7) / 8;
        int frameSize = bytesPerSample * channels;
        int totalFrames = pcmBytes.length / frameSize;

        float[][] channelSamples = new float[channels][totalFrames];
        float[] monoSamples = new float[totalFrames];

        int byteOffset = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            float frameSum = 0.0f;
            for (int ch = 0; ch < channels; ch++) {
                float sampleValue = 0.0f;
                if (bytesPerSample == 2) {
                    short val;
                    if (bigEndian) {
                        val = (short) ((pcmBytes[byteOffset] << 8) | (pcmBytes[byteOffset + 1] & 0xFF));
                    } else {
                        val = (short) ((pcmBytes[byteOffset] & 0xFF) | (pcmBytes[byteOffset + 1] << 8));
                    }
                    sampleValue = val / 32768.0f;
                    byteOffset += 2;
                } else if (bytesPerSample == 3) {
                    int val;
                    if (bigEndian) {
                        val = (pcmBytes[byteOffset] << 16) | ((pcmBytes[byteOffset + 1] & 0xFF) << 8) | (pcmBytes[byteOffset + 2] & 0xFF);
                    } else {
                        val = (pcmBytes[byteOffset] & 0xFF) | ((pcmBytes[byteOffset + 1] & 0xFF) << 8) | (pcmBytes[byteOffset + 2] << 16);
                    }
                    sampleValue = val / 8388608.0f;
                    byteOffset += 3;
                } else if (bytesPerSample == 4) {
                    int val;
                    if (bigEndian) {
                        val = (pcmBytes[byteOffset] << 24) | ((pcmBytes[byteOffset + 1] & 0xFF) << 16) | ((pcmBytes[byteOffset + 2] & 0xFF) << 8) | (pcmBytes[byteOffset + 3] & 0xFF);
                    } else {
                        val = (pcmBytes[byteOffset] & 0xFF) | ((pcmBytes[byteOffset + 1] & 0xFF) << 8) | ((pcmBytes[byteOffset + 2] & 0xFF) << 16) | (pcmBytes[byteOffset + 3] << 24);
                    }
                    sampleValue = val / 2147483648.0f;
                    byteOffset += 4;
                } else if (bytesPerSample == 1) {
                    sampleValue = ((pcmBytes[byteOffset] & 0xFF) - 128) / 128.0f;
                    byteOffset += 1;
                }
                channelSamples[ch][frame] = sampleValue;
                frameSum += sampleValue;
            }
            monoSamples[frame] = frameSum / channels;
        }

        return new AudioSampleBuffer(monoSamples, channelSamples, sampleRate, channels, bitDepth, totalFrames);
    }

    private static class FlacSampleAccumulator implements PCMProcessor {
        private final int channels;
        private final int bitDepth;
        private final double scale;
        private final List<float[]> collectedFrames = new ArrayList<>();
        private int totalSamplesCount = 0;

        public FlacSampleAccumulator(int channels, int bitDepth) {
            this.channels = channels;
            this.bitDepth = bitDepth;
            this.scale = Math.pow(2.0, bitDepth - 1);
        }

        @Override
        public void processStreamInfo(StreamInfo info) {}

        @Override
        public void processPCM(ByteData byteData) {
            int len = byteData.getLen();
            byte[] data = byteData.getData();
            int bytesPerSample = (bitDepth + 7) / 8;
            int frameSize = bytesPerSample * channels;
            int frameCount = len / frameSize;
            if (frameCount <= 0) return;

            float[] flat = new float[frameCount * channels];
            int idx = 0;
            int off = 0;
            for (int f = 0; f < frameCount; f++) {
                for (int ch = 0; ch < channels; ch++) {
                    int val = 0;
                    if (bytesPerSample == 2) {
                        val = (data[off] & 0xFF) | (data[off + 1] << 8);
                        off += 2;
                    } else if (bytesPerSample == 3) {
                        val = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8) | (data[off + 2] << 16);
                        off += 3;
                    } else if (bytesPerSample == 1) {
                        val = (data[off] & 0xFF) - 128;
                        off += 1;
                    }
                    flat[idx++] = (float) (val / scale);
                }
            }
            collectedFrames.add(flat);
            totalSamplesCount += frameCount;
        }

        public long getTotalFrames() {
            return totalSamplesCount;
        }

        public float[][] getChannelData() {
            float[][] result = new float[channels][totalSamplesCount];
            int currentFrame = 0;
            for (float[] chunk : collectedFrames) {
                int chunkFrames = chunk.length / channels;
                int off = 0;
                for (int f = 0; f < chunkFrames; f++) {
                    for (int ch = 0; ch < channels; ch++) {
                        result[ch][currentFrame + f] = chunk[off++];
                    }
                }
                currentFrame += chunkFrames;
            }
            return result;
        }
    }
}

