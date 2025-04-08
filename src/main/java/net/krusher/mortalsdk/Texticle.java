package net.krusher.mortalsdk;

public record Texticle(int address, int size, String text) {

    public String format() {
        return address + "#" + size + "#" + text + "\t\t\t;" + text;
    }

}
