package com.aura.audio.desktop;

import com.aura.audio.decoder.AudioDecoder;
import com.aura.audio.decoder.AudioMetadataExtractor;
import com.aura.audio.dsp.FastFourierTransform;
import com.aura.audio.dsp.HannWindow;
import com.aura.audio.dsp.LosslessQualityAnalyzer;
import com.aura.audio.model.AudioMetadata;
import com.aura.audio.model.AudioSampleBuffer;
import com.aura.audio.model.FftFrame;
import com.aura.audio.model.QualityReport;
import com.aura.audio.model.Verdict;
import com.aura.audio.player.AuraAudioPlayer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AuraAudio Neo-Retro Master Studio Console & DSP Player (JavaFX 21).
 *
 * Architecture Highlights:
 * - Center Stage Hero Analyzer layout commanding maximum horizontal and vertical screen real estate.
 * - Hardware studio rack deck framing for Master Header, Left Transport Deck, and Right Forensic Inspector.
 * - Continuous YouTube / video-editor style pre-buffering engine running in Virtual Threads,
 *   populating the full-track spectrogram cache before/during playback.
 * - Direct interactive seeking inside the Spectrogram Analyzer with smooth bidirectional player synchronization.
 * - Zero gradient buttons in code, strictly adhering to Neo-Retro machined hardware CSS classes.
 */
public final class AuraDesktopApp extends Application {

    private static final Logger logger = LoggerFactory.getLogger(AuraDesktopApp.class);

    private final AuraAudioPlayer player = new AuraAudioPlayer();
    private final LosslessQualityAnalyzer analyzer = new LosslessQualityAnalyzer();

    private SpectrogramCanvas spectrogramCanvas;
    private OscilloscopeAndBenchmarkCanvas oscilloscopeCanvas;
    private EqualizerCanvas equalizerCanvas;
    private TrueQualityInspectorPane inspectorPane;

    private ImageView artworkView;
    private StackPane artworkContainer;
    private Label lblTitle;
    private Label lblArtist;
    private Label lblAlbum;
    private Label lblCodecBadge;

    private Slider scrubberSlider;
    private Label lblElapsed;
    private Label lblDuration;
    private Label lblTimecode;
    private Label lblHeaderStatus;
    private Button btnPlayPause;
    private Button btnLoop;
    private Slider volumeSlider;

    private Button btnModeSpectro;
    private Button btnModeOscillo;
    private Button btnModeBenchmarks;

    private Button btnViewFullTrack;
    private Button btnViewScroll;
    private Button btnViewLive;

    private Button btnToggleInspector;

    private volatile boolean userIsScrubbing = false;
    private final AtomicBoolean uiUpdatePending = new AtomicBoolean(false);
    private final AtomicReference<Thread> currentPrebufferThread = new AtomicReference<>(null);

    private double xOffset = 0;
    private double yOffset = 0;

