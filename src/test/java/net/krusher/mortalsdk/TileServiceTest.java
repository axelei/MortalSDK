package net.krusher.mortalsdk;

import junit.framework.TestCase;

import java.util.Arrays;

public class TileServiceTest extends TestCase {

    public void test4BppPngRoundTrip() throws Exception {
        byte[] expected = new byte[64];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 37);
        }
        var image = TileService.decode4Bpp(expected, 16);
        assertEquals(16, image.getWidth());
        assertEquals(8, image.getHeight());
        assertTrue(Arrays.equals(expected, TileService.encode4Bpp(image, 2)));
    }
}
