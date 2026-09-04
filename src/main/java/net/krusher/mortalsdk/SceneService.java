package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pantallas completas: un mapa de tiles de la ROM más el bloque de gráficos al que apunta.
 * <p>
 * El VDP dibuja los fondos con un mapa de 40x28 casillas, una por cada tile de la pantalla visible. Cada
 * casilla es una palabra {@code P CC V H NNNNNNNNNNN}: prioridad, línea de paleta, volteo vertical y
 * horizontal, e índice de tile. Juntando mapa y gráficos sale la pantalla tal y como se ve en el juego, en
 * vez de la hoja de tiles suelta.
 * <p>
 * Al inyectar se rehace el trabajo al revés: la imagen se parte en casillas de 8x8, se vuelven a juntar los
 * tiles repetidos (mirando también los cuatro volteos) y se reconstruyen el bloque de gráficos y el mapa. De
 * cada casilla se conservan la prioridad y la línea de paleta que tenía, porque eso no se puede deducir de
 * los píxeles; lo que se recalcula son el índice de tile y los volteos.
 */
public final class SceneService {

    public static final int COLUMNS = 40;
    public static final int ROWS = 28;
    public static final int CELLS = COLUMNS * ROWS;
    public static final int MAP_BYTES = CELLS * 2;

    private static final int TILE_PIXELS = TileService.TILE_SIZE * TileService.TILE_SIZE;
    private static final int INDEX_MASK = 0x7FF;
    private static final int MAX_TILES = INDEX_MASK + 1;
    private static final String PREFIX = "scene_";
    private static final String EXTENSION = ".png";

    private SceneService() {}

    /**
     * Una pantalla: el bloque del mapa y el de los gráficos que usa.
     */
    public record Scene(int mapAddress, int graphicsAddress) {
        public String fileName() {
            return String.format("%s%06x_%06x%s", PREFIX, mapAddress, graphicsAddress, EXTENSION);
        }
    }

    /**
     * Busca pantallas entre los bloques ya descomprimidos: un mapa es un bloque de exactamente 40x28
     * casillas, y sus gráficos son el bloque que tiene justo los tiles que el mapa usa. Sólo se acepta
     * cuando la cuenta cuadra exactamente y no hay más de un candidato, que es señal de que ese bloque de
     * gráficos se hizo para ese mapa.
     */
    public static List<Scene> find(List<RncService.Block> blocks) {
        Map<Integer, List<Integer>> byTileCount = new HashMap<>();
        for (RncService.Block block : blocks) {
            if (block.data().length % TileService.TILE_BYTES == 0) {
                byTileCount.computeIfAbsent(block.data().length / TileService.TILE_BYTES, k -> new ArrayList<>())
                        .add(block.address());
            }
        }
        List<Scene> scenes = new ArrayList<>();
        for (RncService.Block block : blocks) {
            if (block.data().length != MAP_BYTES) {
                continue;
            }
            int needed = 0;
            for (int cell = 0; cell < CELLS; cell++) {
                needed = Math.max(needed, readWord(block.data(), cell * 2) & INDEX_MASK);
            }
            List<Integer> candidates = byTileCount.get(needed + 1);
            if (candidates != null && candidates.size() == 1) {
                scenes.add(new Scene(block.address(), candidates.getFirst()));
            }
        }
        return scenes;
    }

