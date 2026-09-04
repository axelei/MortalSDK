package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class BlockService {

    private BlockService() {}

    /**
     * Busca los bloques RNC de la ROM y los deja descomprimidos en la carpeta "extracted".
     */
    public static void extractCompressedBlocks(byte[] fileData) throws IOException {
        List<RncService.Block> blocks = RncService.search(fileData);
        for (RncService.Block block : blocks) {
            Path target = Paths.get("extracted", "data_" + toHexStringPadded(block.address()) + ".bin");
            Files.write(target, block.data());
        }
        Log.pnl("Bloques comprimidos extraídos: {0}", blocks.size());
    }

    /**
     * Vuelve a comprimir los bloques de la carpeta "extracted" y los mete en la ROM. El hueco de cada uno es
     * el que ocupaba en la ROM original. Si el bloque recomprimido no cabe, se busca un puntero de tres bytes
     * a su dirección: sólo si existe se mueve al espacio libre y se corrige el puntero.
     */
    public static void injectCompressedBlocks(File[] extractedFiles, byte[] fileData, byte[] originalData) throws IOException {
        Map<Integer, Integer> room = new HashMap<>();
        for (RncService.Block block : RncService.search(originalData)) {
            room.put(block.address(), block.packedSize());
        }
        for (File extractedFile : extractedFiles) {
            String name = extractedFile.getName();
            if (!name.startsWith("data_")) {
                continue;
            }
            int address = parseAddress(name);
            Integer originalSize = room.get(address);
            if (Objects.isNull(originalSize)) {
                Log.pnl();
                Log.p("El bloque {0} no estaba comprimido en la ROM original, no se inyectará.", name);
                continue;
            }
            byte[] blockData = Files.readAllBytes(extractedFile.toPath());
            byte[] compressedData;
            try {
                compressedData = RncService.pack(blockData, RncService.METHOD_1);
            } catch (RncException e) {
                Log.pnl();
                Log.p("No se ha podido comprimir {0}: {1}", name, e.getMessage());
                continue;
            }
            Log.p(" " + name);

            if (originalSize >= compressedData.length) {
                System.arraycopy(compressedData, 0, fileData, address, compressedData.length);
                Arrays.fill(fileData, address + compressedData.length, address + originalSize, (byte) 0x00);
                continue;
            }

            Log.p(" Bloque comprimido {0} mayor que su hueco. ", name);
            Integer pointer = TexticleService.findPointerAddress(address, fileData);
            Integer newAddress = TexticleService.getNewAddress(compressedData.length);
            if (Objects.nonNull(pointer) && Objects.nonNull(newAddress)
                    && newAddress + compressedData.length <= fileData.length) {
                Log.pnl("Se inyectará en la dirección {0}.", toHexStringPadded(newAddress));
                System.arraycopy(compressedData, 0, fileData, newAddress, compressedData.length);
                TexticleService.writeThreeBytes(fileData, pointer, newAddress);
                Arrays.fill(fileData, address, address + originalSize, (byte) 0x00);
            } else {
                Log.pnl("No se inyectará.");
            }
        }
        Log.pnl();
    }

    /**
     * Inyecta los bloques sin comprimir de la carpeta "extracted".
     * <p>
     * No se inyectan los bloques borrados (los que ya no tienen fichero) ni los que no se han modificado
     * respecto a la ROM original. Estos bloques no se reubican: si uno no cabe en su hueco se avisa y se
     * deja como estaba, porque no tienen por qué ser direccionables por puntero.
     */
    public static void injectUncompressedBlocks(File[] extractedFiles, byte[] fileData, byte[] originalData,
                                                String extension, Set<Range> ranges) throws IOException {
        Map<Integer, Range> rangesByAddress = new HashMap<>();
        for (Range range : ranges) {
            rangesByAddress.put(range.getFrom(), range);
        }
        Set<Integer> found = new HashSet<>();
        for (File extractedFile : extractedFiles) {
            String name = extractedFile.getName();
            if (!name.startsWith(extension + "_")) {
                continue;
            }
            int address = parseAddress(name);
            found.add(address);
            byte[] blockData = Files.readAllBytes(extractedFile.toPath());
            Range range = rangesByAddress.get(address);
            int room = Objects.nonNull(range) ? range.size() : blockData.length;
            if (isUnmodified(blockData, originalData, address, room)) {
                continue;
            }
            if (blockData.length > room) {
                Log.pnl();
                Log.p("El bloque {0} ocupa {1} bytes, más que los {2} de su hueco, y no se puede reubicar. No se inyectará.",
                        name, blockData.length, room);
                continue;
            }
            Log.p(" " + name);
            System.arraycopy(blockData, 0, fileData, address, blockData.length);
            Arrays.fill(fileData, address + blockData.length, address + room, (byte) 0x00);
        }
        for (Range range : ranges) {
            if (!found.contains(range.getFrom())) {
                Log.pnl();
                Log.p("Bloque {0} borrado, no se inyectará.", toHexStringPadded(range.getFrom()));
            }
        }
    }

    private static boolean isUnmodified(byte[] blockData, byte[] originalData, int address, int room) {
        return blockData.length == room
                && Arrays.equals(blockData, 0, blockData.length, originalData, address, address + blockData.length);
    }

    private static int parseAddress(String fileName) {
        return Integer.parseInt(fileName.substring(fileName.lastIndexOf('_') + 1, fileName.lastIndexOf('.')), 16);
    }


    public static void extractUncompressedBlock(Set<Range> ranges, String extension, byte[] fileData) throws IOException {
        for (Range range : ranges) {
            int start = range.getFrom();
            int end = range.getTo();
            byte[] block = new byte[end - start + 1];
            System.arraycopy(fileData, start, block, 0, end - start + 1);
            String fileName = "extracted/" + extension + "_" + toHexStringPadded(start) + "." + extension;
            FileOutputStream fos = new FileOutputStream(fileName);
            fos.write(block);
            fos.close();
        }
    }

    public static String toHexStringPadded(int address) {
        return StringUtils.leftPad(Integer.toHexString(address), 6, '0');
    }

}
