package com.aura.audio.desktop;

import com.aura.audio.model.AudioMetadata;
import com.aura.audio.model.QualityReport;
import com.aura.audio.model.Verdict;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop UI component: The True Quality Inspector Dashboard.
 * Displays forensic lossy-upscale detection verdicts, cutoff frequency, dynamic range metrics,
 * and an icon-based audit trail with zero scrollbars and a full forensic details dialog.
 */
public final class TrueQualityInspectorPane extends VBox {

    private final HBox verdictBanner;
    private final Region verdictDot;
    private final Label verdictLabel;
    private final Label verdictSubLabel;

    private final Label lblCutoff;
    private final Label lblCutoffSub;
    private final Label lblBitrate;
    private final Label lblBitrateSub;
    private final Label lblDR;
    private final Label lblDRSub;
    private final Label lblContainer;
    private final Label lblContainerSub;

    private final VBox auditCardsContainer;
    private final Button btnViewDetails;
    private final Button btnCollapse;

    private QualityReport activeReport;
    private Runnable onCollapseRequested;

    public TrueQualityInspectorPane() {
        setSpacing(14);
        setPadding(new Insets(16));
        getStyleClass().add("cyber-glass-card");

        // Header with Collapse Button
        HBox headerBox = new HBox(8);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        VBox titleCol = new VBox(2);
        Label title = new Label("True Quality Inspector");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 14px;");

        Label subtitle = new Label("Forensic Lossless Verification");
        subtitle.getStyleClass().add("card-subtitle");
        subtitle.setStyle("-fx-font-size: 10px;");
        titleCol.getChildren().addAll(title, subtitle);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        btnCollapse = new Button("◫ Hide");
        btnCollapse.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
        btnCollapse.setStyle("-fx-font-size: 10px; -fx-padding: 3px 8px;");
        btnCollapse.setOnAction(e -> {
            if (onCollapseRequested != null) {
                onCollapseRequested.run();
            }
        });

        headerBox.getChildren().addAll(titleCol, headerSpacer, btnCollapse);

        // Verdict Status Banner
        verdictBanner = new HBox(8);
        verdictBanner.setAlignment(Pos.CENTER_LEFT);
        verdictBanner.setPadding(new Insets(10, 12, 10, 12));
        verdictBanner.getStyleClass().add("verdict-banner");

        verdictDot = new Region();
        verdictDot.setPrefSize(8, 8);
        verdictDot.getStyleClass().add("verdict-dot");

        VBox vTextBox = new VBox(2);
        verdictLabel = new Label("AWAITING AUDIO ANALYSIS");
        verdictLabel.getStyleClass().add("verdict-label");
        verdictLabel.setStyle("-fx-font-size: 11px;");

        verdictSubLabel = new Label("Drop a track for lossy/lossless verification");
        verdictSubLabel.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9.5px; -fx-text-fill: #94A3B8;");

        vTextBox.getChildren().addAll(verdictLabel, verdictSubLabel);
        verdictBanner.getChildren().addAll(verdictDot, vTextBox);

        // Forensic 4-Metric Grid (Aligned with Standard-Resolution Analyzer)
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        // Metric 1: Content Reaches
        VBox cardCutoff = createMetricCard("CONTENT REACHES");
        lblCutoff = new Label("-- kHz");
        lblCutoff.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #F59E0B;");
        lblCutoffSub = new Label("Capacity: -- kHz");
        lblCutoffSub.getStyleClass().add("metric-sub");
        cardCutoff.getChildren().addAll(lblCutoff, lblCutoffSub);
        grid.add(cardCutoff, 0, 0);

        // Metric 2: File Format
        VBox cardBitrate = createMetricCard("FILE FORMAT");
        lblBitrate = new Label("--");
        lblBitrate.getStyleClass().add("metric-value-white");
        lblBitrateSub = new Label("-- kbps average");
        lblBitrateSub.getStyleClass().add("metric-sub");
        cardBitrate.getChildren().addAll(lblBitrate, lblBitrateSub);
        grid.add(cardBitrate, 1, 0);

        // Metric 3: Resolution Tier
        VBox cardDR = createMetricCard("RESOLUTION TIER");
        lblDR = new Label("--");
        lblDR.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: #F59E0B;");
        lblDRSub = new Label("Standard CD Source");
        lblDRSub.getStyleClass().add("metric-sub");
        cardDR.getChildren().addAll(lblDR, lblDRSub);
        grid.add(cardDR, 0, 1);

        // Metric 4: Dynamic Range
        VBox cardContainer = createMetricCard("DYNAMIC RANGE");
        lblContainer = new Label("--");
        lblContainer.getStyleClass().add("metric-value-emerald");
        lblContainerSub = new Label("Peak / RMS Level");
        lblContainerSub.getStyleClass().add("metric-sub");
        cardContainer.getChildren().addAll(lblContainer, lblContainerSub);
        grid.add(cardContainer, 1, 1);

        // Forensic Audit Trail Header & Container (No Scrollbar)
        VBox obsBox = new VBox(8);
        HBox trailHeader = new HBox(6);
        trailHeader.setAlignment(Pos.CENTER_LEFT);

        Label obsTitle = new Label("FORENSIC AUDIT TRAIL");
        obsTitle.getStyleClass().add("section-header");
        obsTitle.setStyle("-fx-font-size: 10px;");

        Region trailSpacer = new Region();
        HBox.setHgrow(trailSpacer, Priority.ALWAYS);

        btnViewDetails = new Button("🔍 View Details");
        btnViewDetails.getStyleClass().addAll("view-toggle-btn", "neo-btn-toggle");
        btnViewDetails.setStyle("-fx-font-size: 10px; -fx-padding: 3px 8px; -fx-text-fill: #38BDF8; -fx-border-color: rgba(56, 189, 248, 0.4);");
        btnViewDetails.setOnAction(e -> showDetailedReportDialog());

        trailHeader.getChildren().addAll(obsTitle, trailSpacer, btnViewDetails);

        // Cards Container for Audit Items (replaces raw ListView)
        auditCardsContainer = new VBox(6);
        auditCardsContainer.getChildren().add(createInitialPlaceholderCard());

        obsBox.getChildren().addAll(trailHeader, auditCardsContainer);

        getChildren().addAll(headerBox, verdictBanner, grid, obsBox);
    }

