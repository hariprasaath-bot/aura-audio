package com.aura.audio.player;

import com.aura.audio.decoder.AudioDecoder;
import com.aura.audio.decoder.AudioMetadataExtractor;
import com.aura.audio.dsp.FastFourierTransform;
import com.aura.audio.model.AudioMetadata;
import com.aura.audio.model.AudioSampleBuffer;
import com.aura.audio.model.FftFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Non-blocking audio player engine running on Java 21 Virtual Threads.
 * Streams PCM to javax.sound.sampled.SourceDataLine while tapping real-time DSP visualizer frames.
 */
public final class AuraAudioPlayer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AuraAudioPlayer.class);

    public enum State {
        IDLE, PLAYING, PAUSED, STOPPED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean loop = new AtomicBoolean(false);
    private final AtomicInteger currentFrameIndex = new AtomicInteger(0);

    private final List<Consumer<FftFrame>> visualizerListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<State>> stateListeners = new CopyOnWriteArrayList<>();

    private File currentFile;
    private AudioMetadata currentMetadata;
    private AudioSampleBuffer currentBuffer;
    private SourceDataLine sourceDataLine;
    private FastFourierTransform fft;
    private boolean hardwareGainSupported = false;

    private volatile float volume = 0.85f;
    private volatile float playbackSpeed = 1.0f;
    private volatile boolean isSeeking = false;
    private volatile Thread playerThread;

    private final ExecutorService dspExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aura-dsp-worker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private final AtomicBoolean dspBusy = new AtomicBoolean(false);

    public AuraAudioPlayer() {}

    /**
     * Loads an audio track, decodes samples, and extracts metadata.
     */
    public synchronized void load(File file) throws Exception {
        stop();

        this.currentFile = file;
        this.currentMetadata = AudioMetadataExtractor.extract(file);
        this.currentBuffer = AudioDecoder.decodeToBuffer(file);
        this.currentFrameIndex.set(0);

        int sampleRate = currentBuffer.sampleRate();
        this.fft = new FastFourierTransform(2048, sampleRate);

        initSourceDataLine(sampleRate, currentBuffer.channels());
        transitionState(State.IDLE);
    }

    private void initSourceDataLine(int sampleRate, int channels) throws LineUnavailableException {
        if (sourceDataLine != null && sourceDataLine.isOpen()) {
            sourceDataLine.close();
        }

        // Stereo 16-bit format: pcmBuffer is always written as 2 channels (left/right)
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                2,
                4, // 2 channels * 2 bytes = 4 bytes per frame
                sampleRate,
                false
        );

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try {
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
            // 350ms hardware buffer for ultra-stable, glitch-free desktop audio playback
            int bufferSizeBytes = (int) (sampleRate * 2 * 2 * 0.35);
            sourceDataLine.open(format, bufferSizeBytes);
            sourceDataLine.start();
        } catch (Exception e) {
            logger.warn("Native sample rate {} Hz unavailable on audio device, falling back to 44100 Hz: {}", sampleRate, e.getMessage());
            AudioFormat fallbackFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    44100.0f,
                    16,
                    2,
                    4,
                    44100.0f,
                    false
            );
            DataLine.Info fallbackInfo = new DataLine.Info(SourceDataLine.class, fallbackFormat);
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(fallbackInfo);
            int bufferSizeBytes = (int) (44100 * 2 * 2 * 0.35);
            sourceDataLine.open(fallbackFormat, bufferSizeBytes);
            sourceDataLine.start();
        }

        hardwareGainSupported = sourceDataLine.isControlSupported(FloatControl.Type.MASTER_GAIN);
        applyVolumeToLine();
    }

    public synchronized void play() {
        if (currentBuffer == null) return;

        if (state.get() == State.PAUSED) {
            transitionState(State.PLAYING);
            return;
        }

        transitionState(State.PLAYING);
        if (playerThread == null || !playerThread.isAlive()) {
            playerThread = new Thread(this::playbackLoop, "aura-player-worker");
            playerThread.setDaemon(true);
            playerThread.setPriority(Thread.MAX_PRIORITY);
            playerThread.start();
        }
    }

    public synchronized void pause() {
        if (state.get() == State.PLAYING) {
            transitionState(State.PAUSED);
        }
    }

    public synchronized void stop() {
        transitionState(State.STOPPED);
        currentFrameIndex.set(0);
        dspBusy.set(false);
        if (sourceDataLine != null && sourceDataLine.isOpen()) {
            sourceDataLine.flush();
        }
        dispatchFftFrame(FftFrame.silent(0, getDurationMs()));
    }

    public void seek(long positionMs) {
        if (currentBuffer == null) return;

        isSeeking = true;
        try {
            int sampleRate = currentBuffer.sampleRate();
            long totalFrames = currentBuffer.totalFrames();
            int targetFrame = (int) ((positionMs / 1000.0) * sampleRate);
            targetFrame = Math.max(0, Math.min(targetFrame, (int) totalFrames - 1));

            currentFrameIndex.set(targetFrame);
            if (sourceDataLine != null && sourceDataLine.isOpen()) {
                sourceDataLine.stop();
                sourceDataLine.flush();
                sourceDataLine.start();
            }

            // Immediately notify visualizers and UI of the updated seek position
            long newPosMs = (long) (((double) targetFrame / sampleRate) * 1000.0);
            dispatchFftFrame(FftFrame.silent(newPosMs, getDurationMs()));
        } finally {
            isSeeking = false;
        }
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0.0f, Math.min(1.0f, vol));
        applyVolumeToLine();
    }

    public float getVolume() {
        return volume;
    }

    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = Math.max(0.20f, Math.min(2.5f, speed));
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public void setLoop(boolean enableLoop) {
        this.loop.set(enableLoop);
    }

    public boolean isLooping() {
        return loop.get();
    }

    public State getState() {
        return state.get();
    }

    public AudioMetadata getMetadata() {
        return currentMetadata;
    }

    public AudioSampleBuffer getBuffer() {
        return currentBuffer;
    }

    public File getCurrentFile() {
        return currentFile;
    }

    public long getCurrentPositionMs() {
        if (currentBuffer == null || currentBuffer.sampleRate() <= 0) return 0;
        return (long) (((double) currentFrameIndex.get() / currentBuffer.sampleRate()) * 1000.0);
    }

    public long getDurationMs() {
        return currentMetadata != null ? currentMetadata.durationMs() : 0;
    }

    public void addVisualizerListener(Consumer<FftFrame> listener) {
        visualizerListeners.add(listener);
    }

    public void removeVisualizerListener(Consumer<FftFrame> listener) {
        visualizerListeners.remove(listener);
    }

    public void addStateListener(Consumer<State> listener) {
        stateListeners.add(listener);
    }

    /**
     * Dedicated high-priority audio pump loop.
     */
    private void playbackLoop() {
        int chunkSize = 2048; // ~46ms at 44.1kHz
        float[] monoWindow = new float[chunkSize];
        byte[] pcmBuffer = new byte[chunkSize * 2 * 2]; // 16-bit stereo

        double currentPos = currentFrameIndex.get();

        while (state.get() != State.STOPPED) {
            if (state.get() == State.PAUSED) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            if (isSeeking) {
                currentPos = currentFrameIndex.get();
                Thread.yield();
                continue;
            }

            if (currentBuffer == null) {
                break;
            }

            int totalFrames = (int) currentBuffer.totalFrames();
            int currentIntPos = (int) currentPos;

            // Detect external seek synchronization
            int observedFrame = currentFrameIndex.get();
            if (Math.abs(observedFrame - currentIntPos) > 8) {
                currentPos = observedFrame;
                currentIntPos = observedFrame;
            }

            if (currentIntPos >= totalFrames) {
                if (loop.get()) {
                    currentPos = 0;
                    currentFrameIndex.set(0);
                    continue;
                } else {
                    stop();
                    break;
                }
            }

            float[][] channelData = currentBuffer.channelSamples();
            int channels = currentBuffer.channels();
            double speed = this.playbackSpeed;
            float effVol = hardwareGainSupported ? 1.0f : volume;

            float peak = 0.0f;
            double sumSq = 0.0;
            int byteOffset = 0;
            int framesToRead = 0;
            long frameStart = currentIntPos;

            if (Math.abs(speed - 1.0) < 0.01) {
                // High-performance direct 1.0x playback
                framesToRead = Math.min(chunkSize, totalFrames - currentIntPos);
                for (int i = 0; i < framesToRead; i++) {
                    int f = currentIntPos + i;
                    float left = channelData[0][f];
                    float right = (channels > 1) ? channelData[1][f] : left;
                    float mono = (left + right) * 0.5f;
                    monoWindow[i] = mono;

                    float abs = Math.abs(mono);
                    if (abs > peak) peak = abs;
                    sumSq += (mono * mono);

                    short sLeft = (short) Math.max(-32768, Math.min(32767, (int) (left * effVol * 32767)));
                    short sRight = (short) Math.max(-32768, Math.min(32767, (int) (right * effVol * 32767)));

                    pcmBuffer[byteOffset++] = (byte) (sLeft & 0xFF);
                    pcmBuffer[byteOffset++] = (byte) ((sLeft >> 8) & 0xFF);
                    pcmBuffer[byteOffset++] = (byte) (sRight & 0xFF);
                    pcmBuffer[byteOffset++] = (byte) ((sRight >> 8) & 0xFF);
                }
                currentPos += framesToRead;
            } else {
                // High-precision variable speed & slow-motion resampling with fractional linear interpolation
                int maxOutputFrames = chunkSize;
                framesToRead = 0;

                for (int i = 0; i < maxOutputFrames; i++) {
                    if (currentPos >= totalFrames - 1) {
                        break;
                    }
                    int idx0 = (int) currentPos;
                    int idx1 = Math.min(totalFrames - 1, idx0 + 1);
                    float frac = (float) (currentPos - idx0);

                    float left0 = channelData[0][idx0];
                    float left1 = channelData[0][idx1];
                    float left = left0 + frac * (left1 - left0);

                    float right;
                    if (channels > 1) {
                        float right0 = channelData[1][idx0];
                        float right1 = channelData[1][idx1];
                        right = right0 + frac * (right1 - right0);
                    } else {
                        right = left;
                    }

                    float mono = (left + right) * 0.5f;
                    monoWindow[i] = mono;

                    float abs = Math.abs(mono);
                    if (abs > peak) peak = abs;
                    sumSq += (mono * mono);

                    short sLeft = (short) Math.max(-32768, Math.min(32767, (int) (left * effVol * 32767)));
                    short sRight = (short) Math.max(-32768, Math.min(32767, (int) (right * effVol * 32767)));

                    pcmBuffer[byteOffset++] = (byte) (sLeft & 0xFF);
                    pcmBuffer[byteOffset++] = (byte) ((sLeft >> 8) & 0xFF);
                    pcmBuffer[byteOffset++] = (byte) (sRight & 0xFF);
                    pcmBuffer[byteOffset++] = (byte) ((sRight >> 8) & 0xFF);

                    framesToRead++;
                    currentPos += speed;
                }
            }

            // Advance currentFrameIndex EXACTLY ONCE per chunk
            currentFrameIndex.set((int) currentPos);

            if (framesToRead <= 0) {
                if (loop.get()) {
                    currentPos = 0;
                    currentFrameIndex.set(0);
                    continue;
                } else {
                    stop();
                    break;
                }
            }

            // Zero-fill remaining window for DSP if near end of track
            for (int i = framesToRead; i < chunkSize; i++) {
                monoWindow[i] = 0.0f;
            }

            // 1. Write PCM audio to sound hardware (blocks naturally until line consumes)
            if (sourceDataLine != null && sourceDataLine.isOpen()) {
                sourceDataLine.write(pcmBuffer, 0, framesToRead * 4);
            }

            // 2. Asynchronously process DSP & Visualizers without ever blocking or jittering the audio thread
            if (dspBusy.compareAndSet(false, true)) {
                float[] dspWindow = monoWindow.clone();
                int dspFrames = framesToRead;
                long dspPosMs = (long) (((double) frameStart / currentBuffer.sampleRate()) * 1000.0);
                float dspPeak = peak;
                double dspSumSq = sumSq;

                dspExecutor.execute(() -> {
                    try {
                        float[] dspMags = new float[chunkSize / 2];
                        float[] dspBands = new float[64];

                        if (fft != null) {
                            fft.computeMagnitudes(dspWindow, dspMags);
                            fft.computeBands64(dspMags, dspBands);
                        }

                        float rms = (float) Math.sqrt(dspSumSq / Math.max(1, dspFrames));
                        FftFrame fftFrame = new FftFrame(
                                System.currentTimeMillis(),
                                dspWindow,
                                dspMags,
                                dspBands,
                                dspPeak,
                                rms,
                                dspPosMs,
                                getDurationMs()
                        );
                        dispatchFftFrame(fftFrame);
                    } catch (Exception e) {
                        logger.debug("DSP visualizer computation error: {}", e.getMessage());
                    } finally {
                        dspBusy.set(false);
                    }
                });
            }
        }
    }

    private void applyVolumeToLine() {
        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;

        try {
            if (hardwareGainSupported && sourceDataLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) sourceDataLine.getControl(FloatControl.Type.MASTER_GAIN);
                if (volume <= 0.0001f) {
                    gainControl.setValue(gainControl.getMinimum());
                } else {
                    float dB = (float) (20.0 * Math.log10(volume));
                    dB = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB));
                    gainControl.setValue(dB);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to set master gain on audio line: {}", e.getMessage());
        }
    }

    private void transitionState(State newState) {
        state.set(newState);
        for (Consumer<State> listener : stateListeners) {
            try {
                listener.accept(newState);
            } catch (Exception e) {
                logger.error("State listener error: {}", e.getMessage());
            }
        }
    }

    private void dispatchFftFrame(FftFrame frame) {
        for (Consumer<FftFrame> listener : visualizerListeners) {
            try {
                listener.accept(frame);
            } catch (Exception e) {
                logger.error("Visualizer listener error: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        stop();
        if (dspExecutor != null && !dspExecutor.isShutdown()) {
            dspExecutor.shutdownNow();
        }
        if (sourceDataLine != null) {
            try {
                sourceDataLine.close();
            } catch (Exception ignored) {}
        }
    }
}

