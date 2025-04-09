package net.krusher.mortalsdk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.temporal.ValueRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MortalSDK by Krusher
 */
public class App {

    public static final byte[] TEXTSCHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!:'.,- ".getBytes(StandardCharsets.ISO_8859_1);
    public static final int MIN_CHARS = 4;
    public static final Set<ValueRange> TEXTS_RANGES = Set.of(
            ValueRange.of(0, 3187513)
            );

    public static final Set<ValueRange> SOUNDS = Set.of(
            ValueRange.of(2051263, 2063433) // Finish him
    );

    public static final String RNC_EXE = "rnc_propack_x64.exe";

    private static final int CHECKSUM_OFFSET = 398; // 256 + 142

    public static void main( String[] args ) throws IOException, InterruptedException {

        System.out.println("MortalSDK by Krusher - Programa bajo licencia GPL 3");

        //check parameters
        if (args.length != 2) {
            displayHelp();
            System.exit(1);
        }

        //check mode
        if (args[0].equals("x")) {
            extract(args[1]);
        } else if (args[0].equals("i")) {
            inject(args[1]);
        } else {
            displayHelp();
            System.exit(1);
        }

        System.exit(0);
    }

    public static void extract(String file) throws IOException, InterruptedException {
        System.out.println("Modo: Extraer");
        System.out.println("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        System.out.println("Extrayendo bloques...");
        execute(RNC_EXE, "e", file);
        System.out.println("Extrayendo datos sin comprimir...");
        extractUncompressedBlock(SOUNDS, "pcm", fileData);
        System.out.println("Extrayendo textos...");
        List<Texticle> texts = extractTexts(fileData);
        System.out.println("Extracción terminada, escribiendo salida...");
        writeTexts(texts, file);
        System.out.println("Salida escrita en: " + file + ".txt");
    }

    public static void inject(String file) throws IOException, InterruptedException {
        System.out.println("Modo: Inyectar");
        System.out.println("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        System.out.println("Inyectando bloques...");
        File extractedDir = new File("extracted");
        File[] extractedFiles = extractedDir.listFiles();
        if (extractedFiles == null || extractedFiles.length == 0) {
            System.out.println("No se encontraron archivos extraídos en la carpeta 'extracted'");
            System.exit(1);
        }
        System.out.print("Inyectando bloques comprimidos:");
        injectCompressedBlocks(extractedFiles, fileData);
        System.out.print("Inyectando bloques sin comprimir: ");
        injectUncompressedBlocks(extractedFiles, fileData, "pcm");
        System.out.println("Inyectando textos...");
        List<Texticle> texticles = extractTexts(file);
        for (Texticle texticle : texticles) {
            System.arraycopy(texticle.toAsciiBytes(), 0, fileData, texticle.address(), texticle.size());
        }
        System.out.println("Inyección terminada.");
        System.out.println("Arreglando checksum...");
        fixChecksum(fileData);
        System.out.println("Escribiendo salida...");
        File outputFile = new File(file + ".patched.bin");
        Files.write(outputFile.toPath(), fileData);
        System.out.println("Salida escrita en: " + outputFile.getAbsolutePath());
    }

    private static void injectCompressedBlocks(File[] extractedFiles, byte[] fileData) throws IOException, InterruptedException {
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().startsWith("data_")) {
                continue;
            }
            execute("rnc_propack_x64.exe", "p", "extracted\\" + extractedFile.getName(), "temp.bin");
            System.out.print(" " + extractedFile.getName());
            String addressHex = extractedFile.getName().substring(5, 11);
            int addressDecimal = Integer.parseInt(addressHex, 16);
            byte[] compressedData = Files.readAllBytes(Paths.get("temp.bin"));
            System.arraycopy(compressedData, 0, fileData, addressDecimal, compressedData.length);
        }
        File tempFile = new File("temp.bin");
        if (tempFile.exists()) {
            tempFile.delete();
        }
        System.out.println();
    }