    public void setOnCollapseRequested(Runnable callback) {
        this.onCollapseRequested = callback;
    }

    private VBox createMetricCard(String title) {
        VBox box = new VBox(2);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.getStyleClass().add("metric-card");
        Label t = new Label(title);
        t.getStyleClass().add("metric-header");
        box.getChildren().add(t);
        return box;
    }

    private VBox createInitialPlaceholderCard() {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setStyle("-fx-background-color: rgba(15, 23, 42, 0.6); -fx-background-radius: 8px; -fx-border-color: rgba(255, 255, 255, 0.08); -fx-border-radius: 8px;");

        HBox top = new HBox(6);
        top.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("♫");
        icon.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");
        Label tag = new Label("READY FOR AUDIO");
        tag.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #94A3B8;");
        top.getChildren().addAll(icon, tag);

        Label desc = new Label("Drop a FLAC, WAV, ALAC, or MP3 file to execute forensic spectral analysis.");
        desc.setWrapText(true);
        desc.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10px; -fx-text-fill: #64748B;");

        card.getChildren().addAll(top, desc);
        return card;
    }

    public void updateReport(QualityReport report) {
        if (report == null) return;
        this.activeReport = report;

        Verdict v = report.verdict();
        AudioMetadata m = report.metadata();

        verdictLabel.setText(v.getDisplayName().toUpperCase());
        verdictSubLabel.setText(report.getVerdictSubText());
        verdictBanner.setStyle(String.format(
                "-fx-background-color: %s22; -fx-border-color: %s; -fx-border-radius: 8px; -fx-background-radius: 8px;",
                v.getHexColor(), v.getHexColor()
        ));
        verdictLabel.setStyle(String.format("-fx-text-fill: %s; -fx-font-weight: 800;", v.getHexColor()));
        verdictDot.setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 4px;", v.getHexColor()));

        // Metrics (Directly matching Standard-Resolution Analyzer)
        lblCutoff.setText(report.getContentReachesFormatted());
        lblCutoff.setStyle(String.format("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: %s;", v.getHexColor()));
        lblCutoffSub.setText(String.format("Capacity: up to %s", report.getContentCapacityFormatted()));

        lblBitrate.setText(report.getFileFormatSummary());
        lblBitrateSub.setText(String.format("%d-bit • %,d kbps avg", m.bitDepth() > 0 ? m.bitDepth() : 16, m.bitRateKbps()));

        String tierText = (v == Verdict.STUDIO_HI_RES) ? "STUDIO HI-RES" :
                (v == Verdict.STANDARD_RESOLUTION || v == Verdict.GENUINE_LOSSLESS) ? "STANDARD-RES" : "LOSSY FORMAT";
        lblDR.setText(tierText);
        lblDR.setStyle(String.format("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 12px; -fx-font-weight: 800; -fx-text-fill: %s;", v.getHexColor()));
        lblDRSub.setText(v == Verdict.STANDARD_RESOLUTION ? "Normal for 44.1k • Not Hi-Res" : "Audio Resolution Tier");

        lblContainer.setText(String.format("DR%.0f", report.dynamicRangeScore()));
        lblContainerSub.setText(String.format("Peak %.1f / RMS %.1f dBFS", report.peakDbfs(), report.rmsDbfs()));

        // Populate Icon-Based Audit Cards (Zero Scrollbars)
        auditCardsContainer.getChildren().clear();
        List<String> details = report.forensicDetails();
        if (details.isEmpty()) {
            auditCardsContainer.getChildren().add(createInitialPlaceholderCard());
        } else {
            for (String detail : details) {
                auditCardsContainer.getChildren().add(createAuditItemCard(detail, v));
            }
        }
    }

