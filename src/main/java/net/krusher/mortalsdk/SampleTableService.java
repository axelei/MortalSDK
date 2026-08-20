package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SampleTableService {

    private static final int ENTRY_SIZE = 8;

    private SampleTableService() {}

    public static void exportSamples(byte[] rom, File outputDirectory, Config config) throws IOException {
        if (config.sampleTableOffset() < 0 || config.sampleCount() == 0) {
            return;
        }
        Files.createDirectories(outputDirectory.toPath());
        for (SampleEntry entry : readTable(rom, config.sampleTableOffset(), config.sampleCount())) {
            if (entry.length() == 0 || !entry.isInside(rom.length)) {
                if (entry.length() > 0) {
                    Log.pnl("Muestra {0} ignorada: offset o longitud fuera de la ROM.",
                            String.format("%02X", entry.id()));
                }
                continue;
            }
            byte[] pcm = java.util.Arrays.copyOfRange(rom, entry.offset(), entry.offset() + entry.length());
            WavService.writeSigned8BitMono(pcm, config.pcmSampleRate(), new File(outputDirectory, entry.fileName()));
        }
    }

    public static void injectSamples(byte[] rom, File inputDirectory, Config config) throws IOException {
        if (!inputDirectory.isDirectory() || config.sampleTableOffset() < 0 || config.sampleCount() == 0) {
            return;
        }
        for (SampleEntry entry : readTable(rom, config.sampleTableOffset(), config.sampleCount())) {
            if (entry.length() == 0 || !entry.isInside(rom.length)) {
                continue;
            }
            File wav = new File(inputDirectory, entry.fileName());
            if (!wav.isFile()) {
                continue;
            }
            byte[] pcm = WavService.readSigned8BitMono(wav, config.pcmSampleRate());
            if (pcm.length != entry.length()) {
                throw new IOException(wav.getName() + " contiene " + pcm.length
                        + " muestras; la tabla exige " + entry.length());
            }
            System.arraycopy(pcm, 0, rom, entry.offset(), pcm.length);
        }
    }

    public static List<ReplacementResult> applyReplacements(byte[] rom, Config config,
                                                             Map<Integer, byte[]> replacements) throws IOException {
        List<SampleEntry> entries = readTable(rom, config.sampleTableOffset(), config.sampleCount());
        FreeSpaceAllocator allocator = new FreeSpaceAllocator(config.spaceRanges(), rom.length);
        Map<Integer, SampleEntry> byId = new HashMap<>();
        for (SampleEntry entry : entries) {
            if (byId.put(entry.id(), entry) != null) {
                throw new IOException(String.format("El ID de muestra %02X esta duplicado en la tabla", entry.id()));
            }
        }
        List<ReplacementResult> results = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> replacement : replacements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            SampleEntry entry = byId.get(replacement.getKey());
            if (entry == null || entry.length() == 0 || !entry.isInside(rom.length)) {
                throw new IOException("ID de muestra no válido: " + replacement.getKey());
            }
            byte[] pcm = replacement.getValue();
            if (pcm.length == 0 || pcm.length > 0xffff) {
                throw new IOException("La muestra " + String.format("%02X", entry.id())
                        + " debe tener entre 1 y 65535 bytes");
            }
            int destination = allocator.allocate(pcm.length);
            System.arraycopy(pcm, 0, rom, destination, pcm.length);
            writeThreeBytes(rom, entry.tablePosition() + 1, destination);
            Checksum.writeWord(rom, entry.tablePosition() + 4, pcm.length);
            results.add(new ReplacementResult(entry.id(), entry.offset(), destination, entry.length(), pcm.length));
        }
        return results;
    }

    static List<SampleEntry> readTable(byte[] rom, int tableOffset, int count) throws IOException {
        if (tableOffset < 0 || count < 0 || tableOffset + count * ENTRY_SIZE > rom.length) {
            throw new IOException("La tabla de muestras queda fuera de la ROM");
        }
        List<SampleEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int cursor = tableOffset + i * ENTRY_SIZE;
            int id = rom[cursor] & 0xff;
            int offset = (rom[cursor + 1] & 0xff) << 16
                    | (rom[cursor + 2] & 0xff) << 8
                    | rom[cursor + 3] & 0xff;
            int length = readUnsignedWord(rom, cursor + 4);
            int flags = readUnsignedWord(rom, cursor + 6);
            entries.add(new SampleEntry(flags, id, offset, length, cursor));
        }
        return entries;
    }

    private static int readUnsignedWord(byte[] data, int offset) {
        return (data[offset] & 0xff) << 8 | data[offset + 1] & 0xff;
    }

    private static void writeThreeBytes(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >> 16);
        data[offset + 1] = (byte) (value >> 8);
        data[offset + 2] = (byte) value;
    }

    record SampleEntry(int flags, int id, int offset, int length, int tablePosition) {
        boolean isInside(int romLength) {
            return offset >= 0 && (long) offset + length <= romLength;
        }

        String fileName() {
            return String.format("sample_%02x_%06x_%04x_%04x.wav", id, offset, length, flags);
        }
    }

    public record ReplacementResult(int id, int oldOffset, int newOffset, int oldLength, int newLength) {}

    private static final class FreeSpaceAllocator {
        private final List<Range> ranges;

        private FreeSpaceAllocator(java.util.Set<Range> configuredRanges, int romLength) throws IOException {
            ranges = configuredRanges.stream()
                    .map(range -> Range.of(range.getFrom(), range.getTo()))
                    .sorted(Comparator.comparingInt(Range::getFrom))
                    .toList();
            for (Range range : ranges) {
                if (range.getFrom() < 0 || range.getTo() < range.getFrom() || range.getTo() >= romLength
                        || range.getTo() > 0xffffff) {
                    throw new IOException(String.format("Rango de espacio libre fuera de la ROM: %06X-%06X",
                            range.getFrom(), range.getTo()));
                }
            }
            for (int i = 1; i < ranges.size(); i++) {
                if (ranges.get(i).getFrom() <= ranges.get(i - 1).getTo()) {
                    throw new IOException("Los rangos de espacio libre se solapan");
                }
            }
        }

        private int allocate(int size) throws IOException {
            for (Range range : ranges) {
                int start = (range.getFrom() + 1) & ~1;
                if ((long) start + size - 1 > range.getTo()) {
                    continue;
                }
                range.setFrom(start + size);
                return start;
            }
            throw new IOException("No queda un hueco configurado de " + size + " bytes para la muestra");
        }
    }
}
