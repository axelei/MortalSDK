package net.krusher.mortalsdk;

import java.io.File;
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

    public static final byte[] TEXTSCHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!'.,- ".getBytes(StandardCharsets.ISO_8859_1);
    public static final int MIN_CHARS = 4;
    public static final Set<ValueRange> RANGES = Set.of(
            ValueRange.of(0, 3187513)
            );

    public static final String RNC = "rnc_propack_x64.exe";

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
        execute(RNC, "e", file);
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
        for (File extractedFile : extractedFiles) {
            execute("rnc_propack_x64.exe", "p", "extracted\\" + extractedFile.getName(), "temp.bin");
            System.out.println("Inyectando: " + extractedFile.getName());
            String addressHex = extractedFile.getName().substring(5, 11);
            int addressDecimal = Integer.parseInt(addressHex, 16);
            byte[] compressedData = Files.readAllBytes(Paths.get("temp.bin"));
            System.arraycopy(compressedData, 0, fileData, addressDecimal, compressedData.length);
        }
        File tempFile = new File("temp.bin");
        if (tempFile.exists()) {
            tempFile.delete();
        }
        System.out.println("Inyectando textos...");
        List<Texticle> texticles = extractTexts(file);
        for (Texticle texticle : texticles) {
            System.arraycopy(texticle.text().getBytes(StandardCharsets.ISO_8859_1), 0, fileData, texticle.address(), texticle.size());
        }
        System.out.println("Inyección terminada, escribiendo salida...");
        File outputFile = new File(file + ".patched.bin");
        Files.write(outputFile.toPath(), fileData);
        System.out.println("Salida escrita en: " + outputFile.getAbsolutePath());
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
        for (ValueRange range : RANGES) {
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
        processBuilder.inheritIO();
        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.out.println("Error al ejecutar el comando: " + exitCode);
            System.exit(exitCode);
        } else {
            System.out.println("Comando ejecutado correctamente");
        }
    }
}
