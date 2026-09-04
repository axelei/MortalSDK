package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlockService {

    private BlockService() {}

    public static void extractCompressedBlocks(String file) throws IOException, InterruptedException {
        String output = BlockService.execute(App.config.proPackExe(), "e", file);
        File extractedFolder = new File("extracted");
        File[] extractedFiles = extractedFolder.listFiles();
        if (extractedFiles == null) {
            Log.pnl("No se encontraron archivos extraídos.");
            return;
        }
        List<String> sizes = new ArrayList<>();
        Map<Integer, Integer> originalSizes = new HashMap<>();
        for (String line : output.split("\n")) {
            if (line.contains("RNC archive found")) {
                String regex = "(0x[0-9a-f]+) \\(([0-9]+)/([0-9]+)";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(line);
                matcher.find();
                int address = Integer.parseInt(matcher.group(1).trim().substring(2), 16);
                int originalSize = Integer.parseInt(matcher.group(2).trim());
                originalSizes.put(address, originalSize);
            }
        }
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().startsWith("data_")) {
                continue;
            }
            sizes.add(extractedFile.getName() + "#" + extractedFile.length() + "#" + originalSizes.get(Integer.parseInt(extractedFile.getName().substring(5, 11), 16)));
        }
        printLogFile(sizes);
    }

    public static void injectCompressedBlocks(File[] extractedFiles, byte[] fileData) throws IOException, InterruptedException {
        Map<String, Integer> sizes = new HashMap<>();
        List<String> logLines = Files.readAllLines(Paths.get("extracted/log.txt"));
        for (String line : logLines) {
            String[] parts = line.split("#");
            String fileName = parts[0];
            int size = Integer.parseInt(parts[2]);
            sizes.put(fileName, size);
        }
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().startsWith("data_")) {
                continue;
            }
            execute(App.config.proPackExe(), "p", "extracted\\" + extractedFile.getName(), "temp.bin");
            Log.p(" " + extractedFile.getName());
            String addressHex = extractedFile.getName().substring(5, 11);
            int addressDecimal = Integer.parseInt(addressHex, 16);
            byte[] compressedData = Files.readAllBytes(Paths.get("temp.bin"));
            int originalSize = sizes.get(extractedFile.getName());
            if (originalSize >= compressedData.length) {
                System.arraycopy(compressedData, 0, fileData, addressDecimal, compressedData.length);
                if (originalSize > compressedData.length) {
                    byte[] padding = new byte[originalSize - compressedData.length];
                    Arrays.fill(padding, (byte) 0x00);
                    System.arraycopy(padding, 0, fileData, addressDecimal + compressedData.length, padding.length);
                }
                continue;
            }
            Log.p(" Bloque comprimido {0} mayor que su hueco. ", extractedFile.getName());
            int address = Integer.parseInt(extractedFile.getName().substring(extractedFile.getName().lastIndexOf('_') + 1, extractedFile.getName().lastIndexOf('.')), 16);
            Integer pointer = TexticleService.findPointerAddress(address, fileData);
            Integer newAddress = TexticleService.getNewAddress(compressedData.length);
            if (Objects.nonNull(pointer) && Objects.nonNull(newAddress)) {
                Log.pnl("Se inyectará en la dirección {0}.", toHexStringPadded(newAddress));
                System.arraycopy(compressedData, 0, fileData, newAddress, compressedData.length);
                TexticleService.writeThreeBytes(fileData, pointer, newAddress);
                byte[] padding = new byte[originalSize];
                Arrays.fill(padding, (byte) 0x00);
                System.arraycopy(padding, 0, fileData, addressDecimal, originalSize);
            } else {
                Log.pnl("No se inyectará.");
            }
        }
        File tempFile = new File("temp.bin");
        if (tempFile.exists()) {
            tempFile.delete();
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

    public static String execute(String... parameters) throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();
        ProcessBuilder processBuilder = new ProcessBuilder(parameters);
        processBuilder
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = processBuilder.start();
        String line;
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        while ((line = reader.readLine()) != null) {
            output.append(line).append(System.lineSeparator());
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            Log.pnl("Error al ejecutar el comando: " + exitCode);
            System.exit(exitCode);
        }
        return output.toString();
    }

    public static void printLogFile(List<String> list) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter("extracted/log.txt"));
        for (String line : list) {
            writer.write(line);
            writer.newLine();
        }
        writer.close();
    }

}
