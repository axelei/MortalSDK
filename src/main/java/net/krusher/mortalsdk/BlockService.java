package net.krusher.mortalsdk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.temporal.ValueRange;
import java.util.Set;

public class BlockService {

    private BlockService() {}

    public static void injectCompressedBlocks(File[] extractedFiles, byte[] fileData) throws IOException, InterruptedException {
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().startsWith("data_")) {
                continue;
            }
            execute(App.config.proPackExe(), "p", "extracted\\" + extractedFile.getName(), "temp.bin");
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

    public static void injectUncompressedBlocks(File[] extractedFiles, byte[] fileData, String extension) throws IOException {
        for (File extractedFile : extractedFiles) {
            if (!extractedFile.getName().startsWith(extension)) {
                continue;
            }
            Log.p(" " + extractedFile.getName());
            String addressHex = extractedFile.getName().substring(extractedFile.getName().lastIndexOf('_') + 1, extractedFile.getName().lastIndexOf('.'));
            int addressDecimal = Integer.parseInt(addressHex, 16);
            byte[] uncompressedData = Files.readAllBytes(Paths.get(extractedFile.getAbsolutePath()));
            System.arraycopy(uncompressedData, 0, fileData, addressDecimal, uncompressedData.length);
        }
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
