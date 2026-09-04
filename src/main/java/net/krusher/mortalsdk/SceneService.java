package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

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
     * Una pantalla: el mapa y el bloque de gráficos que usa.
     *
     * @param compressedMap si el mapa es un bloque RNC. Alguno está sin comprimir, suelto en la ROM, y
     *                      entonces se lee y se escribe tal cual, sin pasar por el compresor.
     */
    public record Scene(int mapAddress, int graphicsAddress, boolean compressedMap) {
        public String fileName() {
            return String.format("%s%06x_%06x%s", PREFIX, mapAddress, graphicsAddress, EXTENSION);
        }
    }

    /**
     * Busca pantallas entre los bloques ya descomprimidos.
     * <p>
     * Primero se hace caso a las parejas que diga la configuración. Después se emparejan solas las que no
     * dejan lugar a dudas: un mapa es un bloque de exactamente 40x28 casillas, y sus gráficos son el bloque
     * que tiene justo los tiles que el mapa usa, sin que haya otro candidato con esa misma cuenta.
     */
    public static List<Scene> find(List<RncService.Block> blocks, byte[] fileData) {
        Map<Integer, Integer> lengths = new HashMap<>();
        for (RncService.Block block : blocks) {
            lengths.put(block.address(), block.data().length);
        }
        List<Scene> scenes = new ArrayList<>();
        Set<Integer> takenMaps = new HashSet<>();
        for (Map.Entry<Integer, Integer> pair : App.config.scenes().entrySet()) {
            if (pair.getValue() == Config.NONE) {
                takenMaps.add(pair.getKey());   // dicho a mano: este mapa no forma pantalla
                continue;
            }
            Integer mapLength = lengths.get(pair.getKey());
            boolean compressed = mapLength != null && mapLength == MAP_BYTES;
            // hay mapas que no están comprimidos, sino sueltos en la ROM
            boolean raw = mapLength == null && pair.getKey() + MAP_BYTES <= fileData.length;
            if (!lengths.containsKey(pair.getValue()) || (!compressed && !raw)) {
                Log.pnl("La pantalla {0} de la configuración no cuadra con la ROM, se ignora.",
                        Integer.toHexString(pair.getKey()));
                continue;
            }
            scenes.add(new Scene(pair.getKey(), pair.getValue(), compressed));
            takenMaps.add(pair.getKey());
        }

        Map<Integer, List<Integer>> byTileCount = new HashMap<>();
        for (RncService.Block block : blocks) {
            if (block.data().length % TileService.TILE_BYTES == 0) {
                byTileCount.computeIfAbsent(block.data().length / TileService.TILE_BYTES, k -> new ArrayList<>())
                        .add(block.address());
            }
        }
        for (RncService.Block block : blocks) {
            if (block.data().length != MAP_BYTES || takenMaps.contains(block.address())) {
                continue;
            }
            int needed = 0;
            for (int cell = 0; cell < CELLS; cell++) {
                needed = Math.max(needed, readWord(block.data(), cell * 2) & INDEX_MASK);
            }
            List<Integer> candidates = byTileCount.get(needed + 1);
            if (candidates != null && candidates.size() == 1) {
                scenes.add(new Scene(block.address(), candidates.getFirst(), true));
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

    /** Una pantalla lista para rehacer: su mapa original y la imagen que ha quedado. */
    public record Input(int mapAddress, byte[] map, Bitmap image) {}

    /** Lo que sale de rehacer: el bloque de gráficos y el mapa de cada pantalla, por dirección. */
    public record Rebuilt(byte[] graphics, Map<Integer, byte[]> maps) {}

    /**
     * Reconstruye un bloque de gráficos y los mapas de todas las pantallas que lo usan.
     * <p>
     * Se rehacen juntas a propósito: varias pantallas pueden compartir el mismo bloque, y si cada una lo
     * reconstruyese por su cuenta la última pisaría a las demás.
     * <p>
     * Si ninguna imagen dibuja nada distinto de lo que ya había, se devuelven los bloques originales tal
     * cual, así que lo que no se toca vuelve a salir idéntico byte a byte.
     * <p>
     * Si algo ha cambiado hay dos maneras. Cuando el bloque es de una sola pantalla se rehace desde cero,
     * juntando los tiles repetidos y los cuatro volteos, con lo que suele ocupar menos. Si lo comparten
     * varias no se pueden renumerar los tiles sin estropear el resto, así que se respetan los que ya había
     * y sólo se añaden al final los que hagan falta.
     * <p>
     * De cada casilla se conservan la prioridad y la línea de paleta, que no salen de los píxeles.
     */
    public static Rebuilt rebuild(byte[] originalGraphics, List<Input> inputs, int[] palette)
            throws IOException {
        for (Input input : inputs) {
            if (input.image().getWidth() != COLUMNS * TileService.TILE_SIZE
                    || input.image().getHeight() != ROWS * TileService.TILE_SIZE) {
                throw new IOException("La pantalla debe medir " + (COLUMNS * TileService.TILE_SIZE) + "x"
                        + (ROWS * TileService.TILE_SIZE) + " y mide "
                        + input.image().getWidth() + "x" + input.image().getHeight());
            }
        }

        Map<Integer, int[][]> cells = new LinkedHashMap<>();
        boolean changed = false;
        for (Input input : inputs) {
            int[][] own = new int[CELLS][];
            for (int cell = 0; cell < CELLS; cell++) {
                own[cell] = cellOf(input.image(), palette, cell);
                changed |= !draws(originalGraphics, readWord(input.map(), cell * 2), own[cell]);
            }
            cells.put(input.mapAddress(), own);
        }
        if (!changed) {
            Map<Integer, byte[]> maps = new LinkedHashMap<>();
            for (Input input : inputs) {
                maps.put(input.mapAddress(), input.map());
            }
            return new Rebuilt(originalGraphics, maps);
        }

        boolean shared = inputs.size() > 1;
        List<int[]> tiles = new ArrayList<>();
        Map<String, Integer> known = new HashMap<>();
        int keep = shared ? originalGraphics.length / TileService.TILE_BYTES : 0;
        for (int i = 0; i < keep; i++) {
            tiles.add(tileOf(originalGraphics, i));
        }
        for (int i = tiles.size() - 1; i >= 0; i--) {
            known.put(keyOf(tiles.get(i)), i);
        }

        Map<Integer, byte[]> maps = new LinkedHashMap<>();
        for (Input input : inputs) {
            int[][] own = cells.get(input.mapAddress());
            byte[] map = input.map().clone();
            for (int cell = 0; cell < CELLS; cell++) {
                int original = readWord(input.map(), cell * 2);
                if (shared && draws(originalGraphics, original, own[cell])) {
                    continue;
                }
                int index = -1;
                int flips = 0;
                for (int flip = 0; flip < 4 && index < 0; flip++) {
                    Integer found = known.get(keyOf(orient(own[cell], flip)));
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
                    tiles.add(own[cell]);
                    known.putIfAbsent(keyOf(own[cell]), index);
                }
                writeWord(map, cell * 2, (original & 0xE000)
                        | ((flips & 1) != 0 ? 0x0800 : 0)
                        | ((flips & 2) != 0 ? 0x1000 : 0)
                        | index);
            }
            maps.put(input.mapAddress(), map);
        }

        byte[] graphics = shared
                ? java.util.Arrays.copyOf(originalGraphics,
                        Math.max(originalGraphics.length, tiles.size() * TileService.TILE_BYTES))
                : new byte[tiles.size() * TileService.TILE_BYTES];
        for (int i = keep; i < tiles.size(); i++) {
            packTile(tiles.get(i), graphics, i * TileService.TILE_BYTES);
        }
        return new Rebuilt(graphics, maps);
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

    /** Los bytes del mapa: del bloque descomprimido, o de la propia ROM si no está comprimido. */
    public static byte[] mapOf(Scene scene, Map<Integer, byte[]> blocks, byte[] fileData) {
        if (scene.compressedMap()) {
            return blocks.get(scene.mapAddress());
        }
        return java.util.Arrays.copyOfRange(fileData, scene.mapAddress(), scene.mapAddress() + MAP_BYTES);
    }

    public static boolean isSceneFile(String name) {
        return name.startsWith(PREFIX) && name.endsWith(EXTENSION);
    }

    public static File fileOf(Scene scene) {
        return new File("extracted", scene.fileName());
    }

}