    /** Dibuja la pantalla entera: mapa más gráficos, con sus volteos. */
    public static Bitmap render(byte[] map, byte[] graphics, int[] palette) {
        Bitmap image = Bitmap.indexed(COLUMNS * TileService.TILE_SIZE, ROWS * TileService.TILE_SIZE, palette);
        for (int cell = 0; cell < CELLS; cell++) {
            int word = readWord(map, cell * 2);
            int[] tile = tileOf(graphics, word & INDEX_MASK);
            boolean horizontal = (word & 0x0800) != 0;
            boolean vertical = (word & 0x1000) != 0;
            int cellX = (cell % COLUMNS) * TileService.TILE_SIZE;
            int cellY = (cell / COLUMNS) * TileService.TILE_SIZE;
            for (int y = 0; y < TileService.TILE_SIZE; y++) {
                for (int x = 0; x < TileService.TILE_SIZE; x++) {
                    int sourceX = horizontal ? TileService.TILE_SIZE - 1 - x : x;
                    int sourceY = vertical ? TileService.TILE_SIZE - 1 - y : y;
                    image.setIndex(cellX + x, cellY + y, tile[sourceY * TileService.TILE_SIZE + sourceX]);
                }
            }
        }
        return image;
    }

    /** Lo que sale de rehacer una pantalla: los dos bloques que hay que volver a meter en la ROM. */
    public record Rebuilt(byte[] graphics, byte[] map) {}

    /**
     * Reconstruye los gráficos y el mapa a partir de la imagen.
     * <p>
     * Si la imagen dibuja exactamente lo mismo que ya había, se devuelven los bloques originales tal cual,
     * así que una pantalla que no se ha tocado vuelve a salir idéntica byte a byte. En cuanto cambia algo se
     * rehace todo desde cero: la imagen se parte en casillas de 8x8 y se van juntando las repetidas mirando
     * también los cuatro volteos, con lo que suele hacer falta menos sitio que antes. De cada casilla se
     * conservan la prioridad y la línea de paleta, que no se pueden deducir de los píxeles.
     */
    public static Rebuilt rebuild(Bitmap image, byte[] originalMap, byte[] originalGraphics, int[] palette)
            throws IOException {
        if (image.getWidth() != COLUMNS * TileService.TILE_SIZE
                || image.getHeight() != ROWS * TileService.TILE_SIZE) {
            throw new IOException("La pantalla debe medir " + (COLUMNS * TileService.TILE_SIZE) + "x"
                    + (ROWS * TileService.TILE_SIZE) + " y mide " + image.getWidth() + "x" + image.getHeight());
        }

        int[][] cells = new int[CELLS][];
        boolean changed = false;
        for (int cell = 0; cell < CELLS; cell++) {
            cells[cell] = cellOf(image, palette, cell);
            changed |= !draws(originalGraphics, readWord(originalMap, cell * 2), cells[cell]);
        }
        if (!changed) {
            return new Rebuilt(originalGraphics, originalMap);
        }

        List<int[]> tiles = new ArrayList<>();
        Map<String, Integer> known = new HashMap<>();
        byte[] map = originalMap.clone();
        for (int cell = 0; cell < CELLS; cell++) {
            int index = -1;
            int flips = 0;
            for (int flip = 0; flip < 4 && index < 0; flip++) {
                Integer found = known.get(keyOf(orient(cells[cell], flip)));
                if (found != null) {
                    index = found;
                    flips = flip;
                }
            }
            if (index < 0) {
                index = tiles.size();
                if (index >= MAX_TILES) {
                    throw new IOException("La pantalla necesita más de " + MAX_TILES + " tiles distintos");
                }
                tiles.add(cells[cell]);
                known.put(keyOf(cells[cell]), index);
            }
            writeWord(map, cell * 2, (readWord(originalMap, cell * 2) & 0xE000)
                    | ((flips & 1) != 0 ? 0x0800 : 0)
                    | ((flips & 2) != 0 ? 0x1000 : 0)
                    | index);
        }

        // el bloque se queda con los tiles que se usan y nada más, para que siga cuadrando con el mapa
        byte[] graphics = new byte[tiles.size() * TileService.TILE_BYTES];
        for (int i = 0; i < tiles.size(); i++) {
            packTile(tiles.get(i), graphics, i * TileService.TILE_BYTES);
        }
        return new Rebuilt(graphics, map);
    }

