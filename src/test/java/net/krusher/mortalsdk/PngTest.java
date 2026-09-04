package net.krusher.mortalsdk;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PngTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static Bitmap sample(int width, int height, int seed) {
        Bitmap image = Bitmap.indexed(width, height, PaletteService.grayscale());
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setIndex(x, y, random.nextInt(16));
            }
        }
        return image;
    }

    @Test
    public void keepsIndicesAndPaletteAcrossAWrite() throws Exception {
        Bitmap original = sample(128, 24, 3);
        File file = folder.newFile("a.png");
        Png.write(original, file);

        Bitmap read = Png.read(file);
        assertTrue("debería leerse como indexada", read.isIndexed());
        assertEquals(original.getWidth(), read.getWidth());
        assertEquals(original.getHeight(), read.getHeight());
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                assertEquals("índice en " + x + "," + y, original.indexAt(x, y), read.indexAt(x, y));
                assertEquals("color en " + x + "," + y, original.getRgb(x, y), read.getRgb(x, y));
            }
        }
    }

    /** Aunque la paleta tenga colores repetidos, los índices se conservan tal cual. */
    @Test
    public void keepsIndicesEvenWithRepeatedColours() throws Exception {
        int[] palette = new int[16];
        for (int i = 0; i < 16; i++) {
            palette[i] = 0xFF000000; // los 16 colores iguales
        }
        Bitmap original = Bitmap.indexed(16, 8, palette);
        for (int x = 0; x < 16; x++) {
            original.setIndex(x, 0, x);
        }
        File file = folder.newFile("dup.png");
        Png.write(original, file);

        Bitmap read = Png.read(file);
        for (int x = 0; x < 16; x++) {
            assertEquals(x, read.indexAt(x, 0));
        }
    }

    @Test
    public void writesFourBitIndexedPngs() throws Exception {
        File file = folder.newFile("h.png");
        Png.write(sample(32, 8, 1), file);
        byte[] png = Files.readAllBytes(file.toPath());
        assertEquals((byte) 0x89, png[0]);
        assertEquals("PNG", new String(png, 1, 3, StandardCharsets.US_ASCII));
        assertEquals("IHDR", new String(png, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(4, png[24]);   // profundidad
        assertEquals(3, png[25]);   // indexado
    }

    @Test
    public void readsATrueColourPng() throws Exception {
        int width = 4;
        int height = 2;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            raw.write(0); // sin filtro
            for (int x = 0; x < width; x++) {
                raw.write(x * 60);
                raw.write(y * 100);
                raw.write(20);
            }
        }
        File file = folder.newFile("rgb.png");
        Files.write(file.toPath(), truecolorPng(width, height, raw.toByteArray()));

        Bitmap read = Png.read(file);
        assertFalse(read.isIndexed());
        assertEquals(0xFF000000 | (0 << 16) | (0 << 8) | 20, read.getRgb(0, 0));
        assertEquals(0xFF000000 | (180 << 16) | (100 << 8) | 20, read.getRgb(3, 1));
    }

    @Test
    public void rejectsSomethingThatIsNotAPng() throws Exception {
        File file = folder.newFile("bad.png");
        Files.write(file.toPath(), "esto no es un png".getBytes(StandardCharsets.UTF_8));
        try {
            Png.read(file);
            fail("tendría que haber fallado");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("no es un PNG"));
        }
    }

    private static byte[] truecolorPng(int width, int height, byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Png.SIGNATURE);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeU32(ihdr, width);
        writeU32(ihdr, height);
        ihdr.write(8);  // profundidad
        ihdr.write(2);  // color verdadero
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        chunk(out, "IHDR", ihdr.toByteArray());

        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            idat.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        writeU32(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeU32(out, (int) crc.getValue());
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

}
