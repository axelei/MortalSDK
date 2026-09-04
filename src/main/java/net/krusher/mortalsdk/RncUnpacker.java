package net.krusher.mortalsdk;

import java.io.ByteArrayOutputStream;

/**
 * Descompresor RNC ProPack, métodos 1 y 2.
 * <p>
 * Los búferes intermedios se dejan con dos bytes de más porque el código original lee alguno por delante
 * de la posición actual para rellenar el acumulador de bits.
 */
final class RncUnpacker {

    private static final int BUFFER_SIZE = 0x10002;
    private static final int REFILL_AT = 0xFFFD;
    private static final int WINDOW_END = 0xFFFF;

    private final byte[] input;
    private final int base;
    private final int fileSize;

    private final byte[] mem1 = new byte[BUFFER_SIZE];
    private final byte[] decoded = new byte[BUFFER_SIZE];
    private final RncHuffman[] rawTable = RncHuffman.newTable(16);
    private final RncHuffman[] posTable = RncHuffman.newTable(16);
    private final RncHuffman[] lenTable = RncHuffman.newTable(16);

    private int dictSize = RncService.DICT_SIZE_M1;
    private int method;
    private int unpackedSize;
    private int packedSize;
    private int unpackedCrc;
    private int packedCrc;
    private int encKey;

    private int blockStart = REFILL_AT;
    private int inputOffset;
    private int window;
    private int unpackedCrcReal;
    private int bitCount;
    private int bitBuffer;
    private int processedSize;
    private int matchCount;
    private int matchOffset;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    RncUnpacker(byte[] input, int base) {
        this.input = input;
        this.base = base;
        this.fileSize = input.length - base;
    }

    byte[] unpack() throws RncException {
        if (base < 0 || fileSize < RncService.HEADER_SIZE) {
            throw new RncException("El bloque no llega ni a la cabecera RNC");
        }
        readHeader();
        window = dictSize;
        if (readBit() != 0) {
            throw new RncException("El bloque está bloqueado");
        }
        if (readBit() != 0 && encKey == 0) {
            throw new RncException("El bloque está cifrado y hace falta la clave");
        }
        if (method == RncService.METHOD_1) {
            unpackMethod1();
        } else {
            unpackMethod2();
        }
        output.write(decoded, dictSize, window - dictSize);
        if (unpackedCrc != unpackedCrcReal) {
            throw new RncException("El CRC de los datos descomprimidos no cuadra");
        }
        return output.toByteArray();
    }

    private void readHeader() throws RncException {
        int sign = (readByte(0) << 16) | (readByte(1) << 8) | readByte(2);
        if (sign != RncService.SIGNATURE) {
            throw new RncException("No hay firma RNC");
        }
        method = readByte(3) & 3;
        if (method != RncService.METHOD_1 && method != RncService.METHOD_2) {
            throw new RncException("Método RNC desconocido: " + method);
        }
        // Al descomprimir, la herramienta original recorta el diccionario antes de leer la cabecera, así que
        // se queda en el del método 1 aunque el bloque sea del método 2.
        dictSize = RncService.DICT_SIZE_M1;
        unpackedSize = readInt(4);
        packedSize = readInt(8);
        if (packedSize < 0 || fileSize - RncService.HEADER_SIZE < packedSize) {
            throw new RncException("El tamaño comprimido no cabe en el fichero");
        }
        unpackedCrc = readByte(12) << 8 | readByte(13);
        packedCrc = readByte(14) << 8 | readByte(15);
        if (RncService.crcBlock(input, base + RncService.HEADER_SIZE, packedSize) != packedCrc) {
            throw new RncException("El CRC de los datos comprimidos no cuadra");
        }
        inputOffset = RncService.HEADER_SIZE;
    }

    private int readByte(int at) {
        return input[base + at] & 0xFF;
    }

    private int readInt(int at) {
        return (readByte(at) << 24) | (readByte(at + 1) << 16) | (readByte(at + 2) << 8) | readByte(at + 3);
    }

    private int readSourceByte() {
        if (blockStart == REFILL_AT) {
            int leftSize = fileSize - inputOffset;
            int sizeToRead = Math.min(leftSize, REFILL_AT);
            blockStart = 0;
            System.arraycopy(input, base + inputOffset, mem1, 0, sizeToRead);
            inputOffset += sizeToRead;
            int lookAhead = leftSize - sizeToRead > 2 ? 2 : leftSize - sizeToRead;
            System.arraycopy(input, base + inputOffset, mem1, sizeToRead, lookAhead);
        }
        return mem1[blockStart++] & 0xFF;
    }

    private int readBit() {
        return method == RncService.METHOD_2 ? inputBitsM2(1) : inputBitsM1(1);
    }

    private int inputBitsM1(int count) {
        int bits = 0;
        int prevBits = 1;
        while (count-- > 0) {
            if (bitCount == 0) {
                int b1 = readSourceByte();
                int b2 = readSourceByte();
                bitBuffer = ((mem1[blockStart + 1] & 0xFF) << 24) | ((mem1[blockStart] & 0xFF) << 16)
                        | (b2 << 8) | b1;
                bitCount = 16;
            }
            if ((bitBuffer & 1) != 0) {
                bits |= prevBits;
            }
            bitBuffer >>>= 1;
            prevBits <<= 1;
            bitCount--;
        }
        return bits;
    }

