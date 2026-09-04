package net.krusher.mortalsdk;

import java.util.Arrays;

/**
 * Compresor RNC ProPack, métodos 1 y 2.
 * <p>
 * El original trabaja con punteros dentro de un búfer de 64 KB: aquí ese búfer es {@code mem1} y el puntero
 * es el índice {@code blockStart}. Las variables que en el original no tienen nombre (v7, v11, v17...)
 * conservan el suyo para poder seguir el código fuente al lado.
 */
final class RncPacker {

    private static final int MEM_SIZE = 0x10002;
    private static final int DICT_ENTRIES = 0x8000;
    private static final int TEMP_SIZE = 0x100000;
    private static final int CRC_DATA_SIZE = 2048;
    private static final int MAX_SUB_CHUNKS = 0xFFFE;
    private static final int PACK_BLOCK_SIZE = 0x3000;

    private static final int[] MATCH_COUNT_BITS = {0x00, 0x0E, 0x08, 0x0A, 0x12, 0x13, 0x16};
    private static final int[] MATCH_COUNT_BITS_COUNT = {0, 4, 4, 4, 5, 5, 5};
    private static final int[] MATCH_OFFSET_BITS =
            {0x00, 0x06, 0x08, 0x09, 0x15, 0x17, 0x1D, 0x1F, 0x28, 0x29, 0x2C, 0x2D, 0x38, 0x39, 0x3C, 0x3D};
    private static final int[] MATCH_OFFSET_BITS_COUNT = {1, 3, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6};

    private final byte[] input;
    private final int fileSize;
    private final int method;
    private final int dictSize;
    private final int maxMatches;

    private final byte[] output;
    private int outputOffset;
    private boolean overflow;

    private final byte[] mem1 = new byte[MEM_SIZE];
    private final int[] mem2 = new int[DICT_ENTRIES];
    private final int[] mem3 = new int[DICT_ENTRIES];
    private final int[] mem4 = new int[DICT_ENTRIES];
    private final int[] mem5 = new int[DICT_ENTRIES];
    private final byte[] temp = new byte[TEMP_SIZE];
    private final byte[] tmpCrcData = new byte[CRC_DATA_SIZE];

    private final RncHuffman[] rawTable = RncHuffman.newTable(16);
    private final RncHuffman[] posTable = RncHuffman.newTable(16);
    private final RncHuffman[] lenTable = RncHuffman.newTable(16);

    private int inputOffset;
    private int tempOffset;
    private int bytesLeft;
    private int packedSize;
    private int processedSize;
    private int v7;
    private int packBlockPos;
    private int packToken;
    private int bitCount;
    private int v11;
    private int lastMinOffset;
    private int v17;
    private int packBlockLeftSize;
    private int matchCount;
    private int matchOffset;
    private int v20;
    private int v21;
    private int unpackedCrc;
    private int packedCrc;
    private int leeway;
    private int chunksCount;
    private int encKey;

    private int blockStart;
    private int blockMax;
    private int blockEnd;

    RncPacker(byte[] input, int method) {
        this.input = input;
        this.fileSize = input.length;
        this.method = method;
        this.dictSize = method == RncService.METHOD_1 ? RncService.DICT_SIZE_M1 : RncService.DICT_SIZE_M2;
        this.maxMatches = method == RncService.METHOD_1 ? RncService.MAX_MATCHES_M1 : RncService.MAX_MATCHES_M2;
        this.output = new byte[fileSize + RncService.HEADER_SIZE + 2];
    }

    byte[] pack() throws RncException {
        if (fileSize <= RncService.HEADER_SIZE) {
            throw new RncException("El fichero es demasiado pequeño para comprimirlo");
        }
        if (RncService.isRncAt(input, 0)) {
            throw new RncException("El fichero ya está comprimido");
        }
        bytesLeft = fileSize;
        initDicts();

        writeInt((RncService.SIGNATURE << 8) | (method & 0xFF));
        writeInt(fileSize);
        writeInt(0);
        writeShort(0);
        writeShort(0);
        writeShort(0);

        writeBits(0, 1);                     // sin bloqueo
        writeBits(encKey != 0 ? 1 : 0, 1);   // sin clave

        if (method == RncService.METHOD_1) {
            compressMethod1();
        } else {
            compressMethod2();
        }

        for (int i = 0; i < v11; i++) {
            writeToOutput(tmpCrcData[i] & 0xFF);
        }
        v11 = 0;

        if (overflow) {
            throw new RncException("Los datos no se comprimen: ocuparían más que el original");
        }

        leeway = leeway > fileSize - packedSize ? leeway - (fileSize - packedSize) : 0;
        if (method == RncService.METHOD_2) {
            leeway += 2;
        }

        int totalSize = outputOffset;
        outputOffset = 8;
        writeInt(totalSize - RncService.HEADER_SIZE);
        writeShort(unpackedCrc);
        writeShort(packedCrc);
        writeByte(leeway);
        writeByte(chunksCount);

        return Arrays.copyOf(output, totalSize);
    }

