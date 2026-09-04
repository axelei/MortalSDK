package net.krusher.mortalsdk;

/**
 * Conversión entre los tiles de Mega Drive y una imagen.
 * <p>
 * Un tile son 8x8 píxeles de 4 bits: dos píxeles por byte, cuatro bytes por fila, 32 bytes por tile. Los
 * tiles se colocan en la imagen de izquierda a derecha y de arriba abajo, {@value #COLUMNS} por fila.
 * <p>
 * Los bloques de la ROM no siempre traen un número redondo de tiles, así que la última fila se rellena con
 * ceros. Al volver, se recorta al tamaño que tenía el bloque original, que se saca de la propia ROM.
 * <p>
 * No todos los bloques comprimidos son gráficos: los que no lo son se ven como ruido, pero van y vuelven
 * igual de bien, porque la conversión es byte a byte.
 */
public final class TileService {

    public static final int TILE_SIZE = 8;
    public static final int TILE_BYTES = 32;
    public static final int COLUMNS = 16;

    private static final int BYTES_PER_ROW = TILE_SIZE / 2;

    private TileService() {}

    /** Dibuja los bytes de un bloque como una hoja de tiles. */
    public static Bitmap toBitmap(byte[] data, int[] palette) {
        int tiles = Math.max(1, (data.length + TILE_BYTES - 1) / TILE_BYTES);
        int rows = (tiles + COLUMNS - 1) / COLUMNS;
        Bitmap image = Bitmap.indexed(COLUMNS * TILE_SIZE, rows * TILE_SIZE, palette);

        for (int tile = 0; tile < tiles; tile++) {
            int tileX = (tile % COLUMNS) * TILE_SIZE;
            int tileY = (tile / COLUMNS) * TILE_SIZE;
            int base = tile * TILE_BYTES;
            for (int row = 0; row < TILE_SIZE; row++) {
                for (int column = 0; column < TILE_SIZE; column += 2) {
                    int at = base + row * BYTES_PER_ROW + column / 2;
                    int both = at < data.length ? data[at] & 0xFF : 0;
                    image.setIndex(tileX + column, tileY + row, (both >> 4) & 0xF);
                    image.setIndex(tileX + column + 1, tileY + row, both & 0xF);
                }
            }
        }
        return image;
    }

    /**
     * Deshace lo anterior. El resultado se ajusta a {@code length}: se recorta si sobra (el relleno de la
     * última fila) y se completa con ceros si falta.
     */
    public static byte[] toTiles(Bitmap image, int[] palette, int length) {
        int columns = Math.max(1, image.getWidth() / TILE_SIZE);
        int rows = image.getHeight() / TILE_SIZE;
        byte[] data = new byte[length];

        for (int tile = 0; tile < columns * rows; tile++) {
            int tileX = (tile % columns) * TILE_SIZE;
            int tileY = (tile / columns) * TILE_SIZE;
            int base = tile * TILE_BYTES;
            if (base >= length) {
                break;
            }
            for (int row = 0; row < TILE_SIZE; row++) {
                for (int column = 0; column < TILE_SIZE; column += 2) {
                    int at = base + row * BYTES_PER_ROW + column / 2;
                    if (at >= length) {
                        continue;
                    }
                    int left = indexAt(image, palette, tileX + column, tileY + row);
                    int right = indexAt(image, palette, tileX + column + 1, tileY + row);
                    data[at] = (byte) ((left << 4) | right);
                }
            }
        }
        return data;
    }

    /**
     * El índice de paleta de un píxel. Si el PNG es indexado se coge tal cual, que es lo exacto; si el editor
     * lo ha convertido a color, se busca el color más parecido de la paleta.
     */
    static int indexAt(Bitmap image, int[] palette, int x, int y) {
        if (x >= image.getWidth() || y >= image.getHeight()) {
            return 0;
        }
        if (image.isIndexed()) {
            return image.indexAt(x, y) & 0xF;
        }
        return nearest(palette, image.getRgb(x, y));
    }

    private static int nearest(int[] palette, int argb) {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length && i < 16; i++) {
            int dr = red - ((palette[i] >> 16) & 0xFF);
            int dg = green - ((palette[i] >> 8) & 0xFF);
            int db = blue - (palette[i] & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

}
