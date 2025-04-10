package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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

    private static final int CHECKSUM_OFFSET = 398; // 256 + 142

    private static Config config;

    public static void main( String[] args ) throws IOException, InterruptedException {

        Log.pnl("MortalSDK by Krusher - Programa bajo licencia GPL 3");

        //check parameters
        if (args.length < 2) {
            displayHelp();
            System.exit(1);
        }

        //parse config if exists
        if (args.length > 2) {
            config = Config.getInstance(args[2]);
        } else {
            config = new Config();
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
        Log.pnl("Modo: Extraer");
        Log.pnl("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        if (StringUtils.isNotBlank(config.proPackExe())) {
            Log.pnl("Extrayendo bloques...");
            execute(config.proPackExe(), "e", file);
        }
        Log.pnl("Extrayendo datos sin comprimir...");
        extractUncompressedBlock(config.sounds(), "pcm", fileData);
        Log.pnl("Extrayendo textos...");
        List<Texticle> texts = extractTexts(fileData);
        Log.pnl("Extracción terminada, escribiendo salida...");
        writeTexts(texts, file);
        Log.pnl("Salida escrita en: " + file + ".txt");
    }

    public static void inject(String file) throws IOException, InterruptedException {
        Log.pnl("Modo: Inyectar");
        Log.pnl("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        Log.pnl("Inyectando bloques...");
        File extractedDir = new File("extracted");
        File[] extractedFiles = extractedDir.listFiles();
        if (extractedFiles == null || extractedFiles.length == 0) {
            Log.pnl("No se encontraron archivos extraídos en la carpeta 'extracted'");
        } else {
            if (StringUtils.isNotBlank(config.proPackExe())) {
                Log.p("Inyectando bloques comprimidos:");
                injectCompressedBlocks(extractedFiles, fileData);
            }
            Log.p("Inyectando bloques sin comprimir: ");
            injectUncompressedBlocks(extractedFiles, fileData, "pcm");
        }
        Log.pnl("Inyectando textos...");
        List<Texticle> texticles = extractTexts(file);
        for (Texticle texticle : texticles) {
            System.arraycopy(texticle.toAsciiBytes(), 0, fileData, texticle.address(), texticle.size());
        }
        Log.pnl("Inyección terminada.");
        Log.pnl("Arreglando checksum...");
        fixChecksum(fileData);
        Log.pnl("Escribiendo salida...");
        File outputFile = new File(file + ".patched.bin");
        Files.write(outputFile.toPath(), fileData);
        Log.pnl("Salida escrita en: " + outputFile.getAbsolutePath());
    }

    private static void injectCompressedBlocks(File[] extractedFiles, byte[] fileData) throws IOException, InterruptedException {
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().startsWith("data_")) {
                continue;
            }
            execute(config.proPackExe(), "p", "extracted\\" + extractedFile.getName(), "temp.bin");
            Log.p(" " + extractedFile.getName());
            String addressHex = extractedFile.getName().substring(5, 11);
            int addressDecimal = Integer.parseInt(addressHex, 16);
            byte[] compressedData = Files.readAllBytes(Paths.get("temp.bin"));
            System.arraycopy(compressedData, 0, fileData, addressDecimal, compressedData.length);
        }
        File tempFile = new File("temp.bin");
        if (tempFile.exists()) {
            tempFile.delete();
        }
        Log.pnl();
    }

    private static void injectUncompressedBlocks(File[] extractedFiles, byte[] fileData, String extension) throws IOException {
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().endsWith(extension)) {
                continue;
            }
            Log.p(" " + extractedFile.getName());
            String addressHex = extractedFile.getName().substring(extension.length() + 1, extractedFile.getName().lastIndexOf('.'));
            int addressDecimal = Integer.parseInt(addressHex, 16);
            byte[] uncompressedData = Files.readAllBytes(Paths.get(extractedFile.getAbsolutePath()));
            System.arraycopy(uncompressedData, 0, fileData, addressDecimal, uncompressedData.length);
        }
        Log.pnl();
    }

    private static void fixChecksum(byte[] romBytes) {

        int previousChecksum = readWord(romBytes, CHECKSUM_OFFSET);
        Log.pf("Checksum existente: 0x%04x%n", previousChecksum);

        int checksum = calculateChecksum(romBytes);
        Log.pf("Checksum válido: 0x%04x%n", checksum);

        if (previousChecksum != checksum) {
            Log.pnl("El checksum ha cambiado, arreglando...");
            writeWord(romBytes, CHECKSUM_OFFSET, checksum);
        } else {
            Log.pnl("¡El checksum no ha cambiado!");
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
                if (length > config.minChars()) {
                    texts.add(new Texticle(i - length, length, buffer.toString()));
                }
                length = 0;
                buffer = new StringBuilder();
            }
        }
        return texts;
    }

    public static boolean inRange(int i) {
        if (config.textRanges().isEmpty()) {
            return true;
        }
        for (ValueRange range : config.textRanges()) {
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
        for (byte theChar : config.getTextCharsBytes()) {
            if (fileDatum == theChar) {
                return true;
            }
        }
        return false;
    }

    public static void displayHelp() {
        Log.pnl("Debe especificarse modo y archivo");
        Log.pnl("Ejemplos: x \"rom a extraer.bin\" [\"configuracion\"]");
        Log.pnl("          i \"rom a inyectar.bin\" [\"configuracion\"]");
        Log.pnl("Modo: x = extraer, i = inyectar");
        Log.pnl("Configuracion: Opcional, se puede dejar en blanco y se usará una por defecto.");
        Log.pnl("Ejemplos de configuracion en el directorio \"configs\".");
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
            Log.pnl("Error al ejecutar el comando: " + exitCode);
            System.exit(exitCode);
        }
    }
}