    private static void injectUncompressedBlocks(File[] extractedFiles, byte[] fileData, String extension) throws IOException {
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().endsWith(extension)) {
                continue;
            }
            System.out.print(" " + extractedFile.getName());
            String addressHex = extractedFile.getName().substring(extension.length() + 1, extractedFile.getName().lastIndexOf('.'));
            int addressDecimal = Integer.parseInt(addressHex, 16);
            byte[] uncompressedData = Files.readAllBytes(Paths.get(extractedFile.getAbsolutePath()));
            System.arraycopy(uncompressedData, 0, fileData, addressDecimal, uncompressedData.length);
        }
        System.out.println();
    }

    private static void fixChecksum(byte[] romBytes) {

        int prev_cs = readWord(romBytes, CHECKSUM_OFFSET);
        System.out.printf("Checksum existente: 0x%04x%n", prev_cs);

        int checksum = calculateChecksum(romBytes);
        System.out.printf("Checksum válido: 0x%04x%n", checksum);

        if (prev_cs != checksum) {
            System.out.println("El checksum ha cambiado, arreglando...");

            writeWord(romBytes, CHECKSUM_OFFSET, checksum);
        } else {
            System.out.println("¡El checksum no ha cambiado!");
        }
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void writeWord(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static int calculateChecksum(byte[] rom) {
        int checksum = 0;
        for (int i = 512; i + 1 < rom.length; i += 2) {
            int word = ((rom[i] & 0xFF) << 8) | (rom[i + 1] & 0xFF);
            checksum = (checksum + word) & 0xFFFF;
        }
        return checksum;
    }

    public static List<Texticle> extractTexts(String file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file + ".txt"));
        List<Texticle> texticles = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("#");
            if (parts.length == 3) {
                int address = Integer.parseInt(parts[0]);
                int size = Integer.parseInt(parts[1]);
                String text = parts[2];
                if (text.length() > size) {
                    text = text.substring(0, size);
                }
                texticles.add(new Texticle(address, size, text));
            }
        }
        return texticles;
    }

    public static void extractUncompressedBlock(Set<ValueRange> ranges, String extension, byte[] fileData) throws IOException {
        for (ValueRange range : ranges) {
            int start = (int) range.getMinimum();
            int end = (int) range.getMaximum();
            byte[] block = new byte[end - start + 1];
            System.arraycopy(fileData, start, block, 0, end - start + 1);
            String fileName = "extracted/" + extension + "_" + Integer.toHexString(start) + "." + extension;
            FileOutputStream fos = new FileOutputStream(fileName);
            fos.write(block);
            fos.close();
        }
    }

    public static List<Texticle> extractTexts(byte[] fileData) {
        List<Texticle> texts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean inText = false;
        int length = 0;
        for (int i = 0; i < fileData.length; i++) {
            if (!inRange(i)) {
                continue;
            }
            if (isChar(fileData[i])) {
                inText = true;
                buffer.append((char) fileData[i]);
                length++;
            } else if (inText) {
                if (length > MIN_CHARS) {
                    texts.add(new Texticle(i - length, length, buffer.toString()));
                }
                length = 0;
                buffer = new StringBuilder();
            }
        }
        return texts;
    }

    public static boolean inRange(int i) {
        for (ValueRange range : TEXTS_RANGES) {
            if (range.isValidIntValue(i)) {
                return true;
            }
        }
        return false;
    }

    public static void writeTexts(List<Texticle> texticles, String file) throws IOException {
        File outputFile = new File(file + ".txt");
        FileWriter fileWriter = new FileWriter(outputFile);
        PrintWriter printWriter = new PrintWriter(fileWriter);

        for (Texticle texticle : texticles) {
            printWriter.println(texticle.format());
        }

        printWriter.close();
    }

    private static boolean isChar(byte fileDatum) {
        for (byte theChar : TEXTSCHARS) {
            if (fileDatum == theChar) {
                return true;
            }
        }
        return false;
    }

    public static void displayHelp() {
        System.out.println("Debe especificarse modo y archivo");
        System.out.println("Ejemplos: x \"rom a extraer.bin\"");
        System.out.println("          i \"rom a inyectar.bin\"");
    }

    public static void execute(String... parameters) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(parameters);
        processBuilder
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.out.println("Error al ejecutar el comando: " + exitCode);
            System.exit(exitCode);
        }
    }
}
