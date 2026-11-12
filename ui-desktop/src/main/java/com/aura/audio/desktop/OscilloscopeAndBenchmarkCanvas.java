package com.aura.audio.desktop;

import com.aura.audio.model.QualityReport;
import com.aura.audio.model.Verdict;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPU-accelerated Dual-Mode Vector Oscilloscope & Audio Quality Benchmarks Scope.
 *
 * Mode 1: Time-domain Analog Vector Oscilloscope showing live waveform beam,
 *         True Peak/RMS dynamics, Crest Factor, and Inter-Sample Clipping warnings.
 *
 * Mode 2: Audio Quality Benchmarks Spectrum Analyzer showing real-time spectral curve
 *         plotted against the official Lossy/Lossless thresholds (128k, 192k, 320k, CD Nyquist).
 *
 * Neo-Retro Engineering Enhancements:
 * - Deep black CRT screen background (#000000 / #020408) with phosphor glow leak edges.
 * - Dedicated Top HUD, Left dBFS Gutter, and Bottom Frequency/Time Axis Gutters.
 * - Staggered non-overlapping benchmark flags (128k, 192k, 320k, CD Lossless).
 * - Analog phosphor beam with multi-pass neon glow and razor core.
 * - Subtle 10% opacity oscilloscope crosshair reticle with authentic subdivision ticks.
 */
public final class OscilloscopeAndBenchmarkCanvas extends Canvas {

    public enum DisplayMode {
        OSCILLOSCOPE,
        QUALITY_BENCHMARKS
    }

    public enum PhosphorColor {
        CYAN(Color.web("#22D3EE"), Color.rgb(6, 182, 212, 0.25), Color.web("#E0F2FE")),
        PHOSPHOR_GREEN(Color.web("#22C55E"), Color.rgb(16, 185, 129, 0.25), Color.web("#DCFCE7")),
        AMBER(Color.web("#F59E0B"), Color.rgb(245, 158, 11, 0.25), Color.web("#FEF3C7"));

        final Color primary;
        final Color glow;
        final Color core;

        PhosphorColor(Color primary, Color glow, Color core) {
            this.primary = primary;
            this.glow = glow;
            this.core = core;
        }
    }

    // Dedicated Gutter Layout Constants
    private static final double TOP_HUD_H = 34.0;
    private static final double LEFT_GUTTER_W = 46.0;
    private static final double BOTTOM_GUTTER_H = 22.0;
    private static final double RIGHT_PAD = 8.0;

    private final GraphicsContext gc;
    private DisplayMode currentMode = DisplayMode.OSCILLOSCOPE;
    private PhosphorColor phosphorColor = PhosphorColor.CYAN;

    // Waveform buffers for Oscilloscope
    private float[] currentWaveform = new float[512];
    private float livePeak = 0.0f;
    private float liveRms = 0.0f;

    // Spectral and quality benchmarks data
    private float[] currentMagnitudes = new float[256];
    private QualityReport activeReport;
    private int detectedCutoffHz = -1;
    private int nyquistHz = 22050;
    private final AtomicBoolean repaintPending = new AtomicBoolean(false);

    public OscilloscopeAndBenchmarkCanvas(double width, double height) {
        super(width, height);
        this.gc = getGraphicsContext2D();

        widthProperty().addListener((obs, oldV, newV) -> render());
        heightProperty().addListener((obs, oldV, newV) -> render());
        render();
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double minWidth(double height) {
        return 100.0;
    }

    @Override
    public double minHeight(double width) {
        return 50.0;
    }

    @Override
    public double prefWidth(double height) {
        return 800.0;
    }

    @Override
    public double prefHeight(double width) {
        return 480.0;
    }

    @Override
    public double maxWidth(double height) {
        return Double.MAX_VALUE;
    }

    @Override
    public double maxHeight(double width) {
        return Double.MAX_VALUE;
    }

    @Override
    public void resize(double width, double height) {
        super.setWidth(width);
        super.setHeight(height);
    }

    public void setDisplayMode(DisplayMode mode) {
        this.currentMode = mode;
        render();
    }

    public DisplayMode getDisplayMode() {
        return currentMode;
    }

    public void setPhosphorColor(PhosphorColor color) {
        if (color != null) {
            this.phosphorColor = color;
            render();
        }
    }

    public PhosphorColor getPhosphorColor() {
        return phosphorColor;
    }

    public void setQualityReport(QualityReport report) {
        this.activeReport = report;
        if (report != null) {
            this.detectedCutoffHz = report.cutoffFrequencyHz();
            this.nyquistHz = report.nyquistFrequencyHz() > 0 ? report.nyquistFrequencyHz() : 22050;
        }
        render();
    }

    /**
     * Receives real-time audio frame from DSP pipeline.
     */
    public void pushFrame(float[] waveform, float[] magnitudes, float peak, float rms) {
        if (!isVisible()) return;

        if (waveform != null && waveform.length > 0) {
            int len = Math.min(waveform.length, 512);
            if (currentWaveform.length != len) {
                currentWaveform = new float[len];
            }
            System.arraycopy(waveform, 0, currentWaveform, 0, len);
        }

        if (magnitudes != null && magnitudes.length > 0) {
            int len = Math.min(magnitudes.length, 256);
            if (currentMagnitudes.length != len) {
                currentMagnitudes = new float[len];
            }
            System.arraycopy(magnitudes, 0, currentMagnitudes, 0, len);
        }

        this.livePeak = peak;
        this.liveRms = rms;

        requestRepaint();
    }

    public void requestRepaint() {
        if (repaintPending.compareAndSet(false, true)) {
            if (Platform.isFxApplicationThread()) {
                repaintPending.set(false);
                render();
            } else {
                Platform.runLater(() -> {
                    repaintPending.set(false);
                    render();
                });
            }
        }
    }

    public void clear() {
        Arrays.fill(currentWaveform, 0.0f);
        Arrays.fill(currentMagnitudes, 0.0f);
        this.livePeak = 0.0f;
        this.liveRms = 0.0f;
        render();
    }

    private void render() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Overall Deep Black CRT Chassis
        gc.setFill(Color.web("#010204"));
        gc.fillRect(0, 0, w, h);

        // Calculate plot active screen dimensions
        double plotX = LEFT_GUTTER_W;
        double plotY = TOP_HUD_H;
        double plotW = Math.max(10.0, w - plotX - RIGHT_PAD);
        double plotH = Math.max(10.0, h - plotY - BOTTOM_GUTTER_H);

        // 2. Render Screen Panels & Dedicated Gutters
        renderScreenFrameAndGutters(w, h, plotX, plotY, plotW, plotH);

        // 3. Render Mode Specific Active Graphics
        if (currentMode == DisplayMode.OSCILLOSCOPE) {
            renderOscilloscopeMode(w, h, plotX, plotY, plotW, plotH);
        } else {
            renderBenchmarksMode(w, h, plotX, plotY, plotW, plotH);
        }

        // 4. Razor Outer Chassis Border
        gc.setLineWidth(1.0);
        gc.setStroke(Color.web("#0F172A"));
        gc.strokeRect(0.5, 0.5, w - 1.0, h - 1.0);
    }

    private void renderScreenFrameAndGutters(double w, double h, double px, double py, double pw, double ph) {
        // Dedicated Top HUD Bar
        gc.setFill(Color.web("#03060C"));
        gc.fillRect(0, 0, w, TOP_HUD_H);
        gc.setLineWidth(1.0);
        gc.setStroke(Color.web("#1E293B"));
        gc.strokeLine(0, TOP_HUD_H, w, TOP_HUD_H);

        // Dedicated Left Gutter (dBFS / Amplitude Rulers)
        gc.setFill(Color.web("#020509"));
        gc.fillRect(0, TOP_HUD_H, LEFT_GUTTER_W, ph);
        gc.setStroke(Color.web("#1E293B"));
        gc.strokeLine(LEFT_GUTTER_W, TOP_HUD_H, LEFT_GUTTER_W, TOP_HUD_H + ph);

        // Dedicated Bottom Gutter (Frequency / Time Axis)
        gc.setFill(Color.web("#020509"));
        gc.fillRect(0, h - BOTTOM_GUTTER_H, w, BOTTOM_GUTTER_H);
        gc.setStroke(Color.web("#1E293B"));
        gc.strokeLine(0, h - BOTTOM_GUTTER_H, w, h - BOTTOM_GUTTER_H);

        // CRT Screen Display Well (Deep Black Glass)
        gc.setFill(Color.web("#000103"));
        gc.fillRect(px, py, pw, ph);

        // Subtle CRT Phosphor Edge Glow Leak
        Color leakColor = phosphorColor.glow;
        gc.setStroke(leakColor);
        gc.setLineWidth(1.0);
        gc.strokeRect(px + 0.5, py + 0.5, pw - 1.0, ph - 1.0);

        // Corner Bezel Accents on CRT Glass
        double cSize = 6.0;
        gc.setStroke(Color.web("#38BDF8", 0.40));
        // Top-Left
        gc.strokeLine(px, py + cSize, px, py);
        gc.strokeLine(px, py, px + cSize, py);
        // Top-Right
        gc.strokeLine(px + pw - cSize, py, px + pw, py);
        gc.strokeLine(px + pw, py, px + pw, py + cSize);
        // Bottom-Left
        gc.strokeLine(px, py + ph - cSize, px, py + ph);
        gc.strokeLine(px, py + ph, px + cSize, py + ph);
        // Bottom-Right
        gc.strokeLine(px + pw - cSize, py + ph, px + pw, py + ph);
        gc.strokeLine(px + pw, py + ph - cSize, px + pw, py + ph);
    }

    // =========================================================================
    // Mode 1: Vector Oscilloscope & Waveform Dynamics
    // =========================================================================
    private void renderOscilloscopeMode(double w, double h, double px, double py, double pw, double ph) {
        double centerY = py + (ph / 2.0);

        // 1. Left Gutter Amplitude Scale (+1.0 to -1.0)
        gc.setFont(Font.font("JetBrains Mono", 8));
        double[] ampLevels = {1.0, 0.5, 0.0, -0.5, -1.0};
        String[] ampLabels = {"+1.0", "+0.5", " 0.0", "-0.5", "-1.0"};

        for (int i = 0; i < ampLevels.length; i++) {
            double lvl = ampLevels[i];
            double y = centerY - (lvl * (ph / 2.0 * 0.88));

            // Tick in gutter
            gc.setStroke(Color.web("#334155"));
            gc.strokeLine(LEFT_GUTTER_W - 4.0, y, LEFT_GUTTER_W, y);

            // Label
            gc.setFill(Color.web("#64748B"));
            gc.fillText(ampLabels[i], 12.0, y + 3.0);
        }

        // 2. Bottom Gutter Time Scale Divisions
        int numTimeDivs = 10;
        for (int i = 0; i <= numTimeDivs; i++) {
            double x = px + (pw / numTimeDivs) * i;
            gc.setStroke(Color.web("#334155"));
            gc.strokeLine(x, h - BOTTOM_GUTTER_H, x, h - BOTTOM_GUTTER_H + 3.0);

            if (i % 2 == 0) {
                String tLabel = String.format("%.1f ms", i * 1.0);
                gc.setFill(Color.web("#64748B"));
                gc.fillText(tLabel, Math.max(px, x - 14.0), h - 7.0);
            }
        }

        // 3. CRT Crosshair Reticle Grid (10% Opacity)
        gc.save();
        gc.beginPath();
        gc.rect(px, py, pw, ph);
        gc.clip();

        // Horizontal division lines
        for (double lvl : ampLevels) {
            double y = centerY - (lvl * (ph / 2.0 * 0.88));
            if (lvl == 0.0) {
                // Major center 0V axis
                gc.setStroke(Color.rgb(56, 189, 248, 0.22));
                gc.setLineWidth(1.0);
                gc.strokeLine(px, y, px + pw, y);

                // Authentic oscilloscope subdivision tick marks along center X axis
                for (int s = 1; s < numTimeDivs * 5; s++) {
                    double sx = px + (pw / (numTimeDivs * 5.0)) * s;
                    double tickH = (s % 5 == 0) ? 4.0 : 2.0;
                    gc.setStroke(Color.rgb(56, 189, 248, 0.25));
                    gc.strokeLine(sx, y - tickH, sx, y + tickH);
                }
            } else {
                gc.setStroke(Color.rgb(255, 255, 255, 0.04));
                gc.setLineWidth(1.0);
                gc.strokeLine(px, y, px + pw, y);
            }
        }

        // Vertical division lines
        double centerX = px + (pw / 2.0);
        for (int i = 1; i < numTimeDivs; i++) {
            double x = px + (pw / numTimeDivs) * i;
            if (Math.abs(x - centerX) < 1.0) {
                // Major center vertical axis
                gc.setStroke(Color.rgb(56, 189, 248, 0.22));
                gc.setLineWidth(1.0);
                gc.strokeLine(x, py, x, py + ph);

                // Authentic subdivision ticks along center Y axis
                for (int s = 1; s < 20; s++) {
                    double sy = py + (ph / 20.0) * s;
                    double tickW = (s % 5 == 0) ? 4.0 : 2.0;
                    gc.setStroke(Color.rgb(56, 189, 248, 0.25));
                    gc.strokeLine(x - tickW, sy, x + tickW, sy);
                }
            } else {
                gc.setStroke(Color.rgb(255, 255, 255, 0.04));
                gc.setLineWidth(1.0);
                gc.strokeLine(x, py, x, py + ph);
            }
        }

        // 4. Analog Vector Phosphor Beam (Waveform Trace)
        int numSamples = currentWaveform.length;
        if (numSamples > 1) {
            // Pass 1: Neon Glow halo leak pass
            gc.setStroke(phosphorColor.glow);
            gc.setLineWidth(4.5);
            drawWaveformPath(px, pw, centerY, ph, numSamples);

            // Pass 2: Phosphor primary beam
            gc.setStroke(phosphorColor.primary);
            gc.setLineWidth(2.2);
            drawWaveformPath(px, pw, centerY, ph, numSamples);

            // Pass 3: Razor-sharp high-intensity core beam
            gc.setStroke(phosphorColor.core);
            gc.setLineWidth(1.1);
            drawWaveformPath(px, pw, centerY, ph, numSamples);
        }

        gc.restore();

        // 5. Top HUD Readout Overlay (Non-overlapping Header Bar)
        renderOscilloscopeHud(w);
    }

    private void drawWaveformPath(double px, double pw, double centerY, double ph, int numSamples) {
        gc.beginPath();
        for (int i = 0; i < numSamples; i++) {
            double x = px + ((double) i / (numSamples - 1)) * pw;
            double sample = Math.max(-1.0, Math.min(1.0, currentWaveform[i]));
            double y = centerY - (sample * (ph / 2.0 * 0.88));

            if (i == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void renderOscilloscopeHud(double w) {
        gc.setFont(Font.font("JetBrains Mono", 9));

        float peakDbfs = (livePeak > 1e-5f) ? (float) (20.0 * Math.log10(livePeak)) : -100.0f;
        float rmsDbfs = (liveRms > 1e-5f) ? (float) (20.0 * Math.log10(liveRms)) : -100.0f;
        float crestFactor = Math.max(0.0f, peakDbfs - rmsDbfs);

        // Mode badge chip
        gc.setFill(Color.web("#0B132B"));
        gc.fillRoundRect(8, 6, 115, 22, 4, 4);
        gc.setStroke(Color.web("#38BDF8", 0.6));
        gc.strokeRoundRect(8, 6, 115, 22, 4, 4);
        gc.setFill(Color.web("#38BDF8"));
        gc.fillText("SCOPE // VECTOR", 14, 20);

        // Live Dynamic Metrics
        gc.setFill(Color.web("#94A3B8"));
        gc.fillText("PEAK:", 135, 20);
        gc.setFill(peakDbfs >= -0.5f ? Color.web("#EF4444") : Color.web("#22D3EE"));
        gc.fillText(String.format("%+.1f dBFS", peakDbfs), 166, 20);

        gc.setFill(Color.web("#94A3B8"));
        gc.fillText("RMS:", 242, 20);
        gc.setFill(Color.web("#F1F5F9"));
        gc.fillText(String.format("%+.1f dBFS", rmsDbfs), 268, 20);

        gc.setFill(Color.web("#94A3B8"));
        gc.fillText("CREST:", 344, 20);
        gc.setFill(Color.web("#10B981"));
        gc.fillText(String.format("%.1f dB", crestFactor), 386, 20);

        // Dynamics Verdict Status Badge (Right side of Top HUD)
        if (peakDbfs >= -0.2f) {
            double badgeW = 195.0;
            double badgeX = w - badgeW - 10.0;
            gc.setFill(Color.rgb(239, 68, 68, 0.20));
            gc.fillRoundRect(badgeX, 6, badgeW, 22, 4, 4);
            gc.setStroke(Color.web("#EF4444", 0.70));
            gc.strokeRoundRect(badgeX, 6, badgeW, 22, 4, 4);
            gc.setFill(Color.web("#EF4444"));
            gc.fillText("⚠ TRUE PEAK CLIPPING RISK", badgeX + 8, 20);
        } else if (crestFactor >= 12.0f) {
            double badgeW = 205.0;
            double badgeX = w - badgeW - 10.0;
            gc.setFill(Color.rgb(16, 185, 129, 0.18));
            gc.fillRoundRect(badgeX, 6, badgeW, 22, 4, 4);
            gc.setStroke(Color.web("#10B981", 0.60));
            gc.strokeRoundRect(badgeX, 6, badgeW, 22, 4, 4);
            gc.setFill(Color.web("#10B981"));
            gc.fillText("✓ PRISTINE AUDIOPHILE DYNAMICS", badgeX + 8, 20);
        } else {
            double badgeW = 145.0;
            double badgeX = w - badgeW - 10.0;
            gc.setFill(Color.rgb(51, 65, 85, 0.25));
            gc.fillRoundRect(badgeX, 6, badgeW, 22, 4, 4);
            gc.setStroke(Color.web("#475569", 0.50));
            gc.strokeRoundRect(badgeX, 6, badgeW, 22, 4, 4);
            gc.setFill(Color.web("#94A3B8"));
            gc.fillText("✓ NORMAL DYNAMICS", badgeX + 8, 20);
        }
    }

    // =========================================================================
    // Mode 2: Audio Quality Benchmarks & Spectral Rolloff Curve
    // =========================================================================
    private void renderBenchmarksMode(double w, double h, double px, double py, double pw, double ph) {
        double maxFreq = nyquistHz > 0 ? nyquistHz : 22050;

        // 1. Dedicated Left Gutter: Clean dBFS Amplitude Scale (0 to -84 dBFS)
        gc.setFont(Font.font("JetBrains Mono", 8));
        double[] dbTicks = {0.0, -12.0, -24.0, -36.0, -48.0, -60.0, -72.0, -84.0};
        for (double db : dbTicks) {
            double norm = (db + 85.0) / 85.0;
            double y = py + ph - (norm * ph);

            // Tick in gutter
            gc.setStroke(Color.web("#334155"));
            gc.strokeLine(LEFT_GUTTER_W - 4.0, y, LEFT_GUTTER_W, y);

            // Label right-aligned
            gc.setFill(Color.web("#64748B"));
            String label = String.format("%3.0f dB", db);
            gc.fillText(label, 6.0, y + 3.0);

            // Subtle horizontal grid line across plot
            gc.setStroke(Color.rgb(255, 255, 255, 0.04));
            gc.strokeLine(px, y, px + pw, y);
        }

        // 2. Dedicated Bottom Gutter: Clean Non-Overlapping Frequency Ruler
        double[] freqTicks = (maxFreq >= 44100)
                ? new double[]{100, 1000, 5000, 10000, 16000, 20500, 22050, 32000, 44100, maxFreq}
                : new double[]{100, 500, 1000, 2500, 5000, 10000, 16000, 18500, 20500, 22050};

        String[] freqLabels = (maxFreq >= 44100)
                ? new String[]{"100", "1k", "5k", "10k", "16k", "20.5k", "22k", "32k", "44.1k", String.format("%.0fk", maxFreq / 1000.0)}
                : new String[]{"100", "500", "1k", "2.5k", "5k", "10k", "16k", "18.5k", "20.5k", "22.05k"};

        double lastDrawnX = -100.0;
        for (int i = 0; i < freqTicks.length; i++) {
            double f = freqTicks[i];
            if (f > maxFreq) continue;
            double fx = px + (f / maxFreq) * pw;

            // Tick in gutter
            gc.setStroke(Color.web("#334155"));
            gc.strokeLine(fx, h - BOTTOM_GUTTER_H, fx, h - BOTTOM_GUTTER_H + 3.0);

            // Non-overlapping label placement
            String lbl = freqLabels[i];
            double lblW = lbl.length() * 5.0;
            double lblLeft = fx - (lblW / 2.0);

            if (lblLeft > lastDrawnX + 8.0 && lblLeft + lblW < w - 2.0) {
                gc.setFill(Color.web("#64748B"));
                gc.fillText(lbl, lblLeft, h - 7.0);
                lastDrawnX = lblLeft + lblW;
            }
        }

        // 3. Lossless & Lossy Quality Benchmark Background Zones
        gc.save();
        gc.beginPath();
        gc.rect(px, py, pw, ph);
        gc.clip();

        // Zone A: Lossy MP3 128k (0 to 16 kHz)
        double x16k = px + (16000.0 / maxFreq) * pw;
        gc.setFill(Color.rgb(239, 68, 68, 0.04));
        gc.fillRect(px, py, Math.min(pw, x16k - px), ph);

        // Zone B: Mid-Bitrate Lossy 192k-320k (16 kHz to 20.5 kHz)
        double x20_5k = px + (20500.0 / maxFreq) * pw;
        if (x20_5k > x16k) {
            gc.setFill(Color.rgb(245, 158, 11, 0.04));
            gc.fillRect(x16k, py, Math.min(pw - (x16k - px), x20_5k - x16k), ph);
        }

        // Zone C: CD Lossless Zone (20.5 kHz to 22.05 kHz)
        double x22k = px + (22050.0 / maxFreq) * pw;
        if (x22k > x20_5k) {
            gc.setFill(Color.rgb(16, 185, 129, 0.06));
            gc.fillRect(x20_5k, py, Math.min(pw - (x20_5k - px), x22k - x20_5k), ph);
        }

        // Zone D: Studio Hi-Res Band (> 22.05 kHz)
        if (maxFreq > 22050 && x22k < px + pw) {
            gc.setFill(Color.rgb(6, 182, 212, 0.06));
            gc.fillRect(x22k, py, (px + pw) - x22k, ph);
        }

        // 4. Theoretical Lossless Reference Rolloff Curve (Faint Guide)
        gc.setStroke(Color.rgb(99, 102, 241, 0.25));
        gc.setLineWidth(1.2);
        gc.beginPath();
        for (int i = 0; i <= 80; i++) {
            double freq = (maxFreq / 80.0) * i;
            double x = px + (freq / maxFreq) * pw;
            double refDb = -20.0 - (18.0 * (freq / maxFreq));
            double y = py + ph - ((refDb + 85.0) / 85.0 * ph);
            if (i == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
        }
        gc.stroke();

        // 5. Real-Time Active Audio Spectrum Curve
        int numBins = currentMagnitudes.length;
        if (numBins > 2) {
            // Area Under Spectrum Curve
            gc.beginPath();
            gc.moveTo(px, py + ph);
            for (int k = 0; k < numBins; k++) {
                double freq = ((double) k / numBins) * maxFreq;
                double x = px + (freq / maxFreq) * pw;
                float mag = currentMagnitudes[k];
                float db = (mag > 1e-5f) ? (float) (20.0 * Math.log10(mag)) : -100.0f;
                double norm = Math.max(0.0, Math.min(1.0, (db + 85.0) / 85.0));
                double y = py + ph - (norm * ph);
                gc.lineTo(x, y);
            }
            gc.lineTo(px + pw, py + ph);
            gc.closePath();

            LinearGradient areaGrad = new LinearGradient(
                    0, py, 0, py + ph, false, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.rgb(6, 182, 212, 0.35)),
                    new Stop(0.5, Color.rgb(99, 102, 241, 0.16)),
                    new Stop(1.0, Color.rgb(3, 7, 18, 0.02))
            );
            gc.setFill(areaGrad);
            gc.fill();

            // Glow Stroke Pass
            gc.setStroke(Color.rgb(6, 182, 212, 0.35));
            gc.setLineWidth(3.8);
            drawSpectrumStroke(px, py, pw, ph, maxFreq, numBins);

            // Core Phosphor Stroke Pass
            gc.setStroke(Color.web("#22D3EE"));
            gc.setLineWidth(1.8);
            drawSpectrumStroke(px, py, pw, ph, maxFreq, numBins);
        }

        // 6. Staggered Non-Overlapping Quality Benchmark Guidelines & Flags
        drawStaggeredBenchmarkLine(16000, maxFreq, px, py, pw, ph, 0, "#EF4444", "128k (16.0k)");
        drawStaggeredBenchmarkLine(18500, maxFreq, px, py, pw, ph, 1, "#F97316", "192k (18.5k)");
        drawStaggeredBenchmarkLine(20500, maxFreq, px, py, pw, ph, 2, "#F59E0B", "320k (20.5k)");
        drawStaggeredBenchmarkLine(22050, maxFreq, px, py, pw, ph, 3, "#10B981", "CD (22.05k)");

        if (maxFreq >= 44100) {
            drawStaggeredBenchmarkLine(48000, maxFreq, px, py, pw, ph, 0, "#06B6D4", "Hi-Res (48k)");
        }

        // 7. Detected Cutoff Benchmark Guideline & Flag
        if (detectedCutoffHz > 0 && detectedCutoffHz < maxFreq - 500) {
            double cutX = px + ((double) detectedCutoffHz / maxFreq) * pw;

            gc.setStroke(Color.web("#EF4444", 0.90));
            gc.setLineWidth(1.8);
            gc.setLineDashes(5.0, 3.0);
            gc.strokeLine(cutX, py, cutX, py + ph);
            gc.setLineDashes(null);

            // Staggered Flag Badge below standard benchmark flags (Slot 4)
            double flagY = py + 8.0 + (4 * 22.0);
            String cutBadge = String.format("⚠ CUTOFF: %.1f kHz", detectedCutoffHz / 1000.0);
            drawBenchmarkPill(cutX, flagY, px, pw, "#EF4444", cutBadge);
        }

        gc.restore();

        // 8. Top HUD Readout Overlay with Clean Forensic Verdict Banner
        renderBenchmarksHud(w, maxFreq);
    }

    private void drawSpectrumStroke(double px, double py, double pw, double ph, double maxFreq, int numBins) {
        gc.beginPath();
        for (int k = 0; k < numBins; k++) {
            double freq = ((double) k / numBins) * maxFreq;
            double x = px + (freq / maxFreq) * pw;
            float mag = currentMagnitudes[k];
            float db = (mag > 1e-5f) ? (float) (20.0 * Math.log10(mag)) : -100.0f;
            double norm = Math.max(0.0, Math.min(1.0, (db + 85.0) / 85.0));
            double y = py + ph - (norm * ph);
            if (k == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
        }
        gc.stroke();
    }

    private void drawStaggeredBenchmarkLine(int freqHz, double maxFreq, double px, double py, double pw, double ph,
                                            int slotIndex, String hexColor, String label) {
        if (freqHz > maxFreq) return;
        double x = px + ((double) freqHz / maxFreq) * pw;

        // Vertical dashed guideline
        gc.setStroke(Color.web(hexColor, 0.45));
        gc.setLineWidth(1.0);
        gc.setLineDashes(4.0, 3.0);
        gc.strokeLine(x, py, x, py + ph);
        gc.setLineDashes(null);

        // Staggered vertical badge chip height (22px separation per slot)
        double flagY = py + 8.0 + (slotIndex * 22.0);
        drawBenchmarkPill(x, flagY, px, pw, hexColor, label);
    }

    private void drawBenchmarkPill(double lineX, double flagY, double px, double pw, String hexColor, String text) {
        gc.setFont(Font.font("JetBrains Mono", 8));
        double pillW = text.length() * 5.6 + 12.0;
        double pillH = 17.0;

        // Anchor pill to the right if space allows, otherwise anchor to the left of the guideline
        double pillX;
        if (lineX + pillW + 4.0 <= px + pw) {
            pillX = lineX + 3.0;
        } else {
            pillX = lineX - pillW - 3.0;
        }

        // Pill background
        gc.setFill(Color.web("#030712"));
        gc.fillRoundRect(pillX, flagY, pillW, pillH, 3, 3);

        // Crisp 1px border matching benchmark tier
        gc.setStroke(Color.web(hexColor, 0.70));
        gc.strokeRoundRect(pillX, flagY, pillW, pillH, 3, 3);

        // Crisp non-overlapping label text
        gc.setFill(Color.web(hexColor, 0.95));
        gc.fillText(text, pillX + 6.0, flagY + 12.0);
    }

    private void renderBenchmarksHud(double w, double maxFreq) {
        gc.setFont(Font.font("JetBrains Mono", 9));

        // Mode badge chip
        gc.setFill(Color.web("#0B132B"));
        gc.fillRoundRect(8, 6, 160, 22, 4, 4);
        gc.setStroke(Color.web("#818CF8", 0.6));
        gc.strokeRoundRect(8, 6, 160, 22, 4, 4);
        gc.setFill(Color.web("#818CF8"));
        gc.fillText("BENCHMARKS // QUALITY", 14, 20);

        // Nyquist Readout
        gc.setFill(Color.web("#94A3B8"));
        gc.fillText(String.format("NYQUIST: %.1f kHz", maxFreq / 1000.0), 180, 20);

        // Forensic Verdict Chip (Right-aligned in Top HUD, zero overlap with graph)
        if (detectedCutoffHz > 0 && detectedCutoffHz < maxFreq - 500) {
            String orig = (activeReport != null) ? activeReport.estimatedOriginalBitrate() : "LOSSY";
            String cutMsg = String.format("⚠ CUTOFF: %.1f kHz (%s)", detectedCutoffHz / 1000.0, orig);
            double chipW = cutMsg.length() * 6.5 + 20.0;
            double chipX = w - chipW - 10.0;

            gc.setFill(Color.rgb(239, 68, 68, 0.22));
            gc.fillRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setStroke(Color.web("#EF4444", 0.80));
            gc.strokeRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setFill(Color.web("#EF4444"));
            gc.fillText(cutMsg, chipX + 10, 20);
        } else if (activeReport != null && (activeReport.verdict() == Verdict.STANDARD_RESOLUTION || activeReport.verdict() == Verdict.GENUINE_LOSSLESS)) {
            String flacMsg = String.format("✓ STANDARD-RES: REACHES %.1f kHz (%.1f kHz)",
                    activeReport.cutoffFrequencyHz() / 1000.0,
                    activeReport.nyquistFrequencyHz() / 500.0);
            double chipW = flacMsg.length() * 6.2 + 20.0;
            double chipX = w - chipW - 10.0;

            gc.setFill(Color.rgb(245, 158, 11, 0.18));
            gc.fillRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setStroke(Color.web("#F59E0B", 0.65));
            gc.strokeRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setFill(Color.web("#F59E0B"));
            gc.fillText(flacMsg, chipX + 10, 20);
        } else if (activeReport != null && activeReport.verdict() == Verdict.STUDIO_HI_RES) {
            String hiResMsg = "✓ STUDIO HI-RES MASTER (> 48 kHz)";
            double chipW = hiResMsg.length() * 6.2 + 20.0;
            double chipX = w - chipW - 10.0;

            gc.setFill(Color.rgb(6, 182, 212, 0.18));
            gc.fillRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setStroke(Color.web("#06B6D4", 0.65));
            gc.strokeRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setFill(Color.web("#06B6D4"));
            gc.fillText(hiResMsg, chipX + 10, 20);
        } else {
            double chipW = 150.0;
            double chipX = w - chipW - 10.0;
            gc.setFill(Color.rgb(51, 65, 85, 0.25));
            gc.fillRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setStroke(Color.web("#475569", 0.50));
            gc.strokeRoundRect(chipX, 6, chipW, 22, 4, 4);
            gc.setFill(Color.web("#94A3B8"));
            gc.fillText("ANALYZING SPECTRUM...", chipX + 10, 20);
        }
    }
}