    private VBox createAuditItemCard(String detail, Verdict verdict) {
        VBox card = new VBox(3);
        card.setPadding(new Insets(8, 10, 8, 10));

        String lower = detail.toLowerCase();
        String iconStr;
        String tagText;
        String borderColor;
        String tagColor;

        if (lower.contains("standard-resolution") || lower.contains("not a hi-res")) {
            iconStr = "💿";
            tagText = "STANDARD CD RESOLUTION";
            borderColor = "rgba(245, 158, 11, 0.45)";
            tagColor = "#F59E0B";
        } else if (lower.contains("content reaches") || lower.contains("spectral energy") || lower.contains("reaches")) {
            iconStr = "〰";
            tagText = "SPECTRAL REACH";
            borderColor = "rgba(245, 158, 11, 0.45)";
            tagColor = "#F59E0B";
        } else if (lower.contains("brickwall") || lower.contains("cutoff") || lower.contains("upscaled") || lower.contains("fake")) {
            iconStr = "⚠️";
            tagText = "CUTOFF FILTER";
            borderColor = "rgba(239, 68, 68, 0.45)";
            tagColor = "#EF4444";
        } else if (lower.contains("container") || lower.contains("flac") || lower.contains("transcode")) {
            iconStr = "📦";
            tagText = "CONTAINER CHECK";
            borderColor = "rgba(168, 85, 247, 0.45)";
            tagColor = "#C084FC";
        } else if (lower.contains("dynamic range") || lower.contains("peak") || lower.contains("rms")) {
            iconStr = "📊";
            tagText = "DYNAMIC RANGE";
            borderColor = "rgba(16, 185, 129, 0.45)";
            tagColor = "#34D399";
        } else {
            iconStr = "🛡️";
            tagText = "FORENSIC LOG";
            borderColor = "rgba(56, 189, 248, 0.45)";
            tagColor = "#38BDF8";
        }

        card.setStyle(String.format(
                "-fx-background-color: rgba(15, 23, 42, 0.7); -fx-background-radius: 6px; -fx-border-color: %s; -fx-border-radius: 6px; -fx-border-width: 1px;",
                borderColor
        ));

        HBox badgeRow = new HBox(6);
        badgeRow.setAlignment(Pos.CENTER_LEFT);

        Label iconLbl = new Label(iconStr);
        iconLbl.setStyle("-fx-font-size: 11px;");

        Label tagLbl = new Label(tagText);
        tagLbl.setStyle(String.format("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9px; -fx-font-weight: 800; -fx-text-fill: %s;", tagColor));

        badgeRow.getChildren().addAll(iconLbl, tagLbl);

        Label descLbl = new Label(detail);
        descLbl.setWrapText(true);
        descLbl.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10px; -fx-text-fill: #CBD5E1; -fx-line-spacing: 1px;");

        card.getChildren().addAll(badgeRow, descLbl);
        return card;
    }