    private void initDicts() {
        Arrays.fill(mem2, dictSize);
        Arrays.fill(mem3, dictSize);
        for (int i = 0; i < dictSize; i++) {
            mem5[i & 0x7FFF] = 0;
            mem4[i & 0x7FFF] = i;
        }
        lastMinOffset = 0;
    }

    // ---------------------------------------------------------------- salida

    private void writeByte(int b) {
        output[outputOffset++] = (byte) b;
    }

    private void writeShort(int value) {
        writeByte(value >>> 8);
        writeByte(value);
    }

    private void writeInt(int value) {
        writeShort(value >>> 16);
        writeShort(value & 0xFFFF);
    }

    private void writeToOutput(int b) {
        if (packedSize >= fileSize - RncService.HEADER_SIZE) {
            overflow = true;
            return;
        }
        output[outputOffset++] = (byte) b;
        packedCrc = RncService.updateCrc(packedCrc, b & 0xFF);
        packedSize++;
    }

    private int readFromInput() {
        int b = input[inputOffset++] & 0xFF;
        unpackedCrc = RncService.updateCrc(unpackedCrc, b);
        processedSize++;
        return b;
    }

    private void flushPending() {
        for (int i = 0; i < v11; i++) {
            writeToOutput(tmpCrcData[i] & 0xFF);
        }
        v11 = 0;
        if (processedSize > packedSize && processedSize - packedSize > leeway) {
            leeway = processedSize - packedSize;
        }
    }

    private void writeBitsM1(int value, int count) {
        while (count-- > 0) {
            packToken = (packToken & 0xFFFF) >>> 1;
            if ((value & 1) != 0) {
                packToken |= 0x8000;
            }
            value >>>= 1;
            bitCount++;
            if (bitCount == 16) {
                writeToOutput(packToken & 0xFF);
                writeToOutput((packToken >>> 8) & 0xFF);
                flushPending();
                bitCount = 0;
                packToken = 0;
            }
        }
    }

    private void writeBitsM2(int value, int count) {
        int mask = 1 << (count - 1);
        while (count-- > 0) {
            packToken = (packToken << 1) & 0xFFFF;
            if ((value & mask) != 0) {
                packToken++;
            }
            mask >>>= 1;
            bitCount++;
            if (bitCount == 8) {
                writeToOutput(packToken & 0xFF);
                flushPending();
                bitCount = 0;
                packToken = 0;
            }
        }
    }

    private void writeBits(int value, int count) {
        if (method == RncService.METHOD_2) {
            writeBitsM2(value, count);
        } else {
            writeBitsM1(value, count);
        }
    }

    private void updateTmpCrcData(int b) {
        if (bitCount != 0) {
            tmpCrcData[v11++] = (byte) b;
        } else {
            writeToOutput(b);
        }
    }

    private void rorKey() {
        encKey = (encKey & 1) != 0 ? 0x8000 | (encKey >>> 1) : encKey >>> 1;
    }

    // ---------------------------------------------------------------- búsqueda de coincidencias

    private int peekWord(int at) {
        return ((mem1[at] & 0xFF) << 8) | (mem1[at + 1] & 0xFF);
    }

