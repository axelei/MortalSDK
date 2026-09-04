package net.krusher.mortalsdk;

/**
 * Una entrada de las tablas Huffman del método 1. Los nombres l1, l2 y l3 vienen del código original:
 * l1 es la frecuencia, l2 el enlace al siguiente nodo al construir el árbol y l3 el código ya generado.
 */
final class RncHuffman {

    int l1;
    int l2;
    int l3;
    int bitDepth;

    static RncHuffman[] newTable(int count) {
        RncHuffman[] table = new RncHuffman[count];
        for (int i = 0; i < count; i++) {
            table[i] = new RncHuffman();
        }
        return table;
    }

    static void clear(RncHuffman[] table) {
        for (RncHuffman entry : table) {
            entry.l1 = 0;
            entry.l2 = 0xFFFF;
            entry.l3 = 0;
            entry.bitDepth = 0;
        }
    }

    /** Da la vuelta a los {@code count} bits de menos peso de {@code value}. */
    static int inverseBits(int value, int count) {
        int result = 0;
        while (count-- > 0) {
            result <<= 1;
            result |= value & 1;
            value >>>= 1;
        }
        return result;
    }

    static int bitsCount(int value) {
        int count = 1;
        while ((value >>>= 1) != 0) {
            count++;
        }
        return count;
    }

    /** Genera los códigos canónicos a partir de las profundidades ya calculadas. */
    static void buildCodes(RncHuffman[] table, int count) {
        long value = 0;
        long div = 0x80000000L;
        int depth = 1;
        while (depth <= 16) {
            for (int i = 0; i < count; i++) {
                if (table[i].bitDepth == depth) {
                    table[i].l3 = inverseBits((int) (value / div), depth);
                    value = (value + div) & 0xFFFFFFFFL;
                }
            }
            depth++;
            div >>>= 1;
        }
    }

}
