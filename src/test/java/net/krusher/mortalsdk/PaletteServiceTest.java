package net.krusher.mortalsdk;

import junit.framework.TestCase;

public class PaletteServiceTest extends TestCase {

    public void testFindsReferencedMegaDrivePalette() {
        byte[] rom = new byte[0x200];
        int offset = 0x100;
        rom[1] = 0;
        rom[2] = 1;
        rom[3] = 0;
        for (int color = 1; color < 16; color++) {
            int word = (color & 7) << 1 | ((color + 2) & 7) << 5 | ((color + 4) & 7) << 9;
            rom[offset + color * 2] = (byte) (word >> 8);
            rom[offset + color * 2 + 1] = (byte) word;
        }
        var result = PaletteService.findReferencedPalettes(rom);
        assertEquals(1, result.size());
        assertEquals(offset, result.getFirst().offset());
        assertEquals(java.util.List.of(0), result.getFirst().references());
    }

    public void testConvertsCramChannelsToRgb() {
        byte[] raw = new byte[32];
        raw[2] = 0x00;
        raw[3] = 0x0e;
        raw[4] = 0x00;
        raw[5] = (byte) 0xe0;
        raw[6] = 0x0e;
        raw[7] = 0x00;
        var image = PaletteService.renderPalette(raw, 1);
        assertEquals(0xff0000, image.getRGB(1, 0) & 0xffffff);
        assertEquals(0x00ff00, image.getRGB(2, 0) & 0xffffff);
        assertEquals(0x0000ff, image.getRGB(3, 0) & 0xffffff);
    }

    public void testInjectsValidatedRawPalette() throws Exception {
        java.io.File directory = java.nio.file.Files.createTempDirectory("mortalsdk-palettes").toFile();
        byte[] palette = new byte[32];
        palette[3] = 0x0e;
        java.nio.file.Files.write(new java.io.File(directory, "palette_000100.pal").toPath(), palette);
        byte[] rom = new byte[0x200];
        PaletteService.injectPalettes(rom, directory);
        assertEquals(0x0e, rom[0x103]);
    }
}
