package com.aura.audio.desktop;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Neo-Retro Hardware Master Deck 64-band Graphic Equalizer Visualizer.
 *
 * Features:
 * - Deep true-black (#010204) recessed display well with razor-sharp etched hardware borders.
 * - Precision segmented LED ladder bars with discrete stacked LED segments and dark segment gaps.
 * - Authentic hardware unlit LED ladder matrix backing.
 * - Tri-tier studio standard color coding:
 *     Green:      -48 dB to -12 dB (Normal operating range)
 *     Cyan/Amber: -12 dB to  -3 dB (Headroom / High energy)
 *     Crimson:     -3 dB to   0 dB (Peak overload ceiling)
 * - High-contrast peak hold needles with hardware ballistic hold and smooth decay with glowing cap leaks.
 * - Calibrated frequency axis ruler with crisp, non-overlapping tick markings.
 */
public final class EqualizerCanvas extends Canvas {

    private static final int NUM_BANDS = 64;
    private static final int PEAK_HOLD_DURATION_FRAMES = 14;

    private final GraphicsContext gc;
    private final float[] levels = new float[NUM_BANDS];
    private final float[] peaks = new float[NUM_BANDS];
    private final int[] peakHold = new int[NUM_BANDS];
    private final float[] targetBands = new float[NUM_BANDS];
    private final AtomicBoolean repaintPending = new AtomicBoolean(false);

    // Key frequency labels for 64-band logarithmic mapping
    private static final int[] TICK_BAND_INDICES = {0, 7, 15, 23, 31, 39, 47, 55, 63};
    private static final String[] TICK_LABELS = {"25", "63", "160", "400", "1k", "2.5k", "6.3k", "14k", "22k"};

    public EqualizerCanvas(double width, double height) {
        super(width, height);
        this.gc = getGraphicsContext2D();
        clear();

        widthProperty().addListener((obs, oldVal, newVal) -> render());
        heightProperty().addListener((obs, oldVal, newVal) -> render());
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
        return 30.0;
    }

    @Override
    public double prefWidth(double height) {
        return 800.0;
    }

    @Override
    public double prefHeight(double width) {
        return 80.0;
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

    public void clear() {
        synchronized (targetBands) {
            Arrays.fill(targetBands, 0.0f);
            Arrays.fill(levels, 0.0f);
            Arrays.fill(peaks, 0.0f);
            Arrays.fill(peakHold, 0);
        }
        render();
    }

    public void updateBands(float[] newBands) {
        if (newBands != null) {
            synchronized (targetBands) {
                int len = Math.min(newBands.length, NUM_BANDS);
                System.arraycopy(newBands, 0, targetBands, 0, len);
            }
        }
        requestRepaint();
    }

    public void requestRepaint() {
        if (repaintPending.compareAndSet(false, true)) {
            if (Platform.isFxApplicationThread()) {
                repaintPending.set(false);
                processAndRender();
            } else {
                Platform.runLater(() -> {
                    repaintPending.set(false);
                    processAndRender();
                });
            }
        }
    }

    private void processAndRender() {
        synchronized (targetBands) {
            for (int i = 0; i < NUM_BANDS; i++) {
                float target = targetBands[i];

                // Level attack and decay
                if (target > levels[i]) {
                    levels[i] = target;
                } else {
                    levels[i] = Math.max(0.0f, levels[i] - 0.040f);
                }

                // Peak hold needle physics
                if (levels[i] >= peaks[i]) {
                    peaks[i] = levels[i];
                    peakHold[i] = PEAK_HOLD_DURATION_FRAMES;
                } else {
                    if (peakHold[i] > 0) {
                        peakHold[i]--;
                    } else {
                        peaks[i] = Math.max(0.0f, peaks[i] - 0.018f);
                    }
                }
            }
        }
        render();
    }

    private void render() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Deep true-black recessed display well
        gc.setFill(Color.web("#010204"));
        gc.fillRect(0, 0, w, h);

        // Razor-sharp recessed hardware frame bevel
        gc.setLineWidth(1.0);
        gc.setStroke(Color.web("#0F172A"));
        gc.strokeRect(0.5, 0.5, w - 1.0, h - 1.0);

        gc.setStroke(Color.rgb(0, 0, 0, 0.8));
        gc.strokeLine(1, 1, w - 2, 1);
        gc.setStroke(Color.rgb(51, 65, 85, 0.25));
        gc.strokeLine(1, h - 1, w - 2, h - 1);

        // Layout measurements
        double topMargin = 5.0;
        double bottomRulerH = 15.0;
        double leftMargin = 8.0;
        double rightMargin = 8.0;
        double meterH = Math.max(10.0, h - topMargin - bottomRulerH);
        double meterW = Math.max(10.0, w - leftMargin - rightMargin);

        // Segment ladder calculation
        double idealPitch = 4.0; // 3px LED + 1px dark gap
        int numSegments = Math.max(12, Math.min(32, (int) (meterH / idealPitch)));
        double segPitch = meterH / numSegments;
        double segGap = Math.max(1.0, Math.min(1.5, segPitch * 0.25));
        double segH = Math.max(1.5, segPitch - segGap);

        // Band geometry
        double bandGap = Math.max(1.0, (meterW * 0.20) / (NUM_BANDS - 1));
        double barWidth = Math.max(1.0, (meterW - ((NUM_BANDS - 1) * bandGap)) / NUM_BANDS);

        // 2. Render 64-Band Precision Segmented LED Ladders
        for (int i = 0; i < NUM_BANDS; i++) {
            double x = leftMargin + i * (barWidth + bandGap);
            float bandLevel = levels[i];
            int litCount = (int) Math.round(bandLevel * numSegments);

            for (int s = 0; s < numSegments; s++) {
                double segY = topMargin + meterH - ((s + 1) * segPitch);
                double frac = (double) (s + 1) / numSegments;
                boolean isLit = (s < litCount);

                // Tri-tier studio hardware color coding
                if (frac <= 0.65) {
                    // Green: -48dB to -12dB
                    gc.setFill(isLit ? Color.web("#10B981") : Color.web("#041B12"));
                } else if (frac <= 0.78) {
                    // Cyan: -12dB to -6dB
                    gc.setFill(isLit ? Color.web("#06B6D4") : Color.web("#041B22"));
                } else if (frac <= 0.88) {
                    // Amber/Yellow: -6dB to -3dB
                    gc.setFill(isLit ? Color.web("#F59E0B") : Color.web("#1E1404"));
                } else {
                    // Crimson/Red: -3dB to 0dB peak overload
                    gc.setFill(isLit ? Color.web("#EF4444") : Color.web("#26080B"));
                }

                // Crisp solid LED segment (no fuzzy gradients)
                gc.fillRect(x, segY, barWidth, segH);

                // Subtle top edge specular highlight on lit segments for authentic 3D diode lens look
                if (isLit && segH >= 2.5) {
                    gc.setFill(Color.rgb(255, 255, 255, 0.22));
                    gc.fillRect(x, segY, barWidth, 1.0);
                }
            }

            // 3. High-Contrast Peak Hold Needle with Glowing Cap Leak
            float peakVal = peaks[i];
            if (peakVal > 0.02f) {
                double peakY = topMargin + meterH - (peakVal * meterH);
                double needleH = Math.max(2.0, Math.min(3.0, segH));
                double needleY = Math.max(topMargin, peakY - needleH);

                // Subtle glowing cap leak
                Color capGlowColor;
                if (peakVal > 0.88f) {
                    capGlowColor = Color.rgb(239, 68, 68, 0.40); // ruby glow leak
                } else if (peakVal > 0.78f) {
                    capGlowColor = Color.rgb(245, 158, 11, 0.35); // amber glow leak
                } else if (peakVal > 0.65f) {
                    capGlowColor = Color.rgb(6, 182, 212, 0.35);  // cyan glow leak
                } else {
                    capGlowColor = Color.rgb(16, 185, 129, 0.30); // emerald glow leak
                }

                gc.setFill(capGlowColor);
                gc.fillRect(x - 1.0, needleY - 1.0, barWidth + 2.0, needleH + 2.0);

                // Razor-sharp high-contrast solid needle core
                gc.setFill(Color.WHITE);
                gc.fillRect(x, needleY, barWidth, needleH);
            }
        }

        // 4. Hardware Frequency Rulers & Non-Overlapping Tick Markings
        double rulerY = topMargin + meterH + 2.0;
        gc.setLineWidth(1.0);
        gc.setStroke(Color.web("#1E293B"));
        gc.strokeLine(leftMargin, rulerY, leftMargin + meterW, rulerY);

        gc.setFont(Font.font("JetBrains Mono", 8));
        double lastLabelRight = -100.0;

        for (int k = 0; k < TICK_BAND_INDICES.length; k++) {
            int bIdx = TICK_BAND_INDICES[k];
            double bx = leftMargin + bIdx * (barWidth + bandGap) + (barWidth / 2.0);

            // Tick mark
            gc.setStroke(Color.web("#334155"));
            gc.strokeLine(bx, rulerY, bx, rulerY + 3.0);

            // Label
            String label = TICK_LABELS[k];
            double labelW = label.length() * 4.8;
            double labelLeft = bx - (labelW / 2.0);

            // Prevent collision
            if (labelLeft > lastLabelRight + 6.0 && labelLeft + labelW < w - 2.0) {
                gc.setFill(Color.web("#64748B"));
                gc.fillText(label, labelLeft, rulerY + 11.0);
                lastLabelRight = labelLeft + labelW;
            }
        }
    }
}
