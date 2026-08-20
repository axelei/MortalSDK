package net.krusher.mortalsdk;

import junit.framework.TestCase;

import java.io.File;
import java.nio.file.Files;

public class WavServiceTest extends TestCase {

    public void testPcmWavRoundTrip() throws Exception {
        byte[] expected = new byte[] {0, 1, 32, 64, (byte) 128, (byte) 200, (byte) 255};
        File wav = File.createTempFile("mortalsdk-", ".wav");
        try {
            WavService.writeSigned8BitMono(expected, 7040, wav);
            assertTrue(Files.size(wav.toPath()) > expected.length);
            try (var stream = javax.sound.sampled.AudioSystem.getAudioInputStream(wav)) {
                assertEquals(7040, Math.round(stream.getFormat().getSampleRate()));
            }
            assertEquals(javax.sound.sampled.AudioFormat.Encoding.PCM_UNSIGNED, streamEncoding(wav));
            assertTrue(java.util.Arrays.equals(expected, WavService.readSigned8BitMono(wav, 7040)));
        } finally {
            Files.deleteIfExists(wav.toPath());
        }
    }

    private static javax.sound.sampled.AudioFormat.Encoding streamEncoding(File wav) throws Exception {
        try (var stream = javax.sound.sampled.AudioSystem.getAudioInputStream(wav)) {
            return stream.getFormat().getEncoding();
        }
    }
}
