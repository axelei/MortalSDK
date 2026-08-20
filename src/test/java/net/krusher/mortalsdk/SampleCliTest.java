package net.krusher.mortalsdk;

import junit.framework.TestCase;

import java.io.File;
import java.nio.file.Files;

public class SampleCliTest extends TestCase {

    public void testReplaceRelocatesMultipleSamplesAndWritesNewRom() throws Exception {
        File directory = Files.createTempDirectory("mortalsdk-sample-cli").toFile();
        File input = new File(directory, "input.bin");
        File output = new File(directory, "output.bin");
        File config = new File(directory, "test.properties");
        File wav2 = new File(directory, "sample2.wav");
        File wav3 = new File(directory, "sample3.wav");

        byte[] rom = new byte[0x600];
        System.arraycopy(new byte[] {2, 0, 1, 0x20, 0, 4, 0, (byte) 0xb5}, 0, rom, 4, 8);
        System.arraycopy(new byte[] {3, 0, 1, 0x30, 0, 4, 0, (byte) 0xb5}, 0, rom, 12, 8);
        Files.write(input.toPath(), rom);
        Files.writeString(config.toPath(), "spaceRanges=768,1023\n"
                + "pcmSampleRate=7040\nsampleTableOffset=0x4\nsampleCount=2\n");
        WavService.writeSigned8BitMono(new byte[] {1, 2, 3}, 7040, wav2);
        WavService.writeSigned8BitMono(new byte[] {4, 5, 6, 7, 8}, 7040, wav3);

        SampleCli.run(new String[] {"sample", "replace", input.getPath(), output.getPath(), config.getPath(),
                "03", wav3.getPath(), "02", wav2.getPath()});

        byte[] patched = Files.readAllBytes(output.toPath());
        assertTrue(input.isFile());
        assertEquals(0x300, readPointer(patched, 5));
        assertEquals(3, readWord(patched, 8));
        assertEquals(0x304, readPointer(patched, 13));
        assertEquals(5, readWord(patched, 16));
        assertEquals(1, patched[0x300]);
        assertEquals(4, patched[0x304]);
    }

    public void testReplaceRefusesToOverwriteInput() throws Exception {
        File directory = Files.createTempDirectory("mortalsdk-sample-cli").toFile();
        File input = new File(directory, "input.bin");
        Files.write(input.toPath(), new byte[16]);
        try {
            SampleCli.run(new String[] {"sample", "replace", input.getPath(), input.getPath(), "config", "02", "x.wav"});
            fail("Debia impedir sobrescribir la ROM de entrada");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("distinta"));
        }
    }

    private static int readPointer(byte[] data, int offset) {
        return (data[offset] & 0xff) << 16 | (data[offset + 1] & 0xff) << 8 | data[offset + 2] & 0xff;
    }

    private static int readWord(byte[] data, int offset) {
        return (data[offset] & 0xff) << 8 | data[offset + 1] & 0xff;
    }
}