    private void findMatches() {
        matchCount = 1;
        matchOffset = 0;

        int runLength = 1;
        while (runLength < blockEnd - blockStart && mem1[blockStart + runLength] == mem1[blockStart]) {
            runLength++;
        }

        int firstWord = peekWord(blockStart);
        int offset = mem2[firstWord & 0x7FFF];

        while (true) {
            if (offset == dictSize) {
                if (matchCount == 2 && matchOffset > 0x100) {
                    matchCount = 1;
                    matchOffset = 0;
                }
                break;
            }

            int restore = mem4[offset & 0x7FFF];
            int minOffset = lastMinOffset;
            if (minOffset <= offset) {
                minOffset = (minOffset + dictSize) & 0xFFFF;
            }
            minOffset = (minOffset - offset) & 0xFFFF;

            if (peekWord(blockStart - minOffset) == peekWord(blockStart)) {
                int maxCount = mem5[offset & 0x7FFF];

                if (maxCount <= minOffset) {
                    if (maxCount > runLength) {
                        minOffset = (minOffset - maxCount + runLength) & 0xFFFF;
                        maxCount = runLength & 0xFFFF;
                    }
                    int maxSize = blockEnd - blockStart;
                    if (maxCount == runLength) {
                        while (maxCount < maxSize && mem1[blockStart + maxCount] == mem1[blockStart + maxCount - minOffset]) {
                            maxCount++;
                        }
                    }
                } else {
                    minOffset = 1;
                    maxCount = runLength & 0xFFFF;
                }

                if (maxCount > maxMatches) {
                    maxCount = maxMatches;
                }
                if (maxCount > matchCount || (maxCount == matchCount && maxCount < maxMatches)) {
                    matchCount = maxCount;
                    matchOffset = minOffset;
                }
            }

            offset = restore;
        }
    }

    private void findAndCheckMatches() {
        findMatches();
        if (matchCount < 2 || blockMax - blockStart < 3) {
            return;
        }
        int count = matchCount;
        int offset = matchOffset;
        int minOffset = lastMinOffset;

        lastMinOffset = (lastMinOffset + 1) % dictSize;
        blockStart++;
        findMatches();
        blockStart--;
        lastMinOffset = minOffset;

        if (count < matchCount) {
            count = 1;
            offset = 0;
        }
        matchCount = count;
        matchOffset = offset;
    }

    private void updateBitsTable(RncHuffman[] table, int bits) {
        if (bits <= 1) {
            table[bits].l1++;
        } else {
            table[RncHuffman.bitsCount(bits)].l1++;
        }
        temp[tempOffset++] = (byte) (bits >>> 8);
        temp[tempOffset++] = (byte) bits;
    }

    private int readTempWord() {
        int value = ((temp[tempOffset] & 0xFF) << 8) | (temp[tempOffset + 1] & 0xFF);
        tempOffset += 2;
        return value;
    }

    /** Avanza {@code w} bytes por el bloque, manteniendo al día las cadenas del diccionario. */
    private void encodeMatches(int w) {
        while (true) {
            int restore = mem4[lastMinOffset & 0x7FFF];
            mem4[lastMinOffset & 0x7FFF] = dictSize;

            if (restore != lastMinOffset) {
                int bufferWord = peekWord(blockStart - dictSize);
                mem2[bufferWord & 0x7FFF] = restore;
                if (dictSize == restore) {
                    mem3[bufferWord & 0x7FFF] = dictSize;
                }
            }

            int bufferWord = peekWord(blockStart);
            if (mem2[bufferWord & 0x7FFF] == dictSize) {
                mem2[bufferWord & 0x7FFF] = lastMinOffset;
            } else {
                mem4[mem3[bufferWord & 0x7FFF] & 0x7FFF] = lastMinOffset;
            }
            mem3[bufferWord & 0x7FFF] = lastMinOffset;

            int count = 1;
            while (count < blockEnd - blockStart && mem1[blockStart + count] == mem1[blockStart]) {
                count++;
            }
            mem5[lastMinOffset & 0x7FFF] = count & 0xFFFF;

            while (true) {
                lastMinOffset = (lastMinOffset + 1) % dictSize;
                blockStart++;

                if (--w == 0) {
                    return;
                }
                if (--count <= 1) {
                    break;
                }
                mem5[lastMinOffset & 0x7FFF] = count & 0xFFFF;

                if (lastMinOffset != mem4[lastMinOffset & 0x7FFF]) {
                    restore = mem4[lastMinOffset & 0x7FFF];
                    mem4[lastMinOffset & 0x7FFF] = lastMinOffset;

                    bufferWord = peekWord(blockStart - dictSize);
                    mem2[bufferWord & 0x7FFF] = restore;
                    if (dictSize == restore) {
                        mem3[bufferWord & 0x7FFF] = dictSize;
                    }
                }
            }
        }
    }

