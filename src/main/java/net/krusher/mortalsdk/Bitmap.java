package net.krusher.mortalsdk;

/**
 * Un búfer de píxeles pelado, en lugar del BufferedImage de java.awt.
 * <p>
 * No pretende ser una clase de imágenes completa: hace lo justo que necesitan {@link TileService} y
 * {@link Png}. Dejar java.desktop fuera es lo que permite que el ejecutable nativo sea un único fichero,
 * igual que con los WAV: AWT necesita metadatos de JNI y se lleva varias DLL al lado.
 * <p>
 * Adaptada de la que uso en CholeilSDK.
 */
public final class Bitmap {

    private final int width;
    private final int height;

    /** Todos los píxeles en ARGB. */
    private final int[] argb;

    /** Sólo en imágenes indexadas: la paleta y un índice por píxel. */
    private final int[] palette;
    private final byte[] indices;

    private Bitmap(int width, int height, int[] argb, int[] palette, byte[] indices) {
        this.width = width;
        this.height = height;
        this.argb = argb;
        this.palette = palette;
        this.indices = indices;
    }

    /** Una imagen en blanco cuyos píxeles son índices de {@code palette}. */
    public static Bitmap indexed(int width, int height, int[] palette) {
        int[] copy = new int[palette.length];
        System.arraycopy(palette, 0, copy, 0, palette.length);
        return new Bitmap(width, height, new int[width * height], copy, new byte[width * height]);
    }

    /** Una imagen de colores cualesquiera, tal y como venga de un PNG. */
    public static Bitmap trueColor(int width, int height, int[] argb) {
        return new Bitmap(width, height, argb, null, null);
    }

    /** Una imagen leída de un PNG indexado, que conserva sus índices originales. */
    public static Bitmap decodedIndexed(int width, int height, int[] argb, int[] palette, byte[] indices) {
        return new Bitmap(width, height, argb, palette, indices);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getRgb(int x, int y) {
        return argb[y * width + x];
    }

    /** ¿Trae la imagen sus propios índices de paleta? */
    public boolean isIndexed() {
        return indices != null;
    }

    public int indexAt(int x, int y) {
        return indices[y * width + x] & 0xFF;
    }

    /** Pinta un píxel; sólo vale en imágenes indexadas. */
    public void setIndex(int x, int y, int index) {
        if (indices == null) {
            throw new IllegalStateException("la imagen no es indexada");
        }
        indices[y * width + x] = (byte) index;
        argb[y * width + x] = palette[index];
    }

    /** La paleta, o null si la imagen no venía con una. */
    int[] palette() {
        return palette;
    }

    /** Un índice de paleta por píxel, o null si la imagen no es indexada. */
    byte[] indices() {
        return indices;
    }

}
