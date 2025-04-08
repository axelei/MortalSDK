package net.krusher.mortalsdk;

public record Texticle(int address, int size, String text) {

    public static final int PAD = 8;
    public static final String FORMAT = "%0" + PAD + "d";

    public String format() {
        return String.format(FORMAT, address) + "#" + String.format(FORMAT, size) + "#" + text;
    }

}