    @Override
    public void start(Stage primaryStage) {
        // Neo-Retro Master Console Frame: Undecorated with custom chamfered border
        primaryStage.initStyle(StageStyle.UNDECORATED);
        primaryStage.setTitle("AuraAudio • Neo-Retro Master Studio Console & Lossless DSP Analyzer");

        BorderPane root = new BorderPane();
        root.getStyleClass().add("main-window");

        // Top: Sleek Neo-Retro Master Control Header
        HBox customTitleBar = createCustomTitleBar(primaryStage);
        root.setTop(customTitleBar);

        // Center Stage: Dominant Hero Analyzer with Integrated Timeline & Recessed 64-Band Equalizer Rack
        VBox centerStage = createCenterStage();

        // Left Deck: Compact Track Info Card + Tactile Transport Controls
        VBox leftPanel = createLeftPanel(primaryStage);

        // Right Deck: Sleek Collapsible Forensic Inspector Rack
        inspectorPane = new TrueQualityInspectorPane();
        inspectorPane.getStyleClass().addAll("neo-panel", "cyber-glass-card");
        inspectorPane.setPrefWidth(330);
        inspectorPane.setMinWidth(280);
        inspectorPane.setMaxWidth(360);

        inspectorPane.setOnCollapseRequested(() -> {
            boolean visible = !inspectorPane.isVisible();
            inspectorPane.setVisible(visible);
            inspectorPane.setManaged(visible);
            if (visible) {
                btnToggleInspector.getStyleClass().setAll("view-toggle-btn-active", "neo-btn-toggle-active");
            } else {
                btnToggleInspector.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
            }
        });

        HBox mainContent = new HBox(14);
        mainContent.setPadding(new Insets(12, 14, 14, 14));
        HBox.setHgrow(centerStage, Priority.ALWAYS);
        mainContent.getChildren().addAll(leftPanel, centerStage, inspectorPane);

        root.setCenter(mainContent);

        // Register DSP Visualizer & State Listeners
        player.addVisualizerListener(this::handleVisualizerFrame);
        player.addStateListener(state -> Platform.runLater(this::updatePlayPauseButton));

        // Wire Direct Analyzer Seeking to Player & Scrubber Synchronization
        spectrogramCanvas.setOnSeekRequested(ms -> {
            player.seek(ms);
            Platform.runLater(() -> {
                lblElapsed.setText(formatTime(ms));
                long dur = player.getDurationMs();
                if (dur > 0) {
                    scrubberSlider.setValue(((double) ms / dur) * 100.0);
                    lblTimecode.setText(formatTime(ms) + " / " + formatTime(dur));
                }
            });
        });

        // Configure Drag & Drop on Root Pane
        installDragAndDrop(root);

        Scene scene = new Scene(root, 1500, 920);
        scene.getStylesheets().add(getClass().getResource("/aura-dark.css").toExternalForm());

        // Multi-directional window resizer for precision hardware frame
        installWindowResizer(primaryStage, scene);

        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1180);
        primaryStage.setMinHeight(760);
        primaryStage.show();
    }

    /**
     * Top Master Control Header: Brand identity, status badges, master digital timecode counter,
     * track loader, and tactile window controls.
     */
    private HBox createCustomTitleBar(Stage stage) {
        HBox titleBar = new HBox(12);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getStyleClass().add("custom-title-bar");

        // Live Active LED Pulse Indicator
        Circle statusLed = new Circle(4.5);
        statusLed.getStyleClass().add("live-led-pulse");

        // Brand Typography
        Label brand = new Label("AURA AUDIO");
        brand.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #FFFFFF; -fx-letter-spacing: 1px;");

        Label masterDeckBadge = new Label("NEO-RETRO MASTER DECK");
        masterDeckBadge.getStyleClass().add("codec-badge");

        // Center Readouts: Master Monospace Timecode Display & Format Chip
        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);

        HBox centerReadoutBox = new HBox(10);
        centerReadoutBox.setAlignment(Pos.CENTER);

        lblHeaderStatus = new Label("IDLE • AWAITING AUDIO");
        lblHeaderStatus.getStyleClass().add("digital-readout");

        lblTimecode = new Label("0:00 / 0:00");
        lblTimecode.getStyleClass().add("timecode-display");

        centerReadoutBox.getChildren().addAll(lblHeaderStatus, lblTimecode);

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        // Toggle Inspector Button (Collapsible Rack)
        btnToggleInspector = new Button("◫ FORENSICS");
        btnToggleInspector.getStyleClass().addAll("view-toggle-btn-active", "neo-btn-toggle-active");
        btnToggleInspector.setTooltip(new Tooltip("Toggle Forensic Inspector Rack to expand/collapse center analyzer"));
        btnToggleInspector.setOnAction(e -> {
            boolean visible = !inspectorPane.isVisible();
            inspectorPane.setVisible(visible);
            inspectorPane.setManaged(visible);
            if (visible) {
                btnToggleInspector.getStyleClass().setAll("view-toggle-btn-active", "neo-btn-toggle-active");
            } else {
                btnToggleInspector.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
            }
        });

        // Open Track Button (Tactile Hardware Pushbutton)
        Button btnOpen = new Button("📂 OPEN TRACK");
        btnOpen.getStyleClass().addAll("btn-hardware-primary", "neo-btn-primary");
        btnOpen.setOnAction(e -> openFilePicker(stage));

        // Precision Window Controls (Minimize, Maximize, Close)
        Button btnMinimize = new Button("—");
        btnMinimize.getStyleClass().add("win-control-btn");
        btnMinimize.setOnAction(e -> stage.setIconified(true));

        Button btnMaximize = new Button("☐");
        btnMaximize.getStyleClass().add("win-control-btn");
        btnMaximize.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        stage.maximizedProperty().addListener((obs, oldV, isMax) -> btnMaximize.setText(isMax ? "❐" : "☐"));

        Button btnClose = new Button("✕");
        btnClose.getStyleClass().add("win-close-btn");
        btnClose.setOnAction(e -> {
            stopPrebuffering();
            player.close();
            stage.close();
            Platform.exit();
        });

        HBox winControls = new HBox(3);
        winControls.setAlignment(Pos.CENTER_RIGHT);
        winControls.getChildren().addAll(btnMinimize, btnMaximize, btnClose);

        titleBar.getChildren().addAll(
                statusLed, brand, masterDeckBadge,
                leftSpacer, centerReadoutBox, rightSpacer,
                btnToggleInspector, btnOpen, winControls
        );

        // Window Dragging & Double-Click Maximize
        titleBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });

        titleBar.setOnMouseDragged(event -> {
            if (!stage.isMaximized()) {
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            }
        });

        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });

        return titleBar;
    }

    /**
     * Left Deck: Compact, elegant track info card + tactile hardware transport controls.
     * Guaranteed zero inline gradient buttons.
     */
    private VBox createLeftPanel(Stage stage) {
        VBox left = new VBox(12);
        left.setPrefWidth(290);
        left.setMinWidth(270);
        left.setMaxWidth(310);

        // Track Info Card Deck
        VBox card = new VBox(12);
        card.setPadding(new Insets(14));
        card.getStyleClass().addAll("neo-panel", "cyber-glass-card");

        HBox deckHeader = new HBox(6);
        deckHeader.setAlignment(Pos.CENTER_LEFT);
        Label deckTitle = new Label("TRACK DECK");
        deckTitle.getStyleClass().add("section-header");
        deckHeader.getChildren().add(deckTitle);

        HBox topInfo = new HBox(12);
        topInfo.setAlignment(Pos.CENTER_LEFT);

        // Artwork Thumbnail with Recessed Hardware Bezel
        artworkContainer = new StackPane();
        artworkContainer.setPrefSize(76, 76);
        artworkContainer.getStyleClass().addAll("recessed-bezel", "deep-black-well");

        artworkView = new ImageView();
        artworkView.setFitWidth(76);
        artworkView.setFitHeight(76);
        artworkView.setPreserveRatio(true);
        Rectangle clip = new Rectangle(76, 76);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        artworkView.setClip(clip);

        Label artPlaceholder = new Label("♫");
        artPlaceholder.setStyle("-fx-font-size: 24px; -fx-text-fill: #334155;");
        artworkContainer.getChildren().addAll(artPlaceholder, artworkView);

        VBox textInfo = new VBox(3);
        textInfo.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textInfo, Priority.ALWAYS);

        lblCodecBadge = new Label("STANDBY");
        lblCodecBadge.getStyleClass().add("codec-badge");

        lblTitle = new Label("No Track Loaded");
        lblTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #FFFFFF;");
        lblArtist = new Label("Select or drop audio");
        lblArtist.setStyle("-fx-font-size: 11px; -fx-text-fill: #94A3B8;");
        lblAlbum = new Label("AuraAudio Lossless DSP");
        lblAlbum.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748B;");

        textInfo.getChildren().addAll(lblCodecBadge, lblTitle, lblArtist, lblAlbum);
        topInfo.getChildren().addAll(artworkContainer, textInfo);

        // Accurate Scrubber Slider with Direct Click & Live Drag Seeking
        VBox scrubberBox = new VBox(4);
        scrubberSlider = new Slider(0, 100, 0);

        scrubberSlider.setOnMousePressed(e -> userIsScrubbing = true);

        // Seek on drag release
        scrubberSlider.setOnMouseReleased(e -> {
            userIsScrubbing = false;
            long duration = player.getDurationMs();
            if (duration > 0) {
                double pct = scrubberSlider.getValue() / 100.0;
                long targetMs = (long) (pct * duration);
                player.seek(targetMs);
                spectrogramCanvas.setPlaybackPosition(targetMs, duration);
                lblElapsed.setText(formatTime(targetMs));
                lblTimecode.setText(formatTime(targetMs) + " / " + formatTime(duration));
            }
        });

        // Direct click-to-seek on scrubber track
        scrubberSlider.setOnMouseClicked(e -> {
            long duration = player.getDurationMs();
            if (duration > 0 && scrubberSlider.getWidth() > 0) {
                double ratio = Math.max(0.0, Math.min(1.0, e.getX() / scrubberSlider.getWidth()));
                scrubberSlider.setValue(ratio * 100.0);
                long targetMs = (long) (ratio * duration);
                player.seek(targetMs);
                spectrogramCanvas.setPlaybackPosition(targetMs, duration);
                lblElapsed.setText(formatTime(targetMs));
                lblTimecode.setText(formatTime(targetMs) + " / " + formatTime(duration));
            }
        });

        // Live scrubbing timestamp update during dragging
        scrubberSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (userIsScrubbing && player.getDurationMs() > 0) {
                long currentScrubMs = (long) ((newV.doubleValue() / 100.0) * player.getDurationMs());
                spectrogramCanvas.setPlaybackPosition(currentScrubMs, player.getDurationMs());
                lblElapsed.setText(formatTime(currentScrubMs));
                lblTimecode.setText(formatTime(currentScrubMs) + " / " + formatTime(player.getDurationMs()));
            }
        });

        HBox timeBox = new HBox();
        lblElapsed = new Label("0:00");
        lblElapsed.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 11px; -fx-text-fill: #38BDF8; -fx-font-weight: 700;");
        Region timeSpacer = new Region();
        HBox.setHgrow(timeSpacer, Priority.ALWAYS);
        lblDuration = new Label("0:00");
        lblDuration.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 11px; -fx-text-fill: #64748B;");
        timeBox.getChildren().addAll(lblElapsed, timeSpacer, lblDuration);
        scrubberBox.getChildren().addAll(scrubberSlider, timeBox);

        // Tactile Transport Controls (Solid Neo-Retro Buttons)
        HBox transport = new HBox(12);
        transport.setAlignment(Pos.CENTER);

        btnLoop = new Button("🔁");
        btnLoop.getStyleClass().addAll("icon-btn", "neo-btn-icon");
        btnLoop.setPrefSize(40, 40);
        btnLoop.setTooltip(new Tooltip("Loop Playback"));
        btnLoop.setOnAction(e -> {
            boolean loop = !player.isLooping();
            player.setLoop(loop);
            if (loop) {
                btnLoop.getStyleClass().add("neo-btn-toggle-active");
            } else {
                btnLoop.getStyleClass().remove("neo-btn-toggle-active");
            }
        });

        btnPlayPause = new Button("▶");
        btnPlayPause.getStyleClass().addAll("play-btn", "neo-btn-primary");
        btnPlayPause.setPrefSize(50, 50);
        btnPlayPause.setTooltip(new Tooltip("Play / Pause"));
        btnPlayPause.setOnAction(e -> togglePlayPause());

        Button btnStop = new Button("⏹");
        btnStop.getStyleClass().addAll("icon-btn", "neo-btn-icon");
        btnStop.setPrefSize(40, 40);
        btnStop.setTooltip(new Tooltip("Stop Playback & Reset Scopes"));
        btnStop.setOnAction(e -> {
            stopPrebuffering();
            player.stop();
            spectrogramCanvas.clear();
            oscilloscopeCanvas.clear();
            equalizerCanvas.clear();
            lblElapsed.setText("0:00");
            lblTimecode.setText("0:00 / " + (player.getDurationMs() > 0 ? formatTime(player.getDurationMs()) : "0:00"));
            scrubberSlider.setValue(0);
        });

        transport.getChildren().addAll(btnLoop, btnPlayPause, btnStop);

        // Variable Playback Speed Selector (Slow-Mo to Accelerated)
        HBox speedBox = new HBox(4);
        speedBox.setAlignment(Pos.CENTER);
        Label lblSpeedTag = new Label("SPEED:");
        lblSpeedTag.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9.5px; -fx-font-weight: 700; -fx-text-fill: #64748B;");

        Button btnSpd05 = new Button("0.5x");
        Button btnSpd075 = new Button("0.75x");
        Button btnSpd10 = new Button("1.0x");
        Button btnSpd125 = new Button("1.25x");
        Button btnSpd15 = new Button("1.5x");

        btnSpd05.setTooltip(new Tooltip("Slow-Motion Forensic Playback (0.5x)"));
        btnSpd075.setTooltip(new Tooltip("Slow Playback (0.75x)"));
        btnSpd10.setTooltip(new Tooltip("Standard Playback Speed (1.0x)"));
        btnSpd125.setTooltip(new Tooltip("Accelerated Playback (1.25x)"));
        btnSpd15.setTooltip(new Tooltip("Fast Playback (1.5x)"));

        List<Button> spdBtns = List.of(btnSpd05, btnSpd075, btnSpd10, btnSpd125, btnSpd15);
        for (Button b : spdBtns) {
            b.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
            b.setStyle("-fx-font-size: 9.5px; -fx-padding: 3px 6px;");
        }
        btnSpd10.getStyleClass().addAll("view-toggle-btn-active", "neo-btn-toggle-active");

        setupSpeedButton(btnSpd05, 0.5f, spdBtns);
        setupSpeedButton(btnSpd075, 0.75f, spdBtns);
        setupSpeedButton(btnSpd10, 1.0f, spdBtns);
        setupSpeedButton(btnSpd125, 1.25f, spdBtns);
        setupSpeedButton(btnSpd15, 1.5f, spdBtns);

        speedBox.getChildren().addAll(lblSpeedTag, btnSpd05, btnSpd075, btnSpd10, btnSpd125, btnSpd15);

        // Volume Deck
        HBox volBox = new HBox(8);
        volBox.setAlignment(Pos.CENTER_LEFT);
        Label volIcon = new Label("🔊");
        volIcon.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8;");
        volumeSlider = new Slider(0, 1.0, 0.85);
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> player.setVolume(newV.floatValue()));
        HBox.setHgrow(volumeSlider, Priority.ALWAYS);
        volBox.getChildren().addAll(volIcon, volumeSlider);

        card.getChildren().addAll(deckHeader, topInfo, scrubberBox, transport, speedBox, volBox);

        // Drag & Drop Deck Card
        VBox dropCard = new VBox(6);
        dropCard.setAlignment(Pos.CENTER);
        dropCard.setPadding(new Insets(18));
        dropCard.getStyleClass().addAll("drop-zone", "neo-panel");
        Label dropIcon = new Label("⬆");
        dropIcon.setStyle("-fx-font-size: 20px; -fx-text-fill: #38BDF8;");
        Label dropText = new Label("DRAG & DROP AUDIO");
        dropText.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #E2E8F0;");
        Label dropSub = new Label("FLAC • WAV • ALAC • AIFF • MP3");
        dropSub.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9px; -fx-text-fill: #64748B;");
        dropCard.getChildren().addAll(dropIcon, dropText, dropSub);
        dropCard.setOnMouseClicked(e -> openFilePicker(stage));

        left.getChildren().addAll(card, dropCard);
        return left;
    }

    /**
     * Center Stage Hero Canvas with Interactive Analyzer & Recessed 64-Band Equalizer Rack.
     * Takes maximum horizontal and vertical real estate.
     */
    private VBox createCenterStage() {
        VBox center = new VBox(12);

        // Top Main Visualizer Card with Switchable Modes and Sub-Views
        VBox mainVisCard = new VBox(8);
        mainVisCard.setPadding(new Insets(12, 14, 12, 14));
        mainVisCard.getStyleClass().addAll("master-deck-container", "cyber-glass-card");
        VBox.setVgrow(mainVisCard, Priority.ALWAYS);

        // Visualizer Mode Switcher Header
        HBox visHeader = new HBox(8);
        visHeader.setAlignment(Pos.CENTER_LEFT);

        Circle scopePulseDot = new Circle(4, Color.web("#06B6D4"));
        scopePulseDot.getStyleClass().add("live-led-pulse");

        Label visTitle = new Label("HERO ACOUSTIC ANALYZER");
        visTitle.getStyleClass().add("card-title");
        visTitle.setStyle("-fx-font-size: 13px; -fx-letter-spacing: 0.5px;");

        Region spSpacer = new Region();
        HBox.setHgrow(spSpacer, Priority.ALWAYS);

        // Visualizer Zoom Controls (Track View Time Zoom)
        HBox zoomBox = new HBox(3);
        zoomBox.setAlignment(Pos.CENTER_RIGHT);

        Label lblZoomTag = new Label("ZOOM:");
        lblZoomTag.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9.5px; -fx-font-weight: 700; -fx-text-fill: #64748B;");

        Button btnZoomOut = new Button("－");
        btnZoomOut.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
        btnZoomOut.setStyle("-fx-font-size: 9.5px; -fx-padding: 2px 7px;");
        btnZoomOut.setTooltip(new Tooltip("Zoom Out (Or Scroll Down)"));

        Label lblZoomVal = new Label("1.0x");
        lblZoomVal.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9.5px; -fx-font-weight: 700; -fx-text-fill: #38BDF8; -fx-padding: 0 4px;");

        Button btnZoomIn = new Button("＋");
        btnZoomIn.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
        btnZoomIn.setStyle("-fx-font-size: 9.5px; -fx-padding: 2px 7px;");
        btnZoomIn.setTooltip(new Tooltip("Zoom In (Or Scroll Up)"));

        Button btnZoomReset = new Button("RESET");
        btnZoomReset.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
        btnZoomReset.setStyle("-fx-font-size: 8.5px; -fx-padding: 2px 5px;");
        btnZoomReset.setTooltip(new Tooltip("Reset Zoom"));

        btnZoomIn.setOnAction(e -> {
            spectrogramCanvas.zoomIn();
            lblZoomVal.setText(String.format("%.1fx", spectrogramCanvas.getZoomFactor()));
        });
        btnZoomOut.setOnAction(e -> {
            spectrogramCanvas.zoomOut();
            lblZoomVal.setText(String.format("%.1fx", spectrogramCanvas.getZoomFactor()));
        });
        btnZoomReset.setOnAction(e -> {
            spectrogramCanvas.resetZoom();
            lblZoomVal.setText("1.0x");
        });

        zoomBox.getChildren().addAll(lblZoomTag, btnZoomOut, lblZoomVal, btnZoomIn, btnZoomReset);

        // Spectrogram View Projection Mode Sub-Toggles (Full Track vs Windowed vs Live Waterfall)
        HBox subViewToggleBox = new HBox(4);
        subViewToggleBox.setAlignment(Pos.CENTER_RIGHT);

        btnViewFullTrack = new Button("⛶ Track View");
        btnViewFullTrack.getStyleClass().addAll("view-toggle-btn-active", "neo-btn-toggle-active");
        btnViewFullTrack.setTooltip(new Tooltip("Full Track Pre-Buffered Overview"));

        btnViewScroll = new Button("⏩ Windowed");
        btnViewScroll.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
        btnViewScroll.setTooltip(new Tooltip("Scrolling 20-second Window Centered on Playhead"));

        btnViewLive = new Button("🌊 Live Waterfall");
        btnViewLive.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
        btnViewLive.setTooltip(new Tooltip("Real-Time Waterfall Stream"));

        btnViewFullTrack.setOnAction(e -> {
            spectrogramCanvas.setViewMode(SpectrogramCanvas.ViewMode.FULL_TRACK);
            setActiveSubViewButton(btnViewFullTrack);
        });

        btnViewScroll.setOnAction(e -> {
            spectrogramCanvas.setViewMode(SpectrogramCanvas.ViewMode.SCROLLING_WINDOW);
            setActiveSubViewButton(btnViewScroll);
        });

        btnViewLive.setOnAction(e -> {
            spectrogramCanvas.setViewMode(SpectrogramCanvas.ViewMode.LIVE_STREAM);
            setActiveSubViewButton(btnViewLive);
        });

        subViewToggleBox.getChildren().addAll(btnViewFullTrack, btnViewScroll, btnViewLive);

        Region modeDivider = new Region();
        modeDivider.setPrefWidth(12);

        // Three Main Visualizer Modes
        btnModeSpectro = new Button("🌌 Spectrogram");
        btnModeSpectro.getStyleClass().addAll("view-toggle-btn-active", "neo-btn-toggle-active");

        btnModeOscillo = new Button("⚡ Oscilloscope");
        btnModeOscillo.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");

        btnModeBenchmarks = new Button("📊 Benchmarks");
        btnModeBenchmarks.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");

        visHeader.getChildren().addAll(
                scopePulseDot, visTitle, spSpacer,
                zoomBox, subViewToggleBox, modeDivider,
                btnModeSpectro, btnModeOscillo, btnModeBenchmarks
        );

        // Center Stage Hero Canvas Container with Multi-Layer Glow Leaks
        StackPane canvasContainer = new StackPane();
        canvasContainer.getStyleClass().addAll("analyzer-frame", "spectral-glow-leak", "crt-glow-container");
        VBox.setVgrow(canvasContainer, Priority.ALWAYS);
        canvasContainer.setPrefHeight(480);
        canvasContainer.setMinHeight(260);

        spectrogramCanvas = new SpectrogramCanvas(800, 480);
        spectrogramCanvas.setOnZoomChanged(zoom -> {
            Platform.runLater(() -> lblZoomVal.setText(String.format("%.1fx", zoom)));
        });
        oscilloscopeCanvas = new OscilloscopeAndBenchmarkCanvas(800, 480);

        oscilloscopeCanvas.setVisible(false);
        spectrogramCanvas.setVisible(true);

        canvasContainer.getChildren().addAll(spectrogramCanvas, oscilloscopeCanvas);

        // Wire View Mode Switchers
        btnModeSpectro.setOnAction(e -> {
            setActiveModeButton(btnModeSpectro);
            subViewToggleBox.setVisible(true);
            subViewToggleBox.setManaged(true);
            spectrogramCanvas.setVisible(true);
            oscilloscopeCanvas.setVisible(false);
        });

        btnModeOscillo.setOnAction(e -> {
            setActiveModeButton(btnModeOscillo);
            subViewToggleBox.setVisible(false);
            subViewToggleBox.setManaged(false);
            oscilloscopeCanvas.setDisplayMode(OscilloscopeAndBenchmarkCanvas.DisplayMode.OSCILLOSCOPE);
            spectrogramCanvas.setVisible(false);
            oscilloscopeCanvas.setVisible(true);
        });

        btnModeBenchmarks.setOnAction(e -> {
            setActiveModeButton(btnModeBenchmarks);
            subViewToggleBox.setVisible(false);
            subViewToggleBox.setManaged(false);
            oscilloscopeCanvas.setDisplayMode(OscilloscopeAndBenchmarkCanvas.DisplayMode.QUALITY_BENCHMARKS);
            spectrogramCanvas.setVisible(false);
            oscilloscopeCanvas.setVisible(true);
        });

        mainVisCard.getChildren().addAll(visHeader, canvasContainer);

        // Bottom Deck: Recessed Deep-Black 64-Band LED Equalizer Rack Unit
        VBox eqRack = new VBox(6);
        eqRack.setPadding(new Insets(10, 14, 10, 14));
        eqRack.getStyleClass().addAll("neo-panel", "eq-canvas-container", "cyber-glass-card");
        eqRack.setPrefHeight(125);
        eqRack.setMinHeight(105);
        eqRack.setMaxHeight(140);

        HBox eqHeader = new HBox(8);
        eqHeader.setAlignment(Pos.CENTER_LEFT);

        Circle eqDot = new Circle(3.5, Color.web("#10B981"));
        Label eqTitle = new Label("64-BAND LOGARITHMIC LED EQUALIZER RACK");
        eqTitle.getStyleClass().add("section-header");

        Region eqSpacer = new Region();
        HBox.setHgrow(eqSpacer, Priority.ALWAYS);

        Label eqRange = new Label("20 Hz ───► 22.05 kHz • TRI-TIER BALANCED");
        eqRange.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10px; -fx-text-fill: #64748B;");

        eqHeader.getChildren().addAll(eqDot, eqTitle, eqSpacer, eqRange);

        StackPane eqWell = new StackPane();
        eqWell.getStyleClass().addAll("recessed-bezel", "deep-black-well");
        VBox.setVgrow(eqWell, Priority.ALWAYS);

        equalizerCanvas = new EqualizerCanvas(800, 80);
        eqWell.getChildren().add(equalizerCanvas);

        eqRack.getChildren().addAll(eqHeader, eqWell);

        center.getChildren().addAll(mainVisCard, eqRack);
        return center;
    }

    private void setActiveModeButton(Button active) {
        btnModeSpectro.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
        btnModeOscillo.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
        btnModeBenchmarks.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
        active.getStyleClass().setAll("view-toggle-btn-active", "neo-btn-toggle-active");
    }

    private void setActiveSubViewButton(Button active) {
        btnViewFullTrack.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
        btnViewScroll.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
        btnViewLive.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
        active.getStyleClass().setAll("view-toggle-btn-active", "neo-btn-toggle-active");
    }

    private void setupSpeedButton(Button btn, float speed, List<Button> allSpeedButtons) {
        btn.setOnAction(e -> {
            player.setPlaybackSpeed(speed);
            for (Button b : allSpeedButtons) {
                b.getStyleClass().setAll("view-toggle-btn", "neo-btn-toggle");
            }
            btn.getStyleClass().setAll("view-toggle-btn-active", "neo-btn-toggle-active");
        });
    }

    private void handleVisualizerFrame(FftFrame frame) {
        spectrogramCanvas.pushSlice(frame.magnitudes(), frame.positionMs(), frame.durationMs());
        oscilloscopeCanvas.pushFrame(frame.waveformSamples(), frame.magnitudes(), frame.waveformPeak(), frame.waveformRms());
        equalizerCanvas.updateBands(frame.bands64());

        if (uiUpdatePending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                try {
                    if (!userIsScrubbing && frame.durationMs() > 0) {
                        double pct = ((double) frame.positionMs() / frame.durationMs()) * 100.0;
                        scrubberSlider.setValue(pct);
                        lblElapsed.setText(formatTime(frame.positionMs()));
                        lblDuration.setText(formatTime(frame.durationMs()));
                        lblTimecode.setText(formatTime(frame.positionMs()) + " / " + formatTime(frame.durationMs()));
                    }
                } finally {
                    uiUpdatePending.set(false);
                }
            });
        }
    }

    private void togglePlayPause() {
        if (player.getState() == AuraAudioPlayer.State.PLAYING) {
            player.pause();
        } else {
            player.play();
        }
        updatePlayPauseButton();
    }

    private void updatePlayPauseButton() {
        if (player.getState() == AuraAudioPlayer.State.PLAYING) {
            btnPlayPause.setText("⏸");
        } else {
            btnPlayPause.setText("▶");
        }
    }

    private void openFilePicker(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Audio File for True Lossless Analysis");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Lossless & Hi-Res Audio", "*.flac", "*.wav", "*.aiff", "*.alac"),
                new FileChooser.ExtensionFilter("All Supported Audio", "*.flac", "*.wav", "*.mp3", "*.alac", "*.m4a", "*.aiff")
        );
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            processAudioFile(file);
        }
    }

    private void installDragAndDrop(Pane root) {
        root.setOnDragOver(event -> {
            if (event.getGestureSource() != root && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        root.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                File file = db.getFiles().get(0);
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".mp3")
                        || name.endsWith(".m4a") || name.endsWith(".alac") || name.endsWith(".aiff")) {
                    processAudioFile(file);
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void stopPrebuffering() {
        Thread existing = currentPrebufferThread.getAndSet(null);
        if (existing != null && existing.isAlive()) {
            existing.interrupt();
        }
    }

    /**
     * Executes decoding, metadata extraction, and lossless DSP verification in a Virtual Thread.
     * Launches a continuous YouTube/video-editor style pre-buffering engine that calculates
     * FFT frames across the entire audio track and progressively fills the visualizer buffer.
     */
    private void processAudioFile(File file) {
        stopPrebuffering();

        lblTitle.setText("Analyzing Audio DSP...");
        lblArtist.setText(file.getName());
        lblCodecBadge.setText("COMPUTING FFT FRAMES...");
        lblHeaderStatus.setText("LOADING • PRE-BUFFERING");

        Thread.ofVirtual().name("audio-dsp-analyzer").start(() -> {
            try {
                AudioMetadata meta = AudioMetadataExtractor.extract(file);
                AudioSampleBuffer buffer = AudioDecoder.decodeToBuffer(file);
                QualityReport report = analyzer.analyze(buffer, meta);

                player.load(file);

                Platform.runLater(() -> {
                    // Update Now Playing UI
                    lblTitle.setText(meta.title());
                    lblArtist.setText(meta.artist());
                    lblAlbum.setText(meta.album());
                    lblCodecBadge.setText(meta.getCodecBadge());
                    lblDuration.setText(meta.getFormattedDuration());
                    lblHeaderStatus.setText(meta.containerFormat().toUpperCase() + " • " + (meta.bitDepth() > 0 ? meta.bitDepth() + "b" : "16b") + " • " + (meta.sampleRate() / 1000) + "kHz");
                    scrubberSlider.setValue(0);
                    lblTimecode.setText("0:00 / " + meta.getFormattedDuration());

                    // Update Artwork
                    if (meta.artworkData() != null && meta.artworkData().length > 0) {
                        Image img = new Image(new ByteArrayInputStream(meta.artworkData()));
                        artworkView.setImage(img);
                    } else {
                        artworkView.setImage(null);
                    }

                    // Reset and update Cutoff Marker on Spectrogram Canvas
                    spectrogramCanvas.clear();
                    if (report.verdict() == Verdict.SUSPECTED_UPSCALED_LOSSY) {
                        spectrogramCanvas.setCutoffMarker(
                                report.cutoffFrequencyHz(),
                                report.nyquistFrequencyHz(),
                                String.format("CUTOFF: %.1f kHz (%s)", report.cutoffFrequencyHz() / 1000.0, report.estimatedOriginalBitrate())
                        );
                    } else {
                        spectrogramCanvas.setCutoffMarker(-1, report.nyquistFrequencyHz(), "");
                    }

                    // Update Oscilloscope & Benchmark Canvas
                    oscilloscopeCanvas.setQualityReport(report);

                    // Update Inspector Dashboard
                    inspectorPane.updateReport(report);

                    // Start playback
                    player.play();
                });

                // Launch Continuous Full-Track Pre-Buffering Virtual Thread
                Thread prebuffer = Thread.ofVirtual().name("spectro-prebuffer-engine").start(() -> {
                    try {
                        long totalFrames = buffer.totalFrames();
                        int sampleRate = buffer.sampleRate();
                        long totalDurationMs = buffer.getDurationMs();
                        if (totalFrames <= 0 || sampleRate <= 0) return;

                        int fftSize = 2048;
                        FastFourierTransform fft = new FastFourierTransform(fftSize, sampleRate);
                        float[] pcmWindow = new float[fftSize];
                        float[] magnitudes = new float[fft.getNumPositiveBins()];

                        // Choose fine-grained hop size so whole track has between 1000 and 2500 slices
                        int targetSlices = (int) Math.max(800, Math.min(2500, totalDurationMs / 30));
                        long hopFrames = Math.max(512, totalFrames / targetSlices);

                        long frameIndex = 0;
                        int sliceCount = 0;

                        while (frameIndex + fftSize <= totalFrames && !Thread.currentThread().isInterrupted()) {
                            buffer.getWindow((int) frameIndex, pcmWindow);
                            fft.computeMagnitudes(pcmWindow, magnitudes);

                            float[] sliceCopy = new float[magnitudes.length];
                            System.arraycopy(magnitudes, 0, sliceCopy, 0, magnitudes.length);

                            long posMs = (long) (((double) frameIndex / sampleRate) * 1000.0);
                            spectrogramCanvas.appendPrebufferedSlice(sliceCopy, posMs, totalDurationMs);

                            frameIndex += hopFrames;
                            sliceCount++;

                            // Slight micro-yield every 40 slices to keep UI animation silky
                            if (sliceCount % 40 == 0) {
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }

                        if (!Thread.currentThread().isInterrupted()) {
                            spectrogramCanvas.setBufferedRatio(1.0);
                            Platform.runLater(() -> {
                                if (lblHeaderStatus != null) {
                                    lblHeaderStatus.setText("READY • CACHED 100%");
                                }
                            });
                        }
                    } catch (Exception ex) {
                        logger.warn("Prebuffering interrupted or failed: {}", ex.getMessage());
                    }
                });

                currentPrebufferThread.set(prebuffer);

            } catch (Exception e) {
                logger.error("Failed to process audio file {}: {}", file.getName(), e.getMessage(), e);
                Platform.runLater(() -> {
                    lblTitle.setText("Analysis Error");
                    lblArtist.setText(e.getMessage());
                    lblHeaderStatus.setText("ERROR");
                });
            }
        });
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format("%d:%02d", min, sec);
    }

    /**
     * Enables multi-directional resizing from all 4 borders and 4 corners on undecorated stages.
     */
    private static void installWindowResizer(Stage stage, Scene scene) {
        final int BORDER = 8;
        scene.setOnMouseMoved(e -> {
            if (stage.isMaximized()) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            double x = e.getSceneX();
            double y = e.getSceneY();
            double w = stage.getWidth();
            double h = stage.getHeight();

            boolean left = x < BORDER;
            boolean right = x > w - BORDER;
            boolean top = y < BORDER;
            boolean bottom = y > h - BORDER;

            if (top && left) scene.setCursor(Cursor.NW_RESIZE);
            else if (top && right) scene.setCursor(Cursor.NE_RESIZE);
            else if (bottom && left) scene.setCursor(Cursor.SW_RESIZE);
            else if (bottom && right) scene.setCursor(Cursor.SE_RESIZE);
            else if (left) scene.setCursor(Cursor.W_RESIZE);
            else if (right) scene.setCursor(Cursor.E_RESIZE);
            else if (top) scene.setCursor(Cursor.N_RESIZE);
            else if (bottom) scene.setCursor(Cursor.S_RESIZE);
            else scene.setCursor(Cursor.DEFAULT);
        });

        final double[] dragStart = new double[4];
        final boolean[] resizing = new boolean[1];
        final Cursor[] activeCursor = new Cursor[1];

        scene.setOnMousePressed(e -> {
            if (stage.isMaximized()) return;
            Cursor c = scene.getCursor();
            if (c != Cursor.DEFAULT) {
                resizing[0] = true;
                activeCursor[0] = c;
                dragStart[0] = e.getScreenX();
                dragStart[1] = e.getScreenY();
                dragStart[2] = stage.getWidth();
                dragStart[3] = stage.getHeight();
            }
        });

        scene.setOnMouseDragged(e -> {
            if (!resizing[0] || stage.isMaximized()) return;
            double dx = e.getScreenX() - dragStart[0];
            double dy = e.getScreenY() - dragStart[1];
            Cursor c = activeCursor[0];

            if (c == Cursor.E_RESIZE || c == Cursor.NE_RESIZE || c == Cursor.SE_RESIZE) {
                stage.setWidth(Math.max(stage.getMinWidth(), dragStart[2] + dx));
            }
            if (c == Cursor.S_RESIZE || c == Cursor.SW_RESIZE || c == Cursor.SE_RESIZE) {
                stage.setHeight(Math.max(stage.getMinHeight(), dragStart[3] + dy));
            }
            if (c == Cursor.W_RESIZE || c == Cursor.NW_RESIZE || c == Cursor.SW_RESIZE) {
                double newW = Math.max(stage.getMinWidth(), dragStart[2] - dx);
                if (newW > stage.getMinWidth()) {
                    stage.setX(stage.getX() + dx);
                    stage.setWidth(newW);
                    dragStart[0] = e.getScreenX();
                    dragStart[2] = newW;
                }
            }
            if (c == Cursor.N_RESIZE || c == Cursor.NW_RESIZE || c == Cursor.NE_RESIZE) {
                double newH = Math.max(stage.getMinHeight(), dragStart[3] - dy);
                if (newH > stage.getMinHeight()) {
                    stage.setY(stage.getY() + dy);
                    stage.setHeight(newH);
                    dragStart[1] = e.getScreenY();
                    dragStart[3] = newH;
                }
            }
        });

        scene.setOnMouseReleased(e -> resizing[0] = false);
    }

    @Override
    public void stop() {
        stopPrebuffering();
        player.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
