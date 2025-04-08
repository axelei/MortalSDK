package net.krusher.mortalsdk;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record Texticle(int address, int size, String text) {

    public static final int PAD = 8;
    public static final String FORMAT = "%0" + PAD + "d";

    public static final byte ASCII_SPACE = 0x20;

    public String format() {
        return String.format(FORMAT, address) + "#" + String.format(FORMAT, size) + "#" + text;
    }

    public byte[] toAsciiBytes() {
        byte[] result = text.getBytes(StandardCharsets.ISO_8859_1);
        if (size != result.length) {
            System.out.println("Alerta: El texto leído \"" + text + "\" tiene " + result.length + " caracteres, pero el texto original tenía " + size + " caracteres. Se cortará o se rellenará con espacios.");
            if (result.length < size) {
                int padding = size - result.length;
                result = Arrays.copyOf(result, size);
                Arrays.fill(result, padding, size, ASCII_SPACE);
            } else {
                result = Arrays.copyOfRange(result, 0, size);
            }
        }
        return result;
    }

}
