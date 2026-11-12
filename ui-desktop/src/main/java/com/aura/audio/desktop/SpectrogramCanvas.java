package com.aura.audio.desktop;

import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * High-performance 60fps Neo-Retro Spectrogram Waterfall & Interactive Timeline Canvas.
 *
 * Features:
 * - Continuous YouTube / DAW style pre-buffering engine with full track and windowed views.
 * - Interactive dual-axis crosshairs (both X-time and Y-frequency) with floating HUD telemetry.
 * - Mouse-wheel and button-driven time-axis zooming (1.0x to 16.0x) with drag panning.
 * - Dedicated right-gutter frequency & benchmark ruler with anti-collision layout and screen-size dynamic scaling.
 * - Non-overlapping detected cutoff warning indicator and HUD banner.
 * - Neo-retro CRT phosphor raster scanlines, glowing leak accents, and studio reticle frame.
 * - Zero-copy, thread-safe background frame ingestion with 60fps frame coalescing.
 */
public final class SpectrogramCanvas extends Canvas {

    public enum ViewMode {
        FULL_TRACK,
        SCROLLING_WINDOW,
        LIVE_STREAM
    }

    private static final int TIMELINE_HEIGHT = 20;

    // Fast 256-entry ARGB Colormap Lookup Table (Black -> Indigo -> Purple -> Orange -> Hot White)
    private static final int[] COLORMAP_LUT = buildColormapLut();

    private final GraphicsContext gc;
    private final Object dataLock = new Object();
    private final AtomicBoolean repaintPending = new AtomicBoolean(false);

    // Geometry
    private int bufferWidth = 800;
    private int bufferHeight = 260;
    private int rulerWidth = 110;
    private int spectroWidth = 690;
    private int spectroHeight = 240;

    // Pixel buffers
    private int[] livePixelData;
    private WritableImage liveImage;
    private boolean liveDirty = false;

    private int[] prebufferedPixelData;
    private WritableImage prebufferedImage;
    private boolean prebufferedDirty = false;

    // Slices and pre-buffering
    public record SpectroSlice(float[] magnitudes, long positionMs) {}
    private final List<SpectroSlice> prebufferedSlices = new ArrayList<>();
    private double bufferedRatio = 0.0;
    private long totalDurationMs = 0;
    private long currentPlaybackPositionMs = 0;

    // Cutoff and Benchmarks
    private int detectedCutoffHz = -1;
    private int nyquistHz = 22050;
    private String cutoffLabel = "";

    // Zoom & Pan Capabilities
    private double zoomFactor = 1.0; // 1.0x to 16.0x
    private double zoomCenterFraction = 0.5; // 0.0 to 1.0
    private double lastDragX = -1;
    private boolean isPanning = false;

    // View & Interaction
    private ViewMode viewMode = ViewMode.FULL_TRACK;
    private Consumer<Long> onSeekRequested;
    private boolean hovering = false;
    private boolean isScrubbing = false;
    private double hoverX = -1;
    private double hoverY = -1;

    public SpectrogramCanvas(double width, double height) {
        super(width, height);
        this.gc = getGraphicsContext2D();

        initBuffers((int) Math.max(100, width), (int) Math.max(50, height));
        initEventHandlers();

        widthProperty().addListener((obs, oldVal, newVal) -> {
            int w = newVal.intValue();
            int h = (int) getHeight();
            if (w > 50 && h > 50 && (w != bufferWidth || h != bufferHeight)) {
                resizeBuffers(w, h);
            }
        });
        heightProperty().addListener((obs, oldVal, newVal) -> {
            int w = (int) getWidth();
            int h = newVal.intValue();
            if (w > 50 && h > 50 && (w != bufferWidth || h != bufferHeight)) {
                resizeBuffers(w, h);
            }
        });
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
        int w = (int) width;
        int h = (int) height;
        if (w > 50 && h > 50 && (w != bufferWidth || h != bufferHeight)) {
            resizeBuffers(w, h);
        }
    }

    private void initBuffers(int w, int h) {
        this.bufferWidth = Math.max(100, w);
        this.bufferHeight = Math.max(50, h);

        double scale = Math.max(0.85, Math.min(1.4, bufferHeight / 400.0));
        this.rulerWidth = (int) Math.round(110 * scale);
        this.spectroWidth = Math.max(50, bufferWidth - rulerWidth);
        this.spectroHeight = Math.max(30, bufferHeight - TIMELINE_HEIGHT);

        synchronized (dataLock) {
            this.livePixelData = new int[spectroWidth * spectroHeight];
            Arrays.fill(livePixelData, 0xFF05070D);
            this.liveImage = new WritableImage(spectroWidth, spectroHeight);
            this.liveDirty = true;

            this.prebufferedPixelData = new int[spectroWidth * spectroHeight];
            Arrays.fill(prebufferedPixelData, 0xFF05070D);
            this.prebufferedImage = new WritableImage(spectroWidth, spectroHeight);
            this.prebufferedDirty = true;
        }

        renderFrame();
    }

    private void resizeBuffers(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == this.bufferWidth && h == this.bufferHeight) return;

        this.bufferWidth = w;
        this.bufferHeight = h;

        double scale = Math.max(0.85, Math.min(1.4, bufferHeight / 400.0));
        this.rulerWidth = (int) Math.round(110 * scale);
        this.spectroWidth = Math.max(50, bufferWidth - rulerWidth);
        this.spectroHeight = Math.max(30, bufferHeight - TIMELINE_HEIGHT);

        synchronized (dataLock) {
            this.livePixelData = new int[spectroWidth * spectroHeight];
            Arrays.fill(livePixelData, 0xFF05070D);
            this.liveImage = new WritableImage(spectroWidth, spectroHeight);
            this.liveDirty = true;

            this.prebufferedImage = new WritableImage(spectroWidth, spectroHeight);
            rebuildPrebufferedImage();
        }

