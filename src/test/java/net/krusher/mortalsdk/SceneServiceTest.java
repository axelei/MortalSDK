package net.krusher.mortalsdk;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SceneServiceTest {

    @Before
    public void useDefaultConfig() {
        App.config = new Config();
    }

    private static SceneService.Rebuilt rebuildOne(Bitmap image, byte[] map, byte[] gfx, int[] palette)
            throws java.io.IOException {
        return SceneService.rebuild(gfx, List.of(new SceneService.Input(1, map, image)), palette);
    }

    /** Unos gráficos con {@code tiles} tiles distintos y un mapa que los usa todos. */
    private static byte[] graphics(int tiles) {
        byte[] data = new byte[tiles * TileService.TILE_BYTES];
        Random random = new Random(tiles);
        for (int tile = 0; tile < tiles; tile++) {
            // cada tile distinto de los demás: se marca con su número y se rellena aleatorio
            random.nextBytes(data);
            data[tile * TileService.TILE_BYTES] = (byte) (tile & 0xFF);
            data[tile * TileService.TILE_BYTES + 1] = (byte) ((tile >> 8) & 0xFF);
        }
        return data;
    }

    private static byte[] map(int tiles) {
        byte[] data = new byte[SceneService.MAP_BYTES];
        for (int cell = 0; cell < SceneService.CELLS; cell++) {
            int index = cell % tiles;
            int word = index | (cell % 7 == 0 ? 0x0800 : 0) | (cell % 11 == 0 ? 0x1000 : 0);
            data[cell * 2] = (byte) (word >> 8);
            data[cell * 2 + 1] = (byte) word;
        }
        return data;
    }

    @Test
    public void findsAMapWithItsGraphics() {
        int tiles = 200;
        List<RncService.Block> blocks = List.of(
                new RncService.Block(0x100000, 999, map(tiles)),
                new RncService.Block(0x200000, 999, graphics(tiles)),
                new RncService.Block(0x300000, 999, graphics(64)));
        List<SceneService.Scene> scenes = SceneService.find(blocks);
        assertEquals(1, scenes.size());
        assertEquals(0x100000, scenes.getFirst().mapAddress());
        assertEquals(0x200000, scenes.getFirst().graphicsAddress());
    }

    @Test
    public void rendersAndGivesBackTheSameBlocksWhenNothingChanges() throws Exception {
        int tiles = 200;
        byte[] gfx = graphics(tiles);
        byte[] tileMap = map(tiles);
        int[] palette = PaletteService.grayscale();

        Bitmap image = SceneService.render(tileMap, gfx, palette);
        assertEquals(SceneService.COLUMNS * TileService.TILE_SIZE, image.getWidth());
        assertEquals(SceneService.ROWS * TileService.TILE_SIZE, image.getHeight());

        SceneService.Rebuilt rebuilt = rebuildOne(image, tileMap, gfx, palette);
        assertArrayEquals("los gráficos deben salir intactos", gfx, rebuilt.graphics());
        assertArrayEquals("el mapa debe salir intacto", tileMap, rebuilt.maps().get(1));
    }

    @Test
    public void keepsThePictureAfterEditingIt() throws Exception {
        int tiles = 200;
        byte[] gfx = graphics(tiles);
        byte[] tileMap = map(tiles);
        int[] palette = PaletteService.grayscale();

        Bitmap image = SceneService.render(tileMap, gfx, palette);
        for (int y = 16; y < 32; y++) {
            for (int x = 24; x < 40; x++) {
                image.setIndex(x, y, 9);
            }
        }

        SceneService.Rebuilt rebuilt = rebuildOne(image, tileMap, gfx, palette);
        Bitmap again = SceneService.render(rebuilt.maps().get(1), rebuilt.graphics(), palette);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                assertEquals("píxel " + x + "," + y, image.indexAt(x, y), again.indexAt(x, y));
            }
        }
        // al rehacerlo desde cero el bloque no debería crecer: los tiles repetidos se juntan
        assertTrue("los gráficos no deberían crecer",
                rebuilt.graphics().length <= gfx.length + 16 * TileService.TILE_BYTES);
    }

    @Test
    public void keepsPriorityAndPaletteLineOfEachCell() throws Exception {
        int tiles = 100;
        byte[] gfx = graphics(tiles);
        byte[] tileMap = map(tiles);
        tileMap[0] = (byte) 0xA0; // prioridad y línea de paleta 1 en la primera casilla
        int[] palette = PaletteService.grayscale();

        Bitmap image = SceneService.render(tileMap, gfx, palette);
        image.setIndex(0, 0, 7);
        SceneService.Rebuilt rebuilt = rebuildOne(image, tileMap, gfx, palette);
        assertEquals("prioridad y línea de paleta", 0xA0, rebuilt.maps().get(1)[0] & 0xE0);
    }

    @Test
    public void refusesAnImageOfTheWrongSize() throws Exception {
        try {
            rebuildOne(Bitmap.indexed(64, 64, PaletteService.grayscale()),
                    map(50), graphics(50), PaletteService.grayscale());
            fail("tendría que haber fallado");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("debe medir"));
        }
    }

}
