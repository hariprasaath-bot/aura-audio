package com.aura.audio.decoder;

import com.aura.audio.model.AudioSampleBuffer;
import org.junit.jupiter.api.Test;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class AudioDecoderTest {

    @Test
    void testDecodeFlacFiles() throws Exception {
        File flac24 = new File("D:/songs/music-sample-96000hz-24bit.flac");
        if (flac24.exists()) {
            AudioSampleBuffer buffer = AudioDecoder.decodeToBuffer(flac24);
            assertNotNull(buffer);
            assertEquals(96000, buffer.sampleRate());
            assertEquals(2, buffer.channels());
            assertEquals(24, buffer.bitDepth());
            assertTrue(buffer.totalFrames() > 0);
            System.out.println("24-bit FLAC decoded successfully: " + buffer.totalFrames() + " frames");

            // Also test openPcmStream fallback capability
            try (var pcm = AudioDecoder.openPcmStream(flac24)) {
                assertNotNull(pcm);
                System.out.println("openPcmStream format: " + pcm.getFormat());
            }
        }

        File flacSnoop = new File("D:/songs/Snoop Dogg - Gin and Juice.flac");
        if (flacSnoop.exists()) {
            AudioSampleBuffer buffer = AudioDecoder.decodeToBuffer(flacSnoop);
            assertNotNull(buffer);
            assertTrue(buffer.totalFrames() > 0);
            System.out.println("Snoop Dogg FLAC decoded successfully: " + buffer.totalFrames() + " frames");
        }
    }
}
