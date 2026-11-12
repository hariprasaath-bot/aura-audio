package com.aura.audio.server;

import com.aura.audio.decoder.AudioDecoder;
import com.aura.audio.decoder.AudioMetadataExtractor;
import com.aura.audio.dsp.LosslessQualityAnalyzer;
import com.aura.audio.model.AudioMetadata;
import com.aura.audio.model.AudioSampleBuffer;
import com.aura.audio.model.FftFrame;
import com.aura.audio.model.QualityReport;
import com.aura.audio.player.AuraAudioPlayer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded Javalin 6.x REST and WebSocket server for AuraAudio.
 * Serves the modern Web SPA, handles audio uploads & analysis,
 * and streams real-time 60fps FFT frames to browser visualizers.
 */
public final class AuraServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AuraServer.class);

    private final int port;
    private final Javalin app;
    private final AuraAudioPlayer player;
    private final LosslessQualityAnalyzer analyzer;
    private final ObjectMapper objectMapper;
    private final Set<WsContext> wsClients = ConcurrentHashMap.newKeySet();

    private volatile File currentAudioFile;
    private volatile QualityReport currentReport;

    public AuraServer(int port) {
        this.port = port;
        this.player = new AuraAudioPlayer();
        this.analyzer = new LosslessQualityAnalyzer();
        this.objectMapper = new ObjectMapper();

        // Register DSP listener to broadcast FFT visualizer frames over WebSockets
        this.player.addVisualizerListener(this::broadcastVisualizerFrame);
        this.player.addStateListener(state -> broadcastState(state.name(), player.getCurrentPositionMs()));

        this.app = configureJavalin();
    }

    private Javalin configureJavalin() {
        return Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });

            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.anyHost());
            });

            config.http.maxRequestSize = 250 * 1024 * 1024; // 250MB upload ceiling for Hi-Res FLAC
        });
    }

    public void start() {
        registerRestEndpoints();
        registerWebSocket();
        app.start(port);
        logger.info("⚡ AuraAudio Server listening on http://localhost:{}", port);
        logger.info("   WebSocket stream available at ws://localhost:{}/ws/visualizer", port);
    }

    private void registerRestEndpoints() {
        // Upload & Analyze Audio File
        app.post("/api/upload", ctx -> {
            UploadedFile uploaded = ctx.uploadedFile("audio");
            if (uploaded == null) {
                ctx.status(HttpStatus.BAD_REQUEST).result("Missing audio file upload.");
                return;
            }

            String originalName = uploaded.filename();
            String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : ".tmp";
            Path tempPath = Files.createTempFile("aura_track_", ext);
            File targetFile = tempPath.toFile();
            targetFile.deleteOnExit();

            try (InputStream is = uploaded.content()) {
                Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Extract metadata & perform Lossless DSP Verification
            AudioMetadata metadata = AudioMetadataExtractor.extract(targetFile);
            AudioSampleBuffer buffer = AudioDecoder.decodeToBuffer(targetFile);
            QualityReport report = analyzer.analyze(buffer, metadata);

            this.currentAudioFile = targetFile;
            this.currentReport = report;

            // Load into player engine
            player.load(targetFile);

            Map<String, Object> response = new HashMap<>();
            response.put("report", report);
            response.put("metadata", metadata);
            response.put("artworkUri", metadata.getArtworkDataUri());

            ctx.json(response);
        });

        // Trigger manual analysis on currently loaded track
        app.get("/api/analyze/current", ctx -> {
            if (currentReport == null) {
                ctx.status(HttpStatus.NOT_FOUND).result("No audio track currently loaded.");
                return;
            }
            ctx.json(currentReport);
        });

        // Audio Stream with HTTP 206 Partial Content Range Support for Web Audio
        app.get("/api/track/stream", ctx -> {
            if (currentAudioFile == null || !currentAudioFile.exists()) {
                ctx.status(HttpStatus.NOT_FOUND).result("No audio file available for streaming.");
                return;
            }

            long fileLength = currentAudioFile.length();
            String rangeHeader = ctx.header("Range");
            String mimeType = getAudioMimeType(currentAudioFile.getName());

            if (rangeHeader == null) {
                ctx.header("Content-Type", mimeType);
                ctx.header("Content-Length", String.valueOf(fileLength));
                ctx.header("Accept-Ranges", "bytes");
                ctx.result(new FileInputStream(currentAudioFile));
                return;
            }

            // Parse byte range
            long start = 0;
            long end = fileLength - 1;
            String[] ranges = rangeHeader.replace("bytes=", "").split("-");
            try {
                if (!ranges[0].isEmpty()) {
                    start = Long.parseLong(ranges[0]);
                }
                if (ranges.length > 1 && !ranges[1].isEmpty()) {
                    end = Long.parseLong(ranges[1]);
                }
            } catch (NumberFormatException ignored) {}

            if (start > end || start >= fileLength) {
                ctx.status(416);
                ctx.header("Content-Range", "bytes */" + fileLength);
                return;
            }

            end = Math.min(end, fileLength - 1);
            long contentLength = end - start + 1;

            ctx.status(206);
            ctx.header("Content-Type", mimeType);
            ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
            ctx.header("Content-Length", String.valueOf(contentLength));
            ctx.header("Accept-Ranges", "bytes");

            RandomAccessFile raf = new RandomAccessFile(currentAudioFile, "r");
            raf.seek(start);

            ctx.result(new RangeInputStream(raf, contentLength));
        });

        // Remote Player Controls
        app.post("/api/player/play", ctx -> {
            player.play();
            ctx.status(HttpStatus.OK).result("Playing");
        });

        app.post("/api/player/pause", ctx -> {
            player.pause();
            ctx.status(HttpStatus.OK).result("Paused");
        });

        app.post("/api/player/stop", ctx -> {
            player.stop();
            ctx.status(HttpStatus.OK).result("Stopped");
        });

        app.post("/api/player/seek", ctx -> {
            String posParam = ctx.queryParam("pos");
            if (posParam != null) {
                long posMs = Long.parseLong(posParam);
                player.seek(posMs);
            }
            ctx.status(HttpStatus.OK).result("Seeked");
        });

        app.post("/api/player/volume", ctx -> {
            String volParam = ctx.queryParam("level");
            if (volParam != null) {
                float vol = Float.parseFloat(volParam);
                player.setVolume(vol);
            }
            ctx.status(HttpStatus.OK).result("Volume updated");
        });

        app.post("/api/player/loop", ctx -> {
            String loopParam = ctx.queryParam("enabled");
            if (loopParam != null) {
                player.setLoop(Boolean.parseBoolean(loopParam));
            }
            ctx.status(HttpStatus.OK).result("Loop updated");
        });

        app.get("/api/player/state", ctx -> {
            Map<String, Object> map = new HashMap<>();
            map.put("state", player.getState().name());
            map.put("positionMs", player.getCurrentPositionMs());
            map.put("durationMs", player.getDurationMs());
            map.put("volume", player.getVolume());
            map.put("loop", player.isLooping());
            ctx.json(map);
        });
    }

    private void registerWebSocket() {
        app.ws("/ws/visualizer", ws -> {
            ws.onConnect(ctx -> {
                wsClients.add(ctx);
                logger.debug("Visualizer client connected: {}", ctx.session.getRemoteAddress());
                // Send initial state
                Map<String, Object> init = new HashMap<>();
                init.put("type", "init");
                init.put("state", player.getState().name());
                init.put("pos", player.getCurrentPositionMs());
                init.put("dur", player.getDurationMs());
                ctx.send(objectMapper.writeValueAsString(init));
            });

            ws.onMessage(ctx -> {
                try {
                    Map<?, ?> msg = objectMapper.readValue(ctx.message(), Map.class);
                    String action = (String) msg.get("action");
                    if ("play".equalsIgnoreCase(action)) {
                        player.play();
                    } else if ("pause".equalsIgnoreCase(action)) {
                        player.pause();
                    } else if ("stop".equalsIgnoreCase(action)) {
                        player.stop();
                    } else if ("seek".equalsIgnoreCase(action)) {
                        Number pos = (Number) msg.get("pos");
                        if (pos != null) player.seek(pos.longValue());
                    } else if ("volume".equalsIgnoreCase(action)) {
                        Number vol = (Number) msg.get("level");
                        if (vol != null) player.setVolume(vol.floatValue());
                    }
                } catch (Exception e) {
                    logger.warn("Invalid ws client message: {}", e.getMessage());
                }
            });

            ws.onClose(ctx -> wsClients.remove(ctx));
            ws.onError(ctx -> wsClients.remove(ctx));
        });
    }

    private void broadcastVisualizerFrame(FftFrame frame) {
        if (wsClients.isEmpty()) return;

        try {
            // Compress frame into efficient lightweight JSON
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "fft");
            payload.put("ts", frame.timestampMs());
            payload.put("bands", frame.bands64());
            payload.put("peak", frame.waveformPeak());
            payload.put("rms", frame.waveformRms());
            payload.put("pos", frame.positionMs());
            payload.put("dur", frame.durationMs());

            // Sample down magnitudes for web spectrogram (take 128 bins)
            float[] srcMag = frame.magnitudes();
            float[] webMags = new float[128];
            int step = Math.max(1, srcMag.length / 128);
            for (int i = 0; i < 128; i++) {
                webMags[i] = srcMag[i * step];
            }
            payload.put("mags", webMags);

            // Sample down waveform for web oscilloscope (take 128 points)
            float[] srcWave = frame.waveformSamples();
            if (srcWave != null && srcWave.length > 0) {
                float[] webWave = new float[128];
                int waveStep = Math.max(1, srcWave.length / 128);
                for (int i = 0; i < 128; i++) {
                    webWave[i] = srcWave[i * waveStep];
                }
                payload.put("wave", webWave);
            }

            String json = objectMapper.writeValueAsString(payload);
            for (WsContext client : wsClients) {
                if (client.session.isOpen()) {
                    client.send(json);
                }
            }
        } catch (Exception e) {
            logger.warn("WebSocket visualizer broadcast error: {}", e.getMessage());
        }
    }

    private void broadcastState(String state, long positionMs) {
        if (wsClients.isEmpty()) return;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "state");
            payload.put("state", state);
            payload.put("pos", positionMs);
            String json = objectMapper.writeValueAsString(payload);
            for (WsContext client : wsClients) {
                if (client.session.isOpen()) {
                    client.send(json);
                }
            }
        } catch (Exception ignored) {}
    }

    private String getAudioMimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a") || lower.endsWith(".alac") || lower.endsWith(".aac")) return "audio/mp4";
        return "application/octet-stream";
    }

    public AuraAudioPlayer getPlayer() {
        return player;
    }

    public LosslessQualityAnalyzer getAnalyzer() {
        return analyzer;
    }

    @Override
    public void close() {
        if (player != null) player.close();
        if (app != null) app.stop();
    }

    public static void main(String[] args) {
        int port = 7070;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }
        AuraServer server = new AuraServer(port);
        server.start();
    }

    private static class RangeInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;

        public RangeInputStream(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = raf.read();
            if (b != -1) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int read = raf.read(b, off, toRead);
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }
}

