package net.krusher.mortalsdk;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Paletas de Mega Drive.
 * <p>
 * Una línea de CRAM son 16 palabras big-endian con el formato {@code 0000 BBB0 GGG0 RRR0}: tres bits por
 * canal, y el resto de bits a cero.
 * <p>
 * Muchas paletas se encuentran solas: el juego guarda tablas donde el puntero a la paleta va justo delante
 * del puntero al bloque de gráficos que la usa, así que basta buscar esos pares comprobando que lo que hay
 * al otro lado tiene de verdad formato CRAM. Lo que no aparezca así se puede indicar a mano en la
 * configuración, y si no, se dibuja con una rampa de grises.
 * <p>
 * La paleta es sólo para poder ver el gráfico: lo que se guarda en el PNG son los índices, que es lo que hay
 * en la ROM, de modo que la ida y vuelta no depende de haber acertado con los colores.
 */
public final class PaletteService {

    public static final int COLORS = 16;
    public static final int SIZE = COLORS * 2;

    private static final int CHANNEL_MAX = 7;

    /** Colores no negros que ha de tener una paleta para no confundirla con un hueco de ceros. */
    private static final int MIN_REAL_COLORS = 4;

    private PaletteService() {}

    /** Rampa de grises, que deja los 16 índices distinguibles a ojo. */
    public static int[] grayscale() {
        int[] palette = new int[COLORS];
        for (int i = 0; i < COLORS; i++) {
            int value = i * 17;
            palette[i] = 0xFF000000 | (value << 16) | (value << 8) | value;
        }
        return palette;
    }

    /** Lee una línea de CRAM de la ROM y la pasa a ARGB. */
    public static int[] readFromRom(byte[] fileData, int offset) {
        if (offset < 0 || offset + SIZE > fileData.length) {
            throw new IllegalArgumentException("La paleta de " + Integer.toHexString(offset) + " no cabe en la ROM");
        }
        int[] palette = new int[COLORS];
        for (int i = 0; i < COLORS; i++) {
            int high = fileData[offset + i * 2] & 0xFF;
            int low = fileData[offset + i * 2 + 1] & 0xFF;
            int blue = (high >> 1) & CHANNEL_MAX;
            int green = (low >> 5) & CHANNEL_MAX;
            int red = (low >> 1) & CHANNEL_MAX;
            palette[i] = 0xFF000000 | (scale(red) << 16) | (scale(green) << 8) | scale(blue);
        }
        return palette;
    }

    private static int scale(int channel) {
        return Math.round(channel * 255f / CHANNEL_MAX);
    }

    /**
     * La paleta con la que se dibuja un bloque: la de la configuración si la hay, si no la que se haya
     * encontrado en la ROM, y si tampoco, grises.
     */
    public static int[] forBlock(int blockAddress, byte[] fileData, Map<Integer, Integer> detected) {
        Integer paletteAddress = App.config.palettes().get(blockAddress);
        if (paletteAddress == null) {
            paletteAddress = detected.get(blockAddress);
        }
        if (paletteAddress == null) {
            return grayscale();
        }
        return readFromRom(fileData, paletteAddress);
    }

    /**
     * Busca en la ROM parejas de punteros seguidos donde el primero apunta a una paleta con formato CRAM y
     * el segundo al principio de uno de los bloques comprimidos. Devuelve qué paleta le toca a cada bloque.
     */
    public static Map<Integer, Integer> detect(byte[] fileData, Collection<Integer> blockAddresses) {
        Set<Integer> blocks = new HashSet<>(blockAddresses);
        Map<Integer, Integer> found = new HashMap<>();
        for (int at = 0; at + 8 <= fileData.length; at += 2) {
            int block = readInt(fileData, at + 4);
            if (!blocks.contains(block) || found.containsKey(block)) {
                continue;
            }
            int palette = readInt(fileData, at);
            if (isCram(fileData, palette)) {
                found.put(block, palette);
            }
        }
        return found;
    }

    /** Una línea de CRAM son 16 palabras 0000 BBB0 GGG0 RRR0, o sea sin ningún bit fuera de sitio. */
    private static boolean isCram(byte[] fileData, int at) {
        if (at < 0 || at % 2 != 0 || at + SIZE > fileData.length) {
            return false;
        }
        int colors = 0;
        for (int i = 0; i < COLORS; i++) {
            int word = ((fileData[at + i * 2] & 0xFF) << 8) | (fileData[at + i * 2 + 1] & 0xFF);
            if ((word & 0xF111) != 0) {
                return false;
            }
            if (word != 0) {
                colors++;
            }
        }
        return colors >= MIN_REAL_COLORS;
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

}
