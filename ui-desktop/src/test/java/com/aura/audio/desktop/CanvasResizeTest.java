package com.aura.audio.desktop;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class CanvasResizeTest {

    @BeforeAll
    static void initJavaFX() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException e) {
            // Already started
            latch.countDown();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX failed to initialize");
    }

    @Test
    void testSpectrogramCanvasIsResizableAndDoesNotLoop() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            SpectrogramCanvas canvas = new SpectrogramCanvas(800, 480);
            assertTrue(canvas.isResizable(), "SpectrogramCanvas must be resizable");
            assertEquals(100.0, canvas.minWidth(-1), "minWidth must be a constant, not getWidth()");
            assertEquals(50.0, canvas.minHeight(-1), "minHeight must be a constant, not getHeight()");

            // Simulate layout resizing
            canvas.resize(1000, 600);
            assertEquals(1000.0, canvas.getWidth());
            assertEquals(600.0, canvas.getHeight());

            // Check that minWidth did NOT expand to 1000!
            assertEquals(100.0, canvas.minWidth(-1), "minWidth must stay constant after resize");
            assertEquals(50.0, canvas.minHeight(-1), "minHeight must stay constant after resize");

            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testEqualizerCanvasIsResizableAndDoesNotLoop() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            EqualizerCanvas canvas = new EqualizerCanvas(800, 80);
            assertTrue(canvas.isResizable(), "EqualizerCanvas must be resizable");
            assertEquals(100.0, canvas.minWidth(-1), "minWidth must be constant");
            assertEquals(30.0, canvas.minHeight(-1), "minHeight must be constant");

            canvas.resize(900, 100);
            assertEquals(100.0, canvas.minWidth(-1));
            assertEquals(30.0, canvas.minHeight(-1));

            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testOscilloscopeCanvasIsResizableAndDoesNotLoop() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            OscilloscopeAndBenchmarkCanvas canvas = new OscilloscopeAndBenchmarkCanvas(800, 480);
            assertTrue(canvas.isResizable(), "OscilloscopeAndBenchmarkCanvas must be resizable");
            assertEquals(100.0, canvas.minWidth(-1), "minWidth must be constant");
            assertEquals(50.0, canvas.minHeight(-1), "minHeight must be constant");

            canvas.resize(1100, 700);
            assertEquals(100.0, canvas.minWidth(-1));
            assertEquals(50.0, canvas.minHeight(-1));

            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}