    /** Analiza un trozo del fichero y deja en {@code temp} la lista de literales y coincidencias. */
    private void analyseChunk() {
        v17 = 0;
        packBlockLeftSize = PACK_BLOCK_SIZE;
        inputOffset = v7 + packBlockPos;
        tempOffset = 0;

        int dataLength = 0;

        while (bytesLeft != 0 || packBlockPos != 0) {
            int sizeToRead = (0xFFFF - dictSize - packBlockPos) & 0xFFFF;
            if (bytesLeft < sizeToRead) {
                sizeToRead = bytesLeft;
            }

            blockStart = dictSize;
            System.arraycopy(input, inputOffset, mem1, blockStart + packBlockPos, sizeToRead);
            inputOffset += sizeToRead;

            bytesLeft -= sizeToRead;
            packBlockPos += sizeToRead;

            blockMax = blockStart + packBlockPos;
            blockEnd = blockStart + packBlockPos;
            if (packBlockLeftSize < packBlockPos) {
                blockMax = blockStart + packBlockLeftSize;
            }

            while (blockStart < blockMax - 1 && v17 < MAX_SUB_CHUNKS) {
                findAndCheckMatches();

                if (matchCount >= 2) {
                    if (blockStart + matchCount <= blockMax) {
                        updateBitsTable(rawTable, dataLength);
                        updateBitsTable(posTable, matchCount - 2);
                        updateBitsTable(lenTable, matchOffset - 1);
                        encodeMatches(matchCount);
                        v17++;
                        dataLength = 0;
                    } else {
                        if (v17 != 0) {
                            break;
                        }
                        matchCount = blockMax - blockStart;
                    }
                } else {
                    encodeMatches(1);
                    dataLength++;
                }
            }

            packBlockPos = blockEnd - blockStart;
            System.arraycopy(mem1, blockStart - dictSize, mem1, 0, dictSize + packBlockPos);

            if (blockMax < blockEnd || (blockMax == blockEnd && bytesLeft == 0) || v17 == MAX_SUB_CHUNKS) {
                break;
            }
            packBlockLeftSize -= blockStart - dictSize;
        }

        if (blockMax == blockEnd && bytesLeft == 0 && v17 != MAX_SUB_CHUNKS) {
            dataLength += packBlockPos;
        }

        updateBitsTable(rawTable, dataLength);
        v17++;
        tempOffset = 0;
    }

    // ---------------------------------------------------------------- Huffman (método 1)

    private boolean findTwoSmallest(RncHuffman[] table, int count) {
        long d6 = 0xFFFFFFFFL;
        long d5 = 0xFFFFFFFFL;
        for (int i = 0; i < count; i++) {
            long l1 = table[i].l1 & 0xFFFFFFFFL;
            if (l1 == 0) {
                continue;
            }
            if (l1 < d5) {
                d6 = d5;
                v21 = v20;
                d5 = l1;
                v20 = i;
            } else if (l1 < d6) {
                d6 = l1;
                v21 = i;
            }
        }
        return d5 != 0xFFFFFFFFL && d6 != 0xFFFFFFFFL;
    }

    private void buildHuffman(RncHuffman[] table, int count) {
        int used = 0;
        int last = 0;
        for (int i = 0; i < count; i++) {
            if (table[i].l1 != 0) {
                used++;
                last = i;
            }
        }
        if (used == 0) {
            return;
        }
        if (used == 1) {
            table[last].bitDepth++;
            return;
        }

        while (findTwoSmallest(table, count)) {
            table[v20].l1 += table[v21].l1;
            table[v21].l1 = 0;
            table[v20].bitDepth++;

            while (table[v20].l2 != 0xFFFF) {
                v20 = table[v20].l2;
                table[v20].bitDepth++;
            }
            table[v20].l2 = v21;
            table[v21].bitDepth++;

            while (table[v21].l2 != 0xFFFF) {
                v21 = table[v21].l2;
                table[v21].bitDepth++;
            }
        }

        RncHuffman.buildCodes(table, count);
    }

    private void writeHuffmanTable(RncHuffman[] table, int count) {
        int cnt = count;
        while (cnt != 0 && table[--cnt].bitDepth == 0) {
            count--;
        }
        writeBitsM1(count, 5);
        for (int i = 0; i < count; i++) {
            writeBitsM1(table[i].bitDepth, 4);
        }
    }

    private void writeValue(RncHuffman[] table, int count) {
        int bits = count > 1 ? RncHuffman.bitsCount(count) : count;
        writeBitsM1(table[bits].l3, table[bits].bitDepth);
        if (bits > 1) {
            writeBitsM1(count - (1 << (bits - 1)), bits - 1);
        }
    }

