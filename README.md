# 🎛️ AuraAudio — Neo-Retro Master Studio Console & Forensic Lossless DSP Analyzer

**AuraAudio** is a high-fidelity desktop audio player, real-time spectral workstation, and forensic lossless audio verifier built with modern Java 21 and JavaFX. It combines studio-grade DSP measurement capabilities with a neo-retro hardware console aesthetic.

---

## ✨ Key Features

### 🔍 Forensic Lossless & Transcode Verification
- **Fake Lossless Detection**: Identifies lossy files (e.g. 128 kbps, 192 kbps, 320 kbps MP3/AAC) upscaled or re-encoded into FLAC or WAV containers.
- **Accurate Resolution Categorization**: Automatically categorizes files into **Standard-Resolution File** (CD quality / 44.1 kHz, ~21.1–22.05 kHz bandwidth), **Genuine Hi-Res Lossless** (96k / 192k), or **Suspected Upscaled Lossy**.
- **Dynamic Range (DR) Scoring**: Analyzes whole-track crest factor, peak dBFS, and integrated RMS energy according to the Pleasurize Music Foundation DR standard.
- **Forensic Audit Findings**: Generates detailed inspection findings with confidence metrics and technical explanations.

### 📊 Neo-Retro Continuous Spectrogram & Interactive Visualizer
- **Real-Time Continuous Spectrogram**: Hardware-accelerated spectral waterfall with continuous background pre-buffering.
- **In-Canvas Seeking & Navigation**: Seek directly within the spectrogram with time ruler and elapsed markers.
- **Precision Tier Overlays**: Visual guidelines marking CD Nyquist (22.05 kHz), MP3 320k cutoff (20.5 kHz), MP3 192k cutoff (18.5 kHz), and MP3 128k cutoff (16.0 kHz).
- **Interactive Crosshair & Zoom**: Hover cursor to inspect exact frequency (Hz) and time coordinates with dynamic axis scaling.

### 🎚️ Studio Equalizer & Oscilloscope
- **10-Band Logarithmic Graphic Equalizer**: Precise frequency shaping with deep-black backdrop and subtle neon glow effects.
- **Real-Time Oscilloscope**: High-frame-rate time-domain waveform monitoring.
- **Variable Playback Speed**: Pitch-preserving playback speed modulation (0.25× to 2.0×).

### 🚀 Audiophile Audio Engine
- **Dedicated Audio Threading**: Decoupled high-priority platform audio thread with 350ms jitter-free hardware ring buffer.
- **Asynchronous DSP Pipeline**: Offloads FFT calculation and peak-hold processing to background worker threads, keeping UI frame rates locked at 60 FPS.

---

## 🏗️ Project Architecture

```
aura-audio/
├── core-dsp/       # Core audio engine, JTransforms FFT, DR calculation, format decoders
├── ui-desktop/     # JavaFX 21 Neo-Retro UI, hardware-accelerated canvas visualizers
└── server/         # Javalin 6 embedded REST and WebSocket telemetry API
```

---

## 🛠️ Prerequisites

- **Java Development Kit (JDK) 21** or later (LTS)
- **Gradle 8.x+** (or use the included `./gradlew` wrapper)
- Windows, macOS, or Linux with hardware graphics acceleration support

---

## 🚀 Getting Started

### 1. Clone the Repository
```bash
git clone https://github.com/hariprasaath-bot/aura-audio.git
cd aura-audio
```

### 2. Build and Run the Desktop App
```bash
# Windows
.\gradlew.bat :ui-desktop:run

# Linux / macOS
./gradlew :ui-desktop:run
```

### 3. Run Unit and Verification Tests
```bash
# Windows
.\gradlew.bat test

# Linux / macOS
./gradlew test
```

### 4. (Optional) Run the Headless DSP Server
```bash
# Windows
.\gradlew.bat :server:run

# Linux / macOS
./gradlew :server:run
```

---

## 📋 License

This project is licensed under the Apache 2.0 License.
