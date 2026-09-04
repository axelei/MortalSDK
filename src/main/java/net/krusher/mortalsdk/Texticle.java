package net.krusher.mortalsdk;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Un "texticle" es un texto de la ROM: dónde está, cuánto ocupa, qué dice y desde dónde se le apunta.
 * <p>
 * En el fichero de textos cada uno ocupa una línea con el formato
 * {@code direccion#tamaño#texto#puntero}. La dirección y el puntero van en hexadecimal, y el tamaño en
 * decimal, que es el número que de verdad importa al traducir: los caracteres que caben.
 */
public record Texticle(int address, int size, String text, Pointer pointer) {

    public static final byte ASCII_SPACE = 0x20;

    private static final String ADDRESS_FORMAT = "%06x";
    private static final String SIZE_FORMAT = "%04d";
    /**
     * Cómo se apunta a un texto desde el código.
     *
     * @param address dónde está el puntero
     * @param lea     si es un {@code lea (d16,PC)} del 68000 en vez de un puntero absoluto de tres bytes.
     *                En ese caso lo que se guarda no es la dirección del texto, sino la distancia hasta él,
     *                que es un entero con signo de 16 bits: el texto tiene que quedar cerca.
     */
    public record Pointer(int address, boolean lea) {

        public static final String ABSOLUTE_MARK = "abs";
        public static final String LEA_MARK = "lea";

        public String format() {
            return (lea ? LEA_MARK : ABSOLUTE_MARK) + ":" + String.format(ADDRESS_FORMAT, address);
        }

        /** Lee un puntero del fichero de textos: {@code abs:xxxxxx} o {@code lea:xxxxxx}. */
        public static Pointer parse(String field) {
            String value = field.trim();
            if (value.isEmpty()) {
                return null;
            }
            int mark = value.indexOf(':');
            if (mark < 0) {
                throw new IllegalArgumentException("Puntero mal escrito, falta abs: o lea: -> " + value);
            }
            return new Pointer(Integer.parseInt(value.substring(mark + 1).trim(), 16),
                    value.substring(0, mark).trim().equals(LEA_MARK));
        }
    }

    public String format() {
        String pointerText = Objects.isNull(pointer) ? "" : "#" + pointer.format();
        return String.format(ADDRESS_FORMAT, address) + "#" + String.format(SIZE_FORMAT, size)
                + "#" + text + pointerText;
    }

    public static int parseAddress(String field) {
        return Integer.parseInt(field.trim(), 16);
    }

    public byte[] toAsciiBytes() {
        byte[] result = text.getBytes(StandardCharsets.ISO_8859_1);
        if (size != result.length) {
            Log.pnl("Alerta: El texto leído \"" + text + "\" tiene " + result.length
                    + " caracteres, pero el texto original tenía " + size
                    + " caracteres. Se cortará o se rellenará con espacios.");
            if (result.length < size) {
                int padding = result.length;
                result = Arrays.copyOf(result, size);
                Arrays.fill(result, padding, size, ASCII_SPACE);
            } else {
                result = Arrays.copyOfRange(result, 0, size);
            }
        }
        return result;
    }

}