    private void compressMethod1() {
        int srcOffset = 0;

        while (v7 < fileSize) {
            RncHuffman.clear(lenTable);
            RncHuffman.clear(posTable);
            RncHuffman.clear(rawTable);

            analyseChunk();
            inputOffset = srcOffset;

            buildHuffman(rawTable, rawTable.length);
            buildHuffman(lenTable, lenTable.length);
            buildHuffman(posTable, posTable.length);

            writeHuffmanTable(rawTable, rawTable.length);
            writeHuffmanTable(lenTable, lenTable.length);
            writeHuffmanTable(posTable, posTable.length);

            writeBitsM1(v17, 16);

            while (v17-- > 0) {
                int dataLength = readTempWord();
                v7 += dataLength;

                writeValue(rawTable, dataLength);

                if (dataLength != 0) {
                    while (dataLength-- > 0) {
                        int b = readFromInput();
                        if (bitCount == 0) {
                            writeToOutput((encKey ^ b) & 0xFF);
                        } else {
                            tmpCrcData[v11++] = (byte) ((encKey ^ b) & 0xFF);
                        }
                    }
                    rorKey();
                }

                if (v17 != 0) {
                    matchCount = readTempWord();
                    matchOffset = readTempWord();

                    writeValue(lenTable, matchOffset);
                    writeValue(posTable, matchCount);

                    matchCount += 2;
                    v7 += matchCount;
                    while (matchCount-- > 0) {
                        readFromInput();
                    }
                }
            }

            if (bitCount == 0) {
                for (int i = 0; i < v11; i++) {
                    writeToOutput(tmpCrcData[i] & 0xFF);
                }
                v11 = 0;
            }

            chunksCount++;
            srcOffset = inputOffset;
        }

        packToken = (packToken & 0xFFFF) >>> (16 - bitCount);
        if (bitCount != 0 || v11 != 0) {
            writeToOutput(packToken & 0xFF);
        }
        if (bitCount > 8 || v11 != 0) {
            writeToOutput((packToken >>> 8) & 0xFF);
        }
    }

    // ---------------------------------------------------------------- método 2

    private void encodeMatchesCount(int count) {
        while (count > 0) {
            if (count >= 12) {
                if ((count & 3) != 0) {
                    writeBitsM2(0, 1);
                    updateTmpCrcData((encKey ^ readFromInput()) & 0xFF);
                    count--;
                } else {
                    writeBitsM2(0x17, 5);
                    if (count >= 72) {
                        writeBitsM2(0xF, 4);
                        for (int i = 0; i < 72; i++) {
                            updateTmpCrcData((encKey ^ readFromInput()) & 0xFF);
                        }
                        count -= 72;
                    } else {
                        writeBitsM2((count - 12) >>> 2, 4);
                        while (count-- > 0) {
                            updateTmpCrcData((encKey ^ readFromInput()) & 0xFF);
                        }
                    }
                }
                rorKey();
            } else {
                while (count != 0) {
                    writeBitsM2(0, 1);
                    updateTmpCrcData((encKey ^ readFromInput()) & 0xFF);
                    rorKey();
                    count--;
                }
            }
        }
    }

    private void compressMethod2() {
        int srcOffset = 0;

        while (v7 < fileSize) {
            analyseChunk();
            inputOffset = srcOffset;

            while (v17-- > 0) {
                int dataLength = readTempWord();
                v7 += dataLength;

                encodeMatchesCount(dataLength);

                if (v17 != 0) {
                    matchCount = readTempWord();
                    matchOffset = readTempWord();

                    if (matchCount != 0) {
                        if (matchCount >= 7) {
                            writeBitsM2(0xF, 4);
                            updateTmpCrcData((matchCount - 6) & 0xFF);
                        } else {
                            writeBitsM2(MATCH_COUNT_BITS[matchCount], MATCH_COUNT_BITS_COUNT[matchCount]);
                        }
                        writeBitsM2(MATCH_OFFSET_BITS[matchOffset >>> 8], MATCH_OFFSET_BITS_COUNT[matchOffset >>> 8]);
                    } else {
                        writeBitsM2(6, 3);
                    }
                    updateTmpCrcData(matchOffset & 0xFF);

                    matchCount += 2;
                    v7 += matchCount;
                    while (matchCount-- > 0) {
                        readFromInput();
                    }
                }
            }

            writeBitsM2(0xF, 4);
            updateTmpCrcData(0);
            writeBitsM2(v7 >= fileSize ? 0 : 1, 1);

            if (bitCount == 0) {
                for (int i = 0; i < v11; i++) {
                    writeToOutput(tmpCrcData[i] & 0xFF);
                }
                v11 = 0;
            }

            chunksCount++;
            srcOffset = inputOffset;
        }

        packToken = (packToken << (8 - bitCount)) & 0xFFFF;
        if (bitCount != 0 || v11 != 0) {
            writeToOutput(packToken & 0xFF);
        }
    }

}
