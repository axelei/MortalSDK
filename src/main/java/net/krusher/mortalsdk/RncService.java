package net.krusher.mortalsdk;

import java.util.ArrayList;
import java.util.List;

/**
 * Compresión y descompresión RNC ProPack, portado del código de Rob Northen que publicó Lab 313
 * (https://github.com/lab313ru/rnc_propack_source). Sustituye a {@code rnc_propack_x64.exe}.
 * <p>
 * Se admiten los dos métodos: el 1 (Huffman + LZ77) y el 2 (LZ77 con códigos fijos).
 */
public final class RncService {

    public static final int HEADER_SIZE = 0x12;
    public static final int METHOD_1 = 1;
    public static final int METHOD_2 = 2;

    static final int SIGNATURE = 0x524E43; // "RNC"

    /** Tamaño del diccionario y máximo de coincidencias, según el método, tal y como los fija la herramienta. */
    static final int DICT_SIZE_M1 = 0x8000;
    static final int DICT_SIZE_M2 = 0x1000;
    static final int MAX_MATCHES_M1 = 0x1000;
    static final int MAX_MATCHES_M2 = 0xFF;

    static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
            CRC_TABLE[i] = crc;
        }
    }

    private RncService() {}

    /**
     * Un bloque RNC encontrado dentro de un fichero.
     *
     * @param address    dónde empieza el bloque
     * @param packedSize tamaño que ocupa comprimido, cabecera incluida
     * @param data       contenido ya descomprimido
     */
    public record Block(int address, int packedSize, byte[] data) {}

    static int crcBlock(byte[] data, int offset, int size) {
        int crc = 0;
        for (int i = 0; i < size; i++) {
            crc ^= data[offset + i] & 0xFF;
            crc = (crc >>> 8) ^ CRC_TABLE[crc & 0xFF];
        }
        return crc & 0xFFFF;
    }

    static int updateCrc(int crc, int b) {
        return (CRC_TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8)) & 0xFFFF;
    }

    /** ¿Hay una cabecera RNC en esta posición? */
    public static boolean isRncAt(byte[] data, int offset) {
        if (offset < 0 || offset + HEADER_SIZE > data.length) {
            return false;
        }
        int sign = ((data[offset] & 0xFF) << 16) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
        int method = data[offset + 3] & 3;
        return sign == SIGNATURE && (method == METHOD_1 || method == METHOD_2);
    }

    /**
     * Recorre el fichero buscando bloques RNC y los descomprime. Equivale al modo "e" de la herramienta
     * original: los bloques que se descomprimen bien se saltan enteros, y del resto se sigue byte a byte.
     */
    public static List<Block> search(byte[] data) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i + HEADER_SIZE < data.length) {
            if (!isRncAt(data, i)) {
                i++;
                continue;
            }
            try {
                byte[] unpacked = unpack(data, i);
                int packedSize = packedSizeAt(data, i) + HEADER_SIZE;
                blocks.add(new Block(i, packedSize, unpacked));
                i += packedSize;
            } catch (RncException e) {
                i++;
            }
        }
        return blocks;
    }

    static int packedSizeAt(byte[] data, int offset) {
        return ((data[offset + 8] & 0xFF) << 24) | ((data[offset + 9] & 0xFF) << 16)
                | ((data[offset + 10] & 0xFF) << 8) | (data[offset + 11] & 0xFF);
    }

    /** Descomprime el bloque RNC que empieza en {@code offset}. */
    public static byte[] unpack(byte[] input, int offset) throws RncException {
        return new RncUnpacker(input, offset).unpack();
    }

    public static byte[] unpack(byte[] input) throws RncException {
        return unpack(input, 0);
    }

    /** Comprime los datos en un fichero RNC completo, con su cabecera. */
    public static byte[] pack(byte[] input, int method) throws RncException {
        if (method != METHOD_1 && method != METHOD_2) {
            throw new RncException("Método RNC no válido: " + method);
        }
        return new RncPacker(input, method).pack();
    }

    public static byte[] pack(byte[] input) throws RncException {
        return pack(input, METHOD_1);
    }

}