    /** ¿La casilla, tal y como está en el mapa, sigue dibujando esos mismos píxeles? */
    private static boolean draws(byte[] graphics, int word, int[] pixels) {
        int[] tile = tileOf(graphics, word & INDEX_MASK);
        boolean horizontal = (word & 0x0800) != 0;
        boolean vertical = (word & 0x1000) != 0;
        for (int y = 0; y < TileService.TILE_SIZE; y++) {
            for (int x = 0; x < TileService.TILE_SIZE; x++) {
                int sourceX = horizontal ? TileService.TILE_SIZE - 1 - x : x;
                int sourceY = vertical ? TileService.TILE_SIZE - 1 - y : y;
                if (tile[sourceY * TileService.TILE_SIZE + sourceX] != pixels[y * TileService.TILE_SIZE + x]) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- ayudas

    private static int[] tileOf(byte[] graphics, int index) {
        int[] pixels = new int[TILE_PIXELS];
        int base = index * TileService.TILE_BYTES;
        for (int y = 0; y < TileService.TILE_SIZE; y++) {
            for (int x = 0; x < TileService.TILE_SIZE; x += 2) {
                int at = base + y * (TileService.TILE_SIZE / 2) + x / 2;
                int both = at >= 0 && at < graphics.length ? graphics[at] & 0xFF : 0;
                pixels[y * TileService.TILE_SIZE + x] = (both >> 4) & 0xF;
                pixels[y * TileService.TILE_SIZE + x + 1] = both & 0xF;
            }
        }
        return pixels;
    }

    private static void packTile(int[] pixels, byte[] target, int at) {
        for (int y = 0; y < TileService.TILE_SIZE; y++) {
            for (int x = 0; x < TileService.TILE_SIZE; x += 2) {
                int left = pixels[y * TileService.TILE_SIZE + x] & 0xF;
                int right = pixels[y * TileService.TILE_SIZE + x + 1] & 0xF;
                target[at + y * (TileService.TILE_SIZE / 2) + x / 2] = (byte) ((left << 4) | right);
            }
        }
    }

    private static int[] cellOf(Bitmap image, int[] palette, int cell) {
        int cellX = (cell % COLUMNS) * TileService.TILE_SIZE;
        int cellY = (cell / COLUMNS) * TileService.TILE_SIZE;
        int[] pixels = new int[TILE_PIXELS];
        for (int y = 0; y < TileService.TILE_SIZE; y++) {
            for (int x = 0; x < TileService.TILE_SIZE; x++) {
                pixels[y * TileService.TILE_SIZE + x] =
                        TileService.indexAt(image, palette, cellX + x, cellY + y);
            }
        }
        return pixels;
    }

    /** flip: bit 0 horizontal, bit 1 vertical. */
    private static int[] orient(int[] pixels, int flip) {
        if (flip == 0) {
            return pixels;
        }
        int[] out = new int[TILE_PIXELS];
        for (int y = 0; y < TileService.TILE_SIZE; y++) {
            for (int x = 0; x < TileService.TILE_SIZE; x++) {
                int sourceX = (flip & 1) != 0 ? TileService.TILE_SIZE - 1 - x : x;
                int sourceY = (flip & 2) != 0 ? TileService.TILE_SIZE - 1 - y : y;
                out[y * TileService.TILE_SIZE + x] = pixels[sourceY * TileService.TILE_SIZE + sourceX];
            }
        }
        return out;
    }

    private static String keyOf(int[] pixels) {
        StringBuilder key = new StringBuilder(TILE_PIXELS);
        for (int pixel : pixels) {
            key.append(Character.forDigit(pixel & 0xF, 16));
        }
        return key.toString();
    }

    private static int readWord(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static void writeWord(byte[] data, int at, int value) {
        data[at] = (byte) (value >> 8);
        data[at + 1] = (byte) value;
    }

    public static boolean isSceneFile(String name) {
        return name.startsWith(PREFIX) && name.endsWith(EXTENSION);
    }

    public static File fileOf(Scene scene) {
        return new File("extracted", scene.fileName());
    }

}
