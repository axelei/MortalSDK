package net.krusher.mortalsdk;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TileServiceTest {

    private static byte[] noise(int size, int seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private void assertRoundTrip(int size) {
        byte[] data = noise(size, size);
        int[] palette = PaletteService.grayscale();
        Bitmap image = TileService.toBitmap(data, palette);
        assertEquals("ancho", TileService.COLUMNS * TileService.TILE_SIZE, image.getWidth());
        assertArrayEquals("tamaño " + size, data, TileService.toTiles(image, palette, data.length));
    }

    @Test
    public void roundTripsWholeTiles() {
        assertRoundTrip(32);
        assertRoundTrip(512);
        assertRoundTrip(33408);
    }

    /** En la ROM hay bloques que no son un número redondo de tiles: la última fila va rellena. */
    @Test
    public void roundTripsPartialTiles() {
        assertRoundTrip(560);
        assertRoundTrip(6200);
        assertRoundTrip(260);
        assertRoundTrip(1);
    }

    @Test
    public void rendersTheExpectedSize() {
        // 512 bytes son 16 tiles, justo una fila
        Bitmap oneRow = TileService.toBitmap(new byte[512], PaletteService.grayscale());
        assertEquals(128, oneRow.getWidth());
        assertEquals(8, oneRow.getHeight());
        // 513 bytes ya necesitan una segunda fila
        assertEquals(16, TileService.toBitmap(new byte[513], PaletteService.grayscale()).getHeight());
    }

    @Test
    public void mapsPixelsToTheRightNibbles() {
        byte[] data = new byte[32];
        data[0] = (byte) 0xA3;
        Bitmap image = TileService.toBitmap(data, PaletteService.grayscale());
        assertEquals(0xA, image.indexAt(0, 0));
        assertEquals(0x3, image.indexAt(1, 0));
    }

    /** Si el editor ha convertido el PNG a color, los índices se recuperan por el color más parecido. */
    @Test
    public void recoversIndicesFromATrueColorImage() {
        byte[] data = noise(2048, 7);
        int[] palette = PaletteService.grayscale();
        Bitmap indexed = TileService.toBitmap(data, palette);

        int[] argb = new int[indexed.getWidth() * indexed.getHeight()];
        for (int y = 0; y < indexed.getHeight(); y++) {
            for (int x = 0; x < indexed.getWidth(); x++) {
                argb[y * indexed.getWidth() + x] = indexed.getRgb(x, y);
            }
        }
        Bitmap trueColor = Bitmap.trueColor(indexed.getWidth(), indexed.getHeight(), argb);
        assertArrayEquals(data, TileService.toTiles(trueColor, palette, data.length));
    }

    @Test
    public void readsAGenesisPalette() {
        // 0000 BBB0 GGG0 RRR0: rojo del todo es 0x000E, verde 0x00E0, azul 0x0E00
        byte[] rom = new byte[PaletteService.SIZE];
        rom[2] = 0x00; rom[3] = 0x0E;          // color 1: rojo
        rom[4] = 0x00; rom[5] = (byte) 0xE0;   // color 2: verde
        rom[6] = 0x0E; rom[7] = 0x00;          // color 3: azul
        int[] palette = PaletteService.readFromRom(rom, 0);
        assertEquals(0xFFFF0000, palette[1]);
        assertEquals(0xFF00FF00, palette[2]);
        assertEquals(0xFF0000FF, palette[3]);
        assertEquals(0xFF000000, palette[4]);
        assertNotEquals(palette[1], palette[2]);
    }

    /** El color 0 es transparente: se ve el fondo del VDP, no lo que guarde la paleta. */
    @Test
    public void paintsColourZeroBlackWhateverTheRomSays() {
        byte[] rom = new byte[PaletteService.SIZE];
        rom[0] = 0x0E; rom[1] = 0x00;          // en la ROM el color 0 es azul
        assertEquals(0xFF000000, PaletteService.readFromRom(rom, 0)[0]);
    }

}