    private int inputBitsM2(int count) {
        int bits = 0;
        while (count-- > 0) {
            if (bitCount == 0) {
                bitBuffer = readSourceByte();
                bitCount = 8;
            }
            bits <<= 1;
            if ((bitBuffer & 0x80) != 0) {
                bits |= 1;
            }
            bitBuffer = (bitBuffer << 1) & 0xFFFFFFFF;
            bitCount--;
        }
        return bits;
    }

    private void writeDecodedByte(int b) {
        if (window == WINDOW_END) {
            output.write(decoded, dictSize, WINDOW_END - dictSize);
            System.arraycopy(decoded, window - dictSize, decoded, 0, dictSize);
            window = dictSize;
        }
        decoded[window++] = (byte) b;
        unpackedCrcReal = RncService.updateCrc(unpackedCrcReal, b);
    }

    private static void rorKey(RncUnpacker v) {
        v.encKey = (v.encKey & 1) != 0 ? 0x8000 | (v.encKey >>> 1) : v.encKey >>> 1;
    }

    // ---------------------------------------------------------------- método 1

    private void makeHuffmanTable(RncHuffman[] table) {
        RncHuffman.clear(table);
        int leafNodes = inputBitsM1(5);
        if (leafNodes == 0) {
            return;
        }
        if (leafNodes > 16) {
            leafNodes = 16;
        }
        for (int i = 0; i < leafNodes; i++) {
            table[i].bitDepth = inputBitsM1(4);
        }
        RncHuffman.buildCodes(table, leafNodes);
    }

    private int decodeTableData(RncHuffman[] table) throws RncException {
        for (int i = 0; i < table.length; i++) {
            if (table[i].bitDepth != 0 && table[i].l3 == (bitBuffer & ((1 << table[i].bitDepth) - 1))) {
                inputBitsM1(table[i].bitDepth);
                if (i < 2) {
                    return i;
                }
                return inputBitsM1(i - 1) | (1 << (i - 1));
            }
        }
        throw new RncException("Código Huffman no encontrado en la tabla");
    }

    private void unpackMethod1() throws RncException {
        while (processedSize < unpackedSize) {
            makeHuffmanTable(rawTable);
            makeHuffmanTable(lenTable);
            makeHuffmanTable(posTable);

            int subChunks = inputBitsM1(16);
            while (subChunks-- > 0) {
                int dataLength = decodeTableData(rawTable);
                processedSize += dataLength;
                if (dataLength != 0) {
                    while (dataLength-- > 0) {
                        writeDecodedByte((encKey ^ readSourceByte()) & 0xFF);
                    }
                    rorKey(this);
                    bitBuffer = ((((mem1[blockStart + 2] & 0xFF) << 16) | ((mem1[blockStart + 1] & 0xFF) << 8)
                            | (mem1[blockStart] & 0xFF)) << bitCount) | (bitBuffer & ((1 << bitCount) - 1));
                }
                if (subChunks != 0) {
                    matchOffset = decodeTableData(lenTable) + 1;
                    matchCount = decodeTableData(posTable) + 2;
                    processedSize += matchCount;
                    while (matchCount-- > 0) {
                        writeDecodedByte(decoded[window - matchOffset] & 0xFF);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- método 2

    private void decodeMatchCount() {
        matchCount = inputBitsM2(1) + 4;
        if (inputBitsM2(1) != 0) {
            matchCount = ((matchCount - 1) << 1) + inputBitsM2(1);
        }
    }

    private void decodeMatchOffset() {
        matchOffset = 0;
        if (inputBitsM2(1) != 0) {
            matchOffset = inputBitsM2(1);
            if (inputBitsM2(1) != 0) {
                matchOffset = ((matchOffset << 1) | inputBitsM2(1)) | 4;
                if (inputBitsM2(1) == 0) {
                    matchOffset = (matchOffset << 1) | inputBitsM2(1);
                }
            } else if (matchOffset == 0) {
                matchOffset = inputBitsM2(1) + 2;
            }
        }
        matchOffset = ((matchOffset << 8) | readSourceByte()) + 1;
    }

    private void unpackMethod2() {
        while (processedSize < unpackedSize) {
            while (true) {
                if (inputBitsM2(1) == 0) {
                    writeDecodedByte((encKey ^ readSourceByte()) & 0xFF);
                    rorKey(this);
                    processedSize++;
                    continue;
                }
                if (inputBitsM2(1) != 0) {
                    if (inputBitsM2(1) != 0) {
                        if (inputBitsM2(1) != 0) {
                            matchCount = readSourceByte() + 8;
                            if (matchCount == 8) {
                                inputBitsM2(1);
                                break;
                            }
                        } else {
                            matchCount = 3;
                        }
                        decodeMatchOffset();
                    } else {
                        matchCount = 2;
                        matchOffset = readSourceByte() + 1;
                    }
                    processedSize += matchCount;
                    while (matchCount-- > 0) {
                        writeDecodedByte(decoded[window - matchOffset] & 0xFF);
                    }
                } else {
                    decodeMatchCount();
                    if (matchCount != 9) {
                        decodeMatchOffset();
                        processedSize += matchCount;
                        while (matchCount-- > 0) {
                            writeDecodedByte(decoded[window - matchOffset] & 0xFF);
                        }
                    } else {
                        int dataLength = (inputBitsM2(4) << 2) + 12;
                        processedSize += dataLength;
                        while (dataLength-- > 0) {
                            writeDecodedByte((encKey ^ readSourceByte()) & 0xFF);
                        }
                        rorKey(this);
                    }
                }
            }
        }
    }

}