        requestRepaint();
    }

    private void initEventHandlers() {
        setOnMouseMoved(this::handleMouseMoved);
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
        setOnMouseClicked(this::handleMouseClicked);
        setOnMouseExited(this::handleMouseExited);
        setOnScroll(this::handleScroll);
    }

    // =========================================================================
    // Public API for Playback, Pre-Buffering & Zoom
    // =========================================================================

    public void setOnSeekRequested(Consumer<Long> callback) {
        this.onSeekRequested = callback;
    }

    public Consumer<Long> getOnSeekRequested() {
        return this.onSeekRequested;
    }

    private Consumer<Double> onZoomChanged;

    public void setOnZoomChanged(Consumer<Double> callback) {
        this.onZoomChanged = callback;
    }

    private void notifyZoomChanged() {
        if (onZoomChanged != null) {
            try {
                if (Platform.isFxApplicationThread()) {
                    onZoomChanged.accept(zoomFactor);
                } else {
                    Platform.runLater(() -> onZoomChanged.accept(zoomFactor));
                }
            } catch (Exception ignored) {}
        }
    }

    public void setPlaybackPosition(long positionMs, long durationMs) {
        this.currentPlaybackPositionMs = Math.max(0, positionMs);
        if (durationMs > 0) {
            this.totalDurationMs = durationMs;
        }
        requestRepaint();
    }

    public void setPlaybackPosition(long positionMs) {
        this.currentPlaybackPositionMs = Math.max(0, positionMs);
        requestRepaint();
    }

    public long getPlaybackPositionMs() {
        return this.currentPlaybackPositionMs;
    }

    public long getTotalDurationMs() {
        return this.totalDurationMs;
    }

    public void setBufferedRatio(double ratio) {
        this.bufferedRatio = Math.max(0.0, Math.min(1.0, ratio));
        requestRepaint();
    }

    public double getBufferedRatio() {
        return this.bufferedRatio;
    }

    public boolean isPrebuffered() {
        synchronized (dataLock) {
            return !prebufferedSlices.isEmpty() || bufferedRatio > 0.05;
        }
    }

    public void setViewMode(ViewMode mode) {
        if (mode != null) {
            this.viewMode = mode;
            requestRepaint();
        }
    }

    public ViewMode getViewMode() {
        return this.viewMode;
    }

    public double getZoomFactor() {
        return this.zoomFactor;
    }

    public void zoomIn() {
        zoomInAt(spectroWidth / 2.0);
    }

    public void zoomOut() {
        zoomOutAt(spectroWidth / 2.0);
    }

    public void resetZoom() {
        this.zoomFactor = 1.0;
        this.zoomCenterFraction = 0.5;
        notifyZoomChanged();
        requestRepaint();
    }

    public void zoomInAt(double mouseX) {
        if (totalDurationMs <= 0 || spectroWidth <= 0) return;
        double oldSpan = 1.0 / zoomFactor;
        double oldStart = Math.max(0.0, Math.min(1.0 - oldSpan, zoomCenterFraction - (oldSpan / 2.0)));
        double mouseFraction = oldStart + (mouseX / spectroWidth) * oldSpan;

        double newZoom = Math.min(16.0, zoomFactor * 1.30);
        double newSpan = 1.0 / newZoom;
        double newStart = mouseFraction - (mouseX / spectroWidth) * newSpan;
        double newCenter = newStart + (newSpan / 2.0);

        this.zoomFactor = newZoom;
        this.zoomCenterFraction = Math.max(newSpan / 2.0, Math.min(1.0 - (newSpan / 2.0), newCenter));
        notifyZoomChanged();
        requestRepaint();
    }

    public void zoomOutAt(double mouseX) {
        if (totalDurationMs <= 0 || spectroWidth <= 0) return;
        double newZoom = Math.max(1.0, zoomFactor / 1.30);
        if (newZoom <= 1.02) {
            resetZoom();
            return;
        }
        double oldSpan = 1.0 / zoomFactor;
        double oldStart = Math.max(0.0, Math.min(1.0 - oldSpan, zoomCenterFraction - (oldSpan / 2.0)));
        double mouseFraction = oldStart + (mouseX / spectroWidth) * oldSpan;

        double newSpan = 1.0 / newZoom;
        double newStart = mouseFraction - (mouseX / spectroWidth) * newSpan;
        double newCenter = newStart + (newSpan / 2.0);

        this.zoomFactor = newZoom;
        this.zoomCenterFraction = Math.max(newSpan / 2.0, Math.min(1.0 - (newSpan / 2.0), newCenter));
        notifyZoomChanged();
        requestRepaint();
    }

    public void appendPrebufferedSlice(float[] slice, long positionMs, long totalDurationMs) {
        if (slice == null || slice.length == 0) return;
        if (totalDurationMs > 0) {
            this.totalDurationMs = totalDurationMs;
        }

        synchronized (dataLock) {
            prebufferedSlices.add(new SpectroSlice(slice.clone(), positionMs));
            if (this.totalDurationMs > 0) {
                this.bufferedRatio = Math.min(1.0, (double) positionMs / this.totalDurationMs);
            }
            updatePrebufferedColumn(slice, positionMs);
        }

        requestRepaint();
    }

    public void setPrebufferedSlices(List<float[]> allSlices, long totalDurationMs) {
        if (allSlices == null || allSlices.isEmpty()) return;
        this.totalDurationMs = totalDurationMs;

        synchronized (dataLock) {
            prebufferedSlices.clear();
            long stepMs = totalDurationMs / Math.max(1, allSlices.size());
            for (int i = 0; i < allSlices.size(); i++) {
                prebufferedSlices.add(new SpectroSlice(allSlices.get(i).clone(), i * stepMs));
            }
            this.bufferedRatio = 1.0;
            rebuildPrebufferedImage();
        }

        requestRepaint();
    }

    public void pushSlice(float[] magnitudes) {
        if (magnitudes == null || magnitudes.length == 0) return;
        if (livePixelData == null || spectroWidth <= 0 || spectroHeight <= 0) return;

        synchronized (dataLock) {
            int step = 2;
            int w = spectroWidth;
            int h = spectroHeight;

            // Shift live buffer to the left
            for (int y = 0; y < h; y++) {
                int rowOffset = y * w;
                System.arraycopy(livePixelData, rowOffset + step, livePixelData, rowOffset, w - step);
            }

            // Render new column at the right edge
            int numBins = magnitudes.length;
            for (int y = 0; y < h; y++) {
                int binIdx = (int) (((double) (h - 1 - y) / h) * numBins);
                binIdx = Math.max(0, Math.min(binIdx, numBins - 1));
                float mag = magnitudes[binIdx];

                float db = (mag > 1e-5f) ? (float) (20.0 * Math.log10(mag)) : -100.0f;
                float normalized = (db + 80.0f) / 80.0f;
                normalized = Math.max(0.0f, Math.min(1.0f, normalized));
                float intensity = (float) Math.pow(normalized, 1.25);
                int lutIdx = Math.max(0, Math.min(255, (int) (intensity * 255.0f)));
                int argb = COLORMAP_LUT[lutIdx];

                for (int x = w - step; x < w; x++) {
                    livePixelData[y * w + x] = argb;
                }
            }
            liveDirty = true;
        }

        requestRepaint();
    }

    public void pushSlice(float[] magnitudes, long positionMs, long durationMs) {
        setPlaybackPosition(positionMs, durationMs);
        pushSlice(magnitudes);
    }

    public void setCutoffMarker(int cutoffHz, int nyquist, String label) {
        this.detectedCutoffHz = cutoffHz;
        this.nyquistHz = nyquist > 0 ? nyquist : 22050;
        this.cutoffLabel = label != null ? label : "";
        requestRepaint();
    }

    public void clear() {
        synchronized (dataLock) {
            if (livePixelData != null) {
                Arrays.fill(livePixelData, 0xFF05070D);
            }
            if (prebufferedPixelData != null) {
                Arrays.fill(prebufferedPixelData, 0xFF05070D);
            }
            prebufferedSlices.clear();
            bufferedRatio = 0.0;
            currentPlaybackPositionMs = 0;
            totalDurationMs = 0;
            detectedCutoffHz = -1;
            cutoffLabel = "";
            zoomFactor = 1.0;
            zoomCenterFraction = 0.5;
            liveDirty = true;
            prebufferedDirty = true;
        }
        notifyZoomChanged();
        requestRepaint();
    }

    public void clearPrebuffered() {
        synchronized (dataLock) {
            if (prebufferedPixelData != null) {
                Arrays.fill(prebufferedPixelData, 0xFF05070D);
            }
            prebufferedSlices.clear();
            bufferedRatio = 0.0;
            prebufferedDirty = true;
        }
        requestRepaint();
    }

    // =========================================================================
    // Mouse Event Handlers for Direct Scrubbing, Zoom & Pan
    // =========================================================================

    private void handleScroll(ScrollEvent e) {
        if (totalDurationMs <= 0) return;
        double delta = e.getDeltaY();
        double mouseX = e.getX();
        if (mouseX < 0 || mouseX > spectroWidth) mouseX = spectroWidth / 2.0;

        if (delta > 0) {
            zoomInAt(mouseX);
        } else if (delta < 0) {
            zoomOutAt(mouseX);
        }
        e.consume();
    }

    private void handleMouseMoved(MouseEvent e) {
        if (e.getX() >= 0 && e.getX() <= spectroWidth && e.getY() >= 0 && e.getY() <= spectroHeight + TIMELINE_HEIGHT) {
            hovering = true;
            hoverX = e.getX();
            hoverY = Math.max(0, Math.min(spectroHeight, e.getY()));
            setCursor(Cursor.CROSSHAIR);
        } else {
            hovering = false;
            setCursor(Cursor.DEFAULT);
        }
        requestRepaint();
    }

    private void handleMousePressed(MouseEvent e) {
        if (e.getX() >= 0 && e.getX() <= spectroWidth) {
            if (e.getButton() == MouseButton.SECONDARY || e.isShiftDown()) {
                isPanning = true;
                lastDragX = e.getX();
                setCursor(Cursor.CLOSED_HAND);
            } else {
                isScrubbing = true;
                hovering = true;
                hoverX = e.getX();
                hoverY = Math.max(0, Math.min(spectroHeight, e.getY()));
                seekToX(e.getX());
            }
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (isPanning && zoomFactor > 1.0) {
            double dx = e.getX() - lastDragX;
            lastDragX = e.getX();
            double span = 1.0 / zoomFactor;
            double deltaFrac = -(dx / spectroWidth) * span;
            this.zoomCenterFraction = Math.max(span / 2.0, Math.min(1.0 - (span / 2.0), zoomCenterFraction + deltaFrac));
            requestRepaint();
            return;
        }

        if (isScrubbing) {
            hovering = true;
            hoverX = Math.max(0, Math.min(spectroWidth, e.getX()));
            hoverY = Math.max(0, Math.min(spectroHeight, e.getY()));
            seekToX(hoverX);
        }
    }

    private void handleMouseReleased(MouseEvent e) {
        isScrubbing = false;
        isPanning = false;
        setCursor(hovering ? Cursor.CROSSHAIR : Cursor.DEFAULT);
        requestRepaint();
    }

    private void handleMouseClicked(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY && !isPanning && e.getX() >= 0 && e.getX() <= spectroWidth) {
            seekToX(e.getX());
        }
    }

    private void handleMouseExited(MouseEvent e) {
        if (!isScrubbing && !isPanning) {
            hovering = false;
            setCursor(Cursor.DEFAULT);
            requestRepaint();
        }
    }

    private void seekToX(double x) {
        if (totalDurationMs <= 0 || spectroWidth <= 0) return;
        long seekMs = getTimeAtX(x);
        this.currentPlaybackPositionMs = seekMs;
        if (onSeekRequested != null) {
            try {
                onSeekRequested.accept(seekMs);
            } catch (Exception ex) {
                // Ignore consumer error
            }
        }
        requestRepaint();
    }

    public long getTimeAtX(double x) {
        if (totalDurationMs <= 0 || spectroWidth <= 0) return 0;
        double windowFraction = 1.0 / zoomFactor;
        double centerFraction = (viewMode == ViewMode.SCROLLING_WINDOW) ?
                (double) currentPlaybackPositionMs / totalDurationMs : zoomCenterFraction;

        double startFraction = Math.max(0.0, Math.min(1.0 - windowFraction, centerFraction - (windowFraction / 2.0)));
        double frac = startFraction + (Math.max(0.0, Math.min(spectroWidth, x)) / spectroWidth) * windowFraction;
        return (long) (Math.max(0.0, Math.min(1.0, frac)) * totalDurationMs);
    }

    // =========================================================================
    // Painting Pipeline
    // =========================================================================

    public void requestRepaint() {
        if (repaintPending.compareAndSet(false, true)) {
            if (Platform.isFxApplicationThread()) {
                repaintPending.set(false);
                renderFrame();
            } else {
                Platform.runLater(() -> {
                    repaintPending.set(false);
                    renderFrame();
                });
            }
        }
    }

    private void renderFrame() {
        double canvasW = getWidth();
        double canvasH = getHeight();
        if (canvasW <= 0 || canvasH <= 0) return;

        // 1. Sync dirty pixels to WritableImage if needed
        synchronized (dataLock) {
            if (isPrebuffered() && viewMode != ViewMode.LIVE_STREAM) {
                if (prebufferedDirty && prebufferedImage != null && prebufferedPixelData != null) {
                    prebufferedImage.getPixelWriter().setPixels(
                            0, 0, spectroWidth, spectroHeight,
                            PixelFormat.getIntArgbInstance(),
                            prebufferedPixelData, 0, spectroWidth
                    );
                    prebufferedDirty = false;
                }
            } else {
                if (liveDirty && liveImage != null && livePixelData != null) {
                    liveImage.getPixelWriter().setPixels(
                            0, 0, spectroWidth, spectroHeight,
                            PixelFormat.getIntArgbInstance(),
                            livePixelData, 0, spectroWidth
                    );
                    liveDirty = false;
                }
            }
        }

        // 2. Clear canvas with deep obsidian/true-black background
        gc.setFill(Color.web("#03050B"));
        gc.fillRect(0, 0, canvasW, canvasH);

        // 3. Draw spectrogram blit (with Zoom support)
        drawSpectrogram(canvasW, canvasH);

        // 4. Draw CRT scanlines & glow leaks
        drawCrtEffects();

        // 5. Draw frequency guidelines and cutoff line
        drawGuidelines();

        // 6. Draw dedicated frequency and benchmark ruler (with Anti-Collision)
        drawRuler(canvasW, canvasH);

        // 7. Draw cutoff warning HUD banner if cutoff detected
        drawCutoffHudBanner();

        // 8. Draw timeline and buffering progress bar
        drawTimeline();

        // 9. Draw illuminated laser playhead needle
        drawPlayhead();

        // 10. Draw dual-axis hover crosshairs & floating telemetry HUD
        drawHoverOverlay();

        // 11. Draw hardware frame accents (reticles/bezels)
        drawHardwareBezel();
    }

    private void drawSpectrogram(double canvasW, double canvasH) {
        if (spectroWidth <= 0 || spectroHeight <= 0) return;

        if (isPrebuffered() && viewMode != ViewMode.LIVE_STREAM) {
            double windowFraction = 1.0 / zoomFactor;
            double centerFraction;
            if (viewMode == ViewMode.SCROLLING_WINDOW && totalDurationMs > 0) {
                long windowMs = (long) (20000 / zoomFactor);
                windowFraction = (double) windowMs / totalDurationMs;
                centerFraction = (double) currentPlaybackPositionMs / totalDurationMs;
            } else {
                centerFraction = zoomCenterFraction;
            }

            windowFraction = Math.max(0.001, Math.min(1.0, windowFraction));
            double startFraction = Math.max(0.0, Math.min(1.0 - windowFraction, centerFraction - (windowFraction / 2.0)));

            double sx = startFraction * spectroWidth;
            double sw = windowFraction * spectroWidth;

            if (prebufferedImage != null) {
                gc.drawImage(prebufferedImage, sx, 0, Math.max(1, sw), spectroHeight,
                        0, 0, spectroWidth, spectroHeight);
            }
        } else {
            if (liveImage != null) {
                gc.drawImage(liveImage, 0, 0, spectroWidth, spectroHeight);
            }
        }
    }

    private void drawCrtEffects() {
        if (spectroWidth <= 0 || spectroHeight <= 0) return;

        // Subtle CRT phosphor line raster (every 3 pixels)
        gc.setStroke(Color.color(0.0, 0.0, 0.0, 0.12));
        gc.setLineWidth(1.0);
        for (int y = 0; y < spectroHeight; y += 3) {
            gc.strokeLine(0, y + 0.5, spectroWidth, y + 0.5);
        }

        // Top cyan glow leak
        LinearGradient topGlow = new LinearGradient(
                0, 0, 0, 16, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#06B6D4", 0.10)),
                new Stop(1, Color.TRANSPARENT)
        );
        gc.setFill(topGlow);
        gc.fillRect(0, 0, spectroWidth, 16);

        // Bottom subtle glow leak (above timeline)
        LinearGradient btmGlow = new LinearGradient(
                0, spectroHeight - 12, 0, spectroHeight, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT),
                new Stop(1, Color.web("#06B6D4", 0.06))
        );
        gc.setFill(btmGlow);
        gc.fillRect(0, spectroHeight - 12, spectroWidth, 12);
    }

    private void drawGuidelines() {
        if (spectroWidth <= 0 || spectroHeight <= 0) return;

        List<RulerItem> items = getRulerItems();
        for (RulerItem item : items) {
            double ratio = (double) item.freqHz / nyquistHz;
            if (ratio > 1.0) continue;
            double y = spectroHeight * (1.0 - ratio);

            gc.setStroke(Color.web(item.hexColor, item.isBenchmark ? 0.22 : 0.12));
            gc.setLineWidth(1.0);
            if (item.isBenchmark) {
                gc.setLineDashes(4.0, 4.0);
            } else {
                gc.setLineDashes(2.0, 4.0);
            }
            gc.strokeLine(0, y, spectroWidth, y);
            gc.setLineDashes(null);
        }

        // Detected Cutoff Guideline
        if (detectedCutoffHz > 0 && detectedCutoffHz < nyquistHz) {
            double ratio = (double) detectedCutoffHz / nyquistHz;
            double y = spectroHeight * (1.0 - ratio);

            // Glowing halo
            gc.setStroke(Color.web("#EF4444", 0.28));
            gc.setLineWidth(3.5);
            gc.strokeLine(0, y, spectroWidth, y);

            // Crisp dashed laser line
            gc.setStroke(Color.web("#EF4444", 0.95));
            gc.setLineWidth(1.5);
            gc.setLineDashes(6.0, 4.0);
            gc.strokeLine(0, y, spectroWidth, y);
            gc.setLineDashes(null);
        }
    }

    private void drawRuler(double canvasW, double canvasH) {
        if (spectroWidth >= canvasW) return;
        double currentRulerW = canvasW - spectroWidth;

        // Dynamic scale factor based on screen/canvas height:
        double scale = Math.max(0.85, Math.min(1.45, canvasH / 420.0));
        double fontSize = Math.round(9.5 * scale);
        double chipFontSize = Math.round(8.5 * scale);
        double minGap = Math.round(18.0 * scale);

        // Dedicated ruler background column
        gc.setFill(Color.web("#060913"));
        gc.fillRect(spectroWidth, 0, currentRulerW, canvasH);

        // Precision divider line
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(1.0);
        gc.strokeLine(spectroWidth, 0, spectroWidth, canvasH);

        // Header label
        gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, Math.round(8.5 * scale)));
        gc.setFill(Color.web("#64748B"));
        gc.fillText("FREQ / TIER", spectroWidth + 8, Math.max(12, 14 * scale));

        List<RulerItem> items = getRulerItems();
        double prevY = 16.0 * scale;
        for (RulerItem item : items) {
            double ratio = (double) item.freqHz / nyquistHz;
            if (ratio > 1.0) continue;
            double targetY = spectroHeight * (1.0 - ratio);
            double actualY = Math.max(targetY, prevY + minGap);

            if (actualY > spectroHeight + 4) continue;

            // Tick mark at divider line
            gc.setStroke(Color.web(item.hexColor, item.isCutoff ? 0.95 : 0.70));
            gc.setLineWidth(item.isCutoff ? 1.5 : 1.0);
            gc.strokeLine(spectroWidth, targetY, spectroWidth + (item.isBenchmark ? 6 : 4), targetY);

            if (item.isBenchmark) {
                // Pill chip
                double chipX = spectroWidth + 7;
                double chipY = actualY - (8.5 * scale);
                double chipW = Math.round(36 * scale);
                double chipH = Math.round(16 * scale);

                if (item.isCutoff) {
                    gc.setFill(Color.web("#7F1D1D"));
                    gc.fillRoundRect(chipX, chipY, chipW, chipH, 4, 4);
                    gc.setStroke(Color.web("#EF4444"));
                    gc.setLineWidth(1.3);
                    gc.strokeRoundRect(chipX, chipY, chipW, chipH, 4, 4);

                    gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, chipFontSize));
                    gc.setFill(Color.WHITE);
                    gc.fillText(item.chipText, chipX + 5, chipY + (11 * scale));

                    gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, fontSize));
                    gc.setFill(Color.web("#EF4444"));
                    gc.fillText(item.freqLabel, chipX + chipW + 4, actualY + 3);
                } else {
                    gc.setFill(Color.web(item.hexColor, 0.16));
                    gc.fillRoundRect(chipX, chipY, chipW, chipH, 4, 4);
                    gc.setStroke(Color.web(item.hexColor, 0.70));
                    gc.setLineWidth(1.0);
                    gc.strokeRoundRect(chipX, chipY, chipW, chipH, 4, 4);

                    gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, chipFontSize));
                    gc.setFill(Color.web(item.hexColor));
                    gc.fillText(item.chipText, chipX + 5, chipY + (11 * scale));

                    gc.setFont(Font.font("JetBrains Mono", FontWeight.SEMI_BOLD, fontSize));
                    gc.setFill(Color.web("#F1F5F9"));
                    gc.fillText(item.freqLabel, chipX + chipW + 4, actualY + 3);
                }
            } else {
                gc.setFont(Font.font("JetBrains Mono", FontWeight.MEDIUM, fontSize));
                gc.setFill(Color.web("#CBD5E1"));
                gc.fillText(item.freqLabel, spectroWidth + 10, actualY + 3);
            }

            prevY = actualY;
        }

        // Real-time hover indicator on right ruler (Y-Axis)
        if (hovering && hoverY >= 0 && hoverY <= spectroHeight) {
            double freqRatio = Math.max(0.0, Math.min(1.0, (spectroHeight - hoverY) / spectroHeight));
            int hoverHz = (int) (freqRatio * nyquistHz);
            String hoverFreqStr = (hoverHz >= 1000) ? String.format("%.1fk", hoverHz / 1000.0) : String.format("%dHz", hoverHz);

            double arrowX = spectroWidth;
            gc.setFill(Color.web("#F59E0B"));
            gc.fillPolygon(
                    new double[]{arrowX, arrowX + 5, arrowX + 5},
                    new double[]{hoverY, hoverY - 4, hoverY + 4},
                    3
            );
            gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, Math.max(9.0, 9.5 * scale)));
            gc.setFill(Color.web("#F59E0B"));
            gc.fillText("◀ " + hoverFreqStr, arrowX + 7, hoverY + 3.5);
        }
    }

    private void drawCutoffHudBanner() {
        if (detectedCutoffHz <= 0 || detectedCutoffHz >= nyquistHz - 300) return;

        double bx = 12;
        double by = 12;
        double bw = 270;
        double bh = 22;

        // Background HUD pill card
        gc.setFill(Color.web("#090C16", 0.94));
        gc.fillRoundRect(bx, by, bw, bh, 5, 5);
        gc.setStroke(Color.web("#EF4444", 0.85));
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(bx, by, bw, bh, 5, 5);

        // Glowing red LED dot
        gc.setFill(Color.web("#EF4444", 0.35));
        gc.fillOval(bx + 7, by + 6, 10, 10);
        gc.setFill(Color.web("#EF4444"));
        gc.fillOval(bx + 9, by + 8, 6, 6);

        // Cutoff warning text
        gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 9.5));
        gc.setFill(Color.web("#FCA5A5"));
        String text = "CUTOFF DETECTED: " + cutoffLabel;
        gc.fillText(text, bx + 22, by + 15);
    }

    private void drawTimeline() {
        if (spectroWidth <= 0 || TIMELINE_HEIGHT <= 0) return;

        double ty = spectroHeight;
        double th = TIMELINE_HEIGHT;

        // Timeline background
        gc.setFill(Color.web("#040711"));
        gc.fillRect(0, ty, spectroWidth, th);

        // Top divider separating spectrogram from timeline
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(1.0);
        gc.strokeLine(0, ty, spectroWidth, ty);

        // Track groove
        double railY = ty + 3;
        double railH = 3;
        gc.setFill(Color.web("#0F172A"));
        gc.fillRect(0, railY, spectroWidth, railH);

        // Buffer line (pre-buffering progress)
        if (bufferedRatio > 0.0) {
            double bw = spectroWidth * Math.max(0.0, Math.min(1.0, bufferedRatio));
            gc.setFill(bufferedRatio >= 1.0 ? Color.web("#059669") : Color.web("#0891B2"));
            gc.fillRect(0, railY, bw, railH);

            if (bufferedRatio < 1.0) {
                gc.setFill(Color.web("#38BDF8", 0.8));
                gc.fillRect(Math.max(0, bw - 2), railY - 1, 3, railH + 2);
            }
        }

        // Playback progress line (respects zoom window)
        if (totalDurationMs > 0) {
            double windowFraction = 1.0 / zoomFactor;
            double startFraction = Math.max(0.0, Math.min(1.0 - windowFraction, zoomCenterFraction - (windowFraction / 2.0)));
            double currentFrac = (double) currentPlaybackPositionMs / totalDurationMs;

            if (currentFrac >= startFraction) {
                double progW = Math.min(spectroWidth, ((currentFrac - startFraction) / windowFraction) * spectroWidth);
                gc.setFill(Color.web("#00F0FF"));
                gc.fillRect(0, railY, Math.max(0, progW), railH);
            }
        }

        // Timecode tick marks
        if (totalDurationMs > 0) {
            gc.setFont(Font.font("JetBrains Mono", 8.5));
            gc.setFill(Color.web("#475569"));

            double windowFraction = 1.0 / zoomFactor;
            double startFraction = Math.max(0.0, Math.min(1.0 - windowFraction, zoomCenterFraction - (windowFraction / 2.0)));
            long visibleStartMs = (long) (startFraction * totalDurationMs);
            long visibleEndMs = (long) ((startFraction + windowFraction) * totalDurationMs);
            long visibleSpanMs = visibleEndMs - visibleStartMs;

            long intervalMs = 60000;
            if (visibleSpanMs < 10000) intervalMs = 1000;
            else if (visibleSpanMs < 30000) intervalMs = 5000;
            else if (visibleSpanMs < 60000) intervalMs = 10000;
            else if (visibleSpanMs < 180000) intervalMs = 30000;
            else if (visibleSpanMs > 600000) intervalMs = 120000;

            long firstTick = ((visibleStartMs / intervalMs) + 1) * intervalMs;
            for (long t = firstTick; t < visibleEndMs; t += intervalMs) {
                double tx = ((double) (t - visibleStartMs) / visibleSpanMs) * spectroWidth;
                gc.setStroke(Color.web("#1E293B"));
                gc.strokeLine(tx, ty + 1, tx, ty + 5);
                String label = formatTimeShort(t);
                gc.fillText(label, tx - 10, ty + 15);
            }
        }

        // Buffer & Zoom status badges
        if (totalDurationMs > 0) {
            gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 8.5));
            if (bufferedRatio < 1.0) {
                gc.setFill(Color.web("#38BDF8"));
                gc.fillText("⚡ BUF " + (int) (bufferedRatio * 100) + "%", spectroWidth - 78, ty + 15);
            } else {
                gc.setFill(Color.web("#10B981"));
                gc.fillText("✓ CACHED", spectroWidth - 68, ty + 15);
            }

            if (zoomFactor > 1.02) {
                gc.setFill(Color.web("#F59E0B"));
                gc.fillText(String.format("🔍 %.1fx ZOOM", zoomFactor), spectroWidth - 170, ty + 15);
            }
        }
    }

    private void drawPlayhead() {
        if (totalDurationMs <= 0 || spectroWidth <= 0) return;

        double windowFraction = 1.0 / zoomFactor;
        double startFraction = Math.max(0.0, Math.min(1.0 - windowFraction, zoomCenterFraction - (windowFraction / 2.0)));
        double playheadFraction = (double) currentPlaybackPositionMs / totalDurationMs;

        double px = ((playheadFraction - startFraction) / windowFraction) * spectroWidth;
        if (px < -4 || px > spectroWidth + 4) return;

        double bottomY = spectroHeight + TIMELINE_HEIGHT;

        // Outer halo
        gc.setStroke(Color.color(0.0, 0.94, 1.0, 0.08));
        gc.setLineWidth(8.0);
        gc.strokeLine(px, 0, px, bottomY);

        // Mid glow
        gc.setStroke(Color.color(0.0, 0.94, 1.0, 0.28));
        gc.setLineWidth(3.5);
        gc.strokeLine(px, 0, px, bottomY);

        // Laser core needle
        gc.setStroke(Color.web("#FFFFFF"));
        gc.setLineWidth(1.5);
        gc.strokeLine(px, 0, px, bottomY);

        // Top Needle Head (inverted neon triangle)
        gc.setFill(Color.web("#00F0FF"));
        gc.fillPolygon(
                new double[]{px - 5, px + 5, px},
                new double[]{0, 0, 7},
                3
        );
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(0.8);
        gc.strokePolygon(
                new double[]{px - 5, px + 5, px},
                new double[]{0, 0, 7},
                3
        );

        // Bottom bead on timeline rail
        double railY = spectroHeight + 4.5;
        gc.setFill(Color.web("#00F0FF"));
        gc.fillOval(px - 3.5, railY - 3.5, 7, 7);
        gc.setFill(Color.WHITE);
        gc.fillOval(px - 1.5, railY - 1.5, 3, 3);
    }

    private void drawHoverOverlay() {
        if (!hovering && !isScrubbing) return;
        if (hoverX < 0 || hoverX > spectroWidth || hoverY < 0 || hoverY > spectroHeight || totalDurationMs <= 0) return;

        // 1. Vertical Hairline (X-Axis: Time)
        gc.setStroke(Color.web("#38BDF8", 0.50));
        gc.setLineWidth(1.0);
        gc.setLineDashes(3.0, 3.0);
        gc.strokeLine(hoverX, 0, hoverX, spectroHeight);

        // 2. Horizontal Hairline (Y-Axis: Frequency)
        gc.setStroke(Color.web("#F59E0B", 0.45));
        gc.setLineWidth(1.0);
        gc.strokeLine(0, hoverY, spectroWidth, hoverY);
        gc.setLineDashes(null);

        // 3. Center Crosshair Reticle Ring
        gc.setStroke(Color.web("#38BDF8", 0.90));
        gc.setLineWidth(1.2);
        gc.strokeOval(hoverX - 5, hoverY - 5, 10, 10);
        gc.setFill(Color.web("#FFFFFF"));
        gc.fillOval(hoverX - 1.5, hoverY - 1.5, 3, 3);

        // 4. Calculate Time & Frequency values
        long hoverMs = getTimeAtX(hoverX);
        String timeStr = formatTimeCode(hoverMs);
        double timePct = (totalDurationMs > 0) ? ((double) hoverMs / totalDurationMs) * 100.0 : 0.0;

        double freqRatio = Math.max(0.0, Math.min(1.0, (spectroHeight - hoverY) / spectroHeight));
        int hoverHz = (int) (freqRatio * nyquistHz);
        String freqStr = String.format("%,d Hz (%.1f kHz)", hoverHz, hoverHz / 1000.0);

        // 5. Dual-Axis Floating HUD Badge near cursor
        double badgeW = 164;
        double badgeH = 40;
        double bx = hoverX + 14;
        if (bx + badgeW > spectroWidth - 8) {
            bx = hoverX - badgeW - 14;
        }
        if (bx < 8) bx = 8;

        double by = hoverY - 44;
        if (by < 8) by = hoverY + 14;
        if (by + badgeH > spectroHeight - 4) by = spectroHeight - badgeH - 4;

        // Dark glass background with cyan border
        gc.setFill(Color.web("#050814", 0.95));
        gc.fillRoundRect(bx, by, badgeW, badgeH, 6, 6);
        gc.setStroke(Color.web("#38BDF8", 0.85));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(bx, by, badgeW, badgeH, 6, 6);

        // Row 1: Time (Cyan)
        gc.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 10));
        gc.setFill(Color.web("#38BDF8"));
        gc.fillText(String.format("⏱ %s  [%.1f%%]", timeStr, timePct), bx + 8, by + 16);

        // Row 2: Frequency (Amber)
        gc.setFont(Font.font("JetBrains Mono", FontWeight.SEMI_BOLD, 9.5));
        gc.setFill(Color.web("#FBBF24"));
        gc.fillText(String.format("〰 %s", freqStr), bx + 8, by + 31);
    }

    private void drawHardwareBezel() {
        if (spectroWidth <= 0 || spectroHeight <= 0) return;

        // Subtle studio corner crosshair reticles
        gc.setStroke(Color.web("#334155", 0.70));
        gc.setLineWidth(1.0);

        // Top-left reticle
        gc.strokeLine(3, 3, 9, 3);
        gc.strokeLine(3, 3, 3, 9);

        // Top-right reticle
        gc.strokeLine(spectroWidth - 9, 3, spectroWidth - 3, 3);
        gc.strokeLine(spectroWidth - 3, 3, spectroWidth - 3, 9);
    }

    // =========================================================================
    // Internal Buffer Computation & Utilities
    // =========================================================================

    private void updatePrebufferedColumn(float[] slice, long positionMs) {
        if (spectroWidth <= 0 || spectroHeight <= 0 || totalDurationMs <= 0) return;

        int targetX = (int) (((double) positionMs / totalDurationMs) * spectroWidth);
        targetX = Math.max(0, Math.min(spectroWidth - 1, targetX));

        int numBins = slice.length;
        int h = spectroHeight;

        for (int y = 0; y < h; y++) {
            int binIdx = (int) (((double) (h - 1 - y) / h) * numBins);
            binIdx = Math.max(0, Math.min(binIdx, numBins - 1));
            float mag = slice[binIdx];

            float db = (mag > 1e-5f) ? (float) (20.0 * Math.log10(mag)) : -100.0f;
            float normalized = (db + 80.0f) / 80.0f;
            normalized = Math.max(0.0f, Math.min(1.0f, normalized));
            float intensity = (float) Math.pow(normalized, 1.25);
            int lutIdx = Math.max(0, Math.min(255, (int) (intensity * 255.0f)));
            int argb = COLORMAP_LUT[lutIdx];

            prebufferedPixelData[y * spectroWidth + targetX] = argb;
        }

        prebufferedDirty = true;
    }

    private void rebuildPrebufferedImage() {
        if (spectroWidth <= 0 || spectroHeight <= 0 || prebufferedSlices.isEmpty() || totalDurationMs <= 0) return;

        this.prebufferedPixelData = new int[spectroWidth * spectroHeight];
        Arrays.fill(prebufferedPixelData, 0xFF05070D);

        int numSlices = prebufferedSlices.size();
        int h = spectroHeight;

        for (int x = 0; x < spectroWidth; x++) {
            long targetMs = (long) (((double) x / spectroWidth) * totalDurationMs);
            int sliceIdx = (int) (((double) targetMs / totalDurationMs) * numSlices);
            sliceIdx = Math.max(0, Math.min(numSlices - 1, sliceIdx));

            float[] mags = prebufferedSlices.get(sliceIdx).magnitudes();
            int numBins = mags.length;

            for (int y = 0; y < h; y++) {
                int binIdx = (int) (((double) (h - 1 - y) / h) * numBins);
                binIdx = Math.max(0, Math.min(binIdx, numBins - 1));
                float mag = mags[binIdx];

                float db = (mag > 1e-5f) ? (float) (20.0 * Math.log10(mag)) : -100.0f;
                float normalized = (db + 80.0f) / 80.0f;
                normalized = Math.max(0.0f, Math.min(1.0f, normalized));
                float intensity = (float) Math.pow(normalized, 1.25);
                int lutIdx = Math.max(0, Math.min(255, (int) (intensity * 255.0f)));
                int argb = COLORMAP_LUT[lutIdx];

                prebufferedPixelData[y * spectroWidth + x] = argb;
            }
        }

        prebufferedDirty = true;
    }

    private static final class RulerItem {
        final int freqHz;
        final String chipText;
        final String freqLabel;
        final String hexColor;
        final boolean isBenchmark;
        final boolean isCutoff;

        RulerItem(int freqHz, String chipText, String freqLabel, String hexColor, boolean isBenchmark) {
            this(freqHz, chipText, freqLabel, hexColor, isBenchmark, false);
        }

        RulerItem(int freqHz, String chipText, String freqLabel, String hexColor, boolean isBenchmark, boolean isCutoff) {
            this.freqHz = freqHz;
            this.chipText = chipText;
            this.freqLabel = freqLabel;
            this.hexColor = hexColor;
            this.isBenchmark = isBenchmark;
            this.isCutoff = isCutoff;
        }
    }

    private List<RulerItem> getRulerItems() {
        List<RulerItem> items = new ArrayList<>();
        items.add(new RulerItem(Math.min(22050, nyquistHz), "CD", "22.1k", "#10B981", true));
        items.add(new RulerItem(20500, "320k", "20.5k", "#F59E0B", true));
        items.add(new RulerItem(18500, "192k", "18.5k", "#F97316", true));
        items.add(new RulerItem(16000, "128k", "16.0k", "#EF4444", true));

        // Scale intermediate frequency ticks dynamically based on spectroHeight:
        if (spectroHeight > 380) {
            items.add(new RulerItem(14000, null, "14 kHz", "#94A3B8", false));
        }
        items.add(new RulerItem(10000, null, "10 kHz", "#CBD5E1", false));
        if (spectroHeight > 320) {
            items.add(new RulerItem(7500, null, "7.5 kHz", "#94A3B8", false));
        }
        items.add(new RulerItem(5000, null, "5.0 kHz", "#CBD5E1", false));
        if (spectroHeight > 360) {
            items.add(new RulerItem(2500, null, "2.5 kHz", "#94A3B8", false));
        }
        items.add(new RulerItem(1000, null, "1.0 kHz", "#CBD5E1", false));
        items.add(new RulerItem(500, null, "500 Hz", "#94A3B8", false));
        items.add(new RulerItem(100, null, "100 Hz", "#CBD5E1", false));

        // Integrate detected cutoff cleanly without duplicate/overlapping items!
        if (detectedCutoffHz > 0 && detectedCutoffHz < nyquistHz - 200) {
            boolean merged = false;
            for (int i = 0; i < items.size(); i++) {
                RulerItem it = items.get(i);
                if (Math.abs(it.freqHz - detectedCutoffHz) < 700) {
                    // Merge with nearby benchmark! Replace with prominent CUT badge!
                    items.set(i, new RulerItem(
                            detectedCutoffHz,
                            "CUT",
                            String.format("%.1fk", detectedCutoffHz / 1000.0),
                            "#EF4444",
                            true,
                            true
                    ));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                // Insert standalone CUT item at proper position
                items.add(new RulerItem(
                        detectedCutoffHz,
                        "CUT",
                        String.format("%.1fk", detectedCutoffHz / 1000.0),
                        "#EF4444",
                        true,
                        true
                ));
            }
        }

        items.sort((a, b) -> Integer.compare(b.freqHz, a.freqHz));
        return items;
    }

    private static String formatTimeCode(long ms) {
        if (ms < 0) ms = 0;
        long min = ms / 60000;
        long sec = (ms % 60000) / 1000;
        long sub = (ms % 1000) / 10;
        return String.format("%02d:%02d.%02d", min, sec, sub);
    }

    private static String formatTimeShort(long ms) {
        long min = ms / 60000;
        long sec = (ms % 60000) / 1000;
        return String.format("%d:%02d", min, sec);
    }

    /**
     * Precomputes 256-entry ARGB Colormap Lookup Table.
     * Gradient: Black -> Deep Indigo -> Purple -> Electric Orange -> Hot White
     */
    private static int[] buildColormapLut() {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float t = i / 255.0f;
            int r, g, b;

            if (t < 0.25f) {
                // Black (5, 7, 13) to Deep Indigo (45, 12, 120)
                float sub = t / 0.25f;
                r = (int) (5 + (45 - 5) * sub);
                g = (int) (7 + (12 - 7) * sub);
                b = (int) (13 + (120 - 13) * sub);
            } else if (t < 0.55f) {
                // Deep Indigo (45, 12, 120) to Purple/Violet (155, 35, 195)
                float sub = (t - 0.25f) / 0.30f;
                r = (int) (45 + (155 - 45) * sub);
                g = (int) (12 + (35 - 12) * sub);
                b = (int) (120 + (195 - 120) * sub);
            } else if (t < 0.85f) {
                // Purple/Violet (155, 35, 195) to Electric Orange (255, 120, 0)
                float sub = (t - 0.55f) / 0.30f;
                r = (int) (155 + (255 - 155) * sub);
                g = (int) (35 + (120 - 35) * sub);
                b = (int) (195 * (1.0f - sub));
            } else {
                // Electric Orange (255, 120, 0) to Hot White (255, 255, 255)
                float sub = (t - 0.85f) / 0.15f;
                r = 255;
                g = (int) (120 + (255 - 120) * sub);
                b = (int) (255 * sub);
            }

            lut[i] = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        }
        return lut;
    }
}
