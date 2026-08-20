package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SampleCli {

    private SampleCli() {}

    static void run(String[] args) throws IOException {
        if (args.length < 2) {
            throw usage("Falta la operacion de samples");
        }
        switch (args[1]) {
            case "list" -> list(args);
            case "extract" -> extract(args);
            case "replace" -> replace(args);
            default -> throw usage("Operacion de samples desconocida: " + args[1]);
        }
    }

    private static void list(String[] args) throws IOException {
        requireLength(args, 4, "sample list ROM CONFIG");
        byte[] rom = Files.readAllBytes(new File(args[2]).toPath());
        Config config = Config.getInstance(args[3]);
        List<SampleTableService.SampleEntry> entries = SampleTableService.readTable(
                rom, config.sampleTableOffset(), config.sampleCount());
        Log.pnl("ID  OFFSET  LONGITUD  FLAGS  ESTADO");
        for (var entry : entries) {
            String state = entry.length() == 0 ? "vacia" : entry.isInside(rom.length) ? "valida" : "fuera de ROM";
            Log.pnl(String.format("%02X  %06X  %8d  %04X   %s", entry.id(), entry.offset(),
                    entry.length(), entry.flags(), state));
        }
    }

    private static void extract(String[] args) throws IOException {
        requireLength(args, 5, "sample extract ROM DIRECTORIO CONFIG");
        byte[] rom = Files.readAllBytes(new File(args[2]).toPath());
        File outputDirectory = new File(args[3]);
        Config config = Config.getInstance(args[4]);
        SampleTableService.exportSamples(rom, outputDirectory, config);
        Log.pnl("Samples extraidos en: " + outputDirectory.getAbsolutePath());
    }

    private static void replace(String[] args) throws IOException {
        if (args.length < 7 || (args.length - 5) % 2 != 0) {
            throw usage("Uso: sample replace ROM SALIDA CONFIG ID WAV [ID WAV ...]");
        }
        File input = new File(args[2]);
        File output = new File(args[3]);
        if (input.getCanonicalFile().equals(output.getCanonicalFile())) {
            throw new IOException("La ROM de salida debe ser distinta de la ROM de entrada");
        }
        Config config = Config.getInstance(args[4]);
        Map<Integer, byte[]> replacements = new LinkedHashMap<>();
        for (int i = 5; i < args.length; i += 2) {
            int id = parseId(args[i]);
            if (replacements.containsKey(id)) {
                throw new IOException(String.format("El ID %02X aparece mas de una vez", id));
            }
            replacements.put(id, WavService.readSigned8BitMono(new File(args[i + 1]), config.pcmSampleRate()));
        }

        byte[] patched = Files.readAllBytes(input.toPath());
        List<SampleTableService.ReplacementResult> results = SampleTableService.applyReplacements(
                patched, config, replacements);
        Checksum.fixChecksum(patched);
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(output.toPath(), patched);
        for (var result : results) {
            Log.pnl(String.format("ID %02X: %06X/%d -> %06X/%d", result.id(), result.oldOffset(),
                    result.oldLength(), result.newOffset(), result.newLength()));
        }
        Log.pnl("ROM generada: " + output.getAbsolutePath());
    }

    private static int parseId(String value) throws IOException {
        String digits = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        try {
            int id = Integer.parseInt(digits, 16);
            if (id < 0 || id > 0xff) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException error) {
            throw new IOException("ID de sample no valido: " + value + " (usa hexadecimal, por ejemplo 0x1A)");
        }
    }

    private static void requireLength(String[] args, int expected, String syntax) throws IOException {
        if (args.length != expected) {
            throw usage("Uso: " + syntax);
        }
    }

    private static IOException usage(String message) {
        return new IOException(message);
    }
}
