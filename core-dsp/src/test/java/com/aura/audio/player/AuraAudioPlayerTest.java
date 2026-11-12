package com.aura.audio.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuraAudioPlayerTest {

    @Test
    @DisplayName("Verify playback speed clamping and state transitions")
    void testSpeedClampingAndState() {
        AuraAudioPlayer player = new AuraAudioPlayer();

        assertEquals(1.0f, player.getPlaybackSpeed(), 0.001f);
        player.setPlaybackSpeed(0.5f);
        assertEquals(0.5f, player.getPlaybackSpeed(), 0.001f);

        // Clamping bounds
        player.setPlaybackSpeed(0.05f);
        assertEquals(0.20f, player.getPlaybackSpeed(), 0.001f, "Speed should be clamped to minimum 0.20x");

        player.setPlaybackSpeed(5.0f);
        assertEquals(2.50f, player.getPlaybackSpeed(), 0.001f, "Speed should be clamped to maximum 2.50x");

        // Volume clamping
        player.setVolume(1.5f);
        assertEquals(1.0f, player.getVolume(), 0.001f);
        player.setVolume(-0.5f);
        assertEquals(0.0f, player.getVolume(), 0.001f);

        // Initial state
        assertEquals(AuraAudioPlayer.State.IDLE, player.getState());
        player.close();
    }
}