    /**
     * Modal dialog displaying the complete unabridged forensic breakdown,
     * matching the layout and metrics of the reference Lossless Quality Analyzer.
     */
    private void showDetailedReportDialog() {
        if (activeReport == null) return;

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.setTitle("Forensic Audio Audit Report");

        VBox root = new VBox(12);
        root.setPadding(new Insets(18));
        Verdict v = activeReport.verdict();
        String primaryColor = v.getHexColor();

        root.setStyle(String.format(
                "-fx-background-color: #0B0F19; -fx-border-color: %s; -fx-border-width: 1.5px; -fx-border-radius: 12px; -fx-background-radius: 12px;",
                primaryColor
        ));
        root.setPrefWidth(620);

        // Dialog Title Bar
        HBox topBar = new HBox(8);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label dlgTitle = new Label("AuraAudio Forensic Inspection Report");
        dlgTitle.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #FFFFFF;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnClose = new Button("✕");
        btnClose.setStyle("-fx-background-color: transparent; -fx-text-fill: #94A3B8; -fx-font-size: 14px; -fx-cursor: hand;");
        btnClose.setOnAction(e -> dialog.close());

        topBar.getChildren().addAll(dlgTitle, spacer, btnClose);

        // File Header Label (Reference screenshot style)
        AudioMetadata m = activeReport.metadata();
        String fileTitle = m != null ? m.title() : "Audio Track";
        Label lblFileName = new Label("File: " + fileTitle);
        lblFileName.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #F1F5F9;");

        String techLine = String.format("%s · %s · Peak %.1f dBFS · RMS %.1f dBFS",
                activeReport.getFileFormatSummary(),
                activeReport.getFileFormatDetails(),
                activeReport.peakDbfs(),
                activeReport.rmsDbfs());
        Label lblTechSummary = new Label(techLine);
        lblTechSummary.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9.5px; -fx-text-fill: #94A3B8;");
        lblTechSummary.setWrapText(true);

        VBox fileInfoBox = new VBox(2, lblFileName, lblTechSummary);

        // Highlighted Gold/Amber Callout Box (from screenshot 1)
        VBox calloutBox = new VBox(6);
        calloutBox.setPadding(new Insets(10, 14, 10, 14));
        calloutBox.setStyle(String.format(
                "-fx-background-color: %s18; -fx-border-color: %s; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-border-width: 1px;",
                primaryColor, primaryColor
        ));

        Label calloutTitle = new Label(String.format("%s · %s", fileTitle, v.getDisplayName()));
        calloutTitle.setStyle(String.format("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 11.5px; -fx-font-weight: 800; -fx-text-fill: %s;", primaryColor));

        Label calloutText = new Label(activeReport.getExplanatoryNote());
        calloutText.setWrapText(true);
        calloutText.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10px; -fx-text-fill: #CBD5E1; -fx-line-spacing: 2px;");

        calloutBox.getChildren().addAll(calloutTitle, calloutText);

        // 3-Card Summary Grid (Exact cards from screenshot 1)
        GridPane summaryGrid = new GridPane();
        summaryGrid.setHgap(8);
        summaryGrid.setVgap(8);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(33.33);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(33.33);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setPercentWidth(33.34);
        summaryGrid.getColumnConstraints().addAll(col1, col2, col3);

        // Card 1: Verdict
        VBox card1 = createSummaryCard(
                "Verdict",
                v.getDisplayName(),
                activeReport.getVerdictSubText(),
                primaryColor,
                v.getResolutionTier()
        );
        summaryGrid.add(card1, 0, 0);

        // Card 2: Content reaches
        VBox card2 = createSummaryCard(
                "Content reaches",
                activeReport.getContentReachesFormatted(),
                String.format("Content reaches %s. This %s file can hold up to %s.",
                        activeReport.getContentReachesFormatted(),
                        activeReport.getFileFormatSummary(),
                        activeReport.getContentCapacityFormatted()),
                "#F59E0B",
                -1
        );
        summaryGrid.add(card2, 1, 0);

        // Card 3: File format
        VBox card3 = createSummaryCard(
                "File format",
                activeReport.getFileFormatSummary(),
                String.format("%d-bit · %d ch · %s · %,d kbps average",
                        m != null && m.bitDepth() > 0 ? m.bitDepth() : 16,
                        m != null && m.channels() > 0 ? m.channels() : 2,
                        m != null ? m.getFormattedFileSize() : "--",
                        m != null ? m.bitRateKbps() : 1411),
                "#FFFFFF",
                -1
        );
        summaryGrid.add(card3, 2, 0);

        // Technical Audit Findings List
        VBox findingsBox = new VBox(6);
        Label findingsTitle = new Label("TECHNICAL AUDIT FINDINGS:");
        findingsTitle.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #64748B;");

        VBox findingsList = new VBox(4);
        for (String detail : activeReport.forensicDetails()) {
            HBox item = new HBox(6);
            item.setAlignment(Pos.TOP_LEFT);
            Label bullet = new Label("•");
            bullet.setStyle(String.format("-fx-text-fill: %s; -fx-font-weight: bold;", primaryColor));
            Label text = new Label(detail);
            text.setWrapText(true);
            text.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10px; -fx-text-fill: #E2E8F0;");
            item.getChildren().addAll(bullet, text);
            findingsList.getChildren().add(item);
        }
        findingsBox.getChildren().addAll(findingsTitle, findingsList);

        // Footer with Close Button
        Button btnDismiss = new Button("CLOSE REPORT");
        btnDismiss.setStyle(String.format(
                "-fx-background-color: #0F172A; -fx-border-color: %s; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-text-fill: %s; -fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 7px 18px; -fx-cursor: hand;",
                primaryColor, primaryColor
        ));
        btnDismiss.setOnAction(e -> dialog.close());
        HBox bottomBar = new HBox(btnDismiss);
        bottomBar.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(topBar, fileInfoBox, calloutBox, summaryGrid, findingsBox, bottomBar);

        Scene scene = new Scene(root);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private VBox createSummaryCard(String header, String value, String sub, String valColor, int tier) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10, 10, 10, 10));
        card.setStyle("-fx-background-color: rgba(15, 23, 42, 0.85); -fx-background-radius: 8px; -fx-border-color: rgba(255, 255, 255, 0.08); -fx-border-radius: 8px; -fx-border-width: 1px;");

        Label lblHeader = new Label(header);
        lblHeader.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #64748B;");

        Label lblVal = new Label(value);
        lblVal.setStyle(String.format("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: %s;", valColor));
        lblVal.setWrapText(true);

        Label lblSub = new Label(sub);
        lblSub.setWrapText(true);
        lblSub.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 9px; -fx-text-fill: #94A3B8;");

        card.getChildren().addAll(lblHeader, lblVal, lblSub);

        // If tier is provided (1, 2, or 3), render 3-tier segmented meter from reference screenshot
        if (tier > 0) {
            HBox meter = new HBox(4);
            meter.setAlignment(Pos.CENTER_LEFT);
            meter.setPadding(new Insets(4, 0, 0, 0));
            for (int i = 1; i <= 3; i++) {
                Region bar = new Region();
                bar.setPrefHeight(4);
                HBox.setHgrow(bar, Priority.ALWAYS);
                if (i <= tier) {
                    bar.setStyle("-fx-background-color: " + valColor + "; -fx-background-radius: 2px;");
                } else {
                    bar.setStyle("-fx-background-color: rgba(255, 255, 255, 0.12); -fx-background-radius: 2px;");
                }
                meter.getChildren().add(bar);
            }
            card.getChildren().add(meter);
        }

        return card;
    }

    private void addParamRow(GridPane grid, int row, String name, String value) {
        Label nameLbl = new Label(name + ":");
        nameLbl.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10px; -fx-text-fill: #94A3B8;");
        Label valLbl = new Label(value);
        valLbl.setStyle("-fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 10.5px; -fx-font-weight: bold; -fx-text-fill: #38BDF8;");
        grid.add(nameLbl, 0, row);
        grid.add(valLbl, 1, row);
    }
}
