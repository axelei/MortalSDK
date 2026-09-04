package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracción e inyección de los samples PCM a partir de la tabla que los describe dentro de la ROM.
 * <p>
 * La tabla no se configura: se busca en la ROM aprovechando que los identificadores de sus entradas son
 * correlativos y que sus direcciones y longitudes tienen que caber en la ROM. Cada sample se vuelca a un WAV
 * con la frecuencia a la que lo reproduce el juego, y al inyectar se acepta cualquier frecuencia porque el
 * campo de velocidad de la propia entrada se reescribe.
 */
public final class SampleService {

    private static final String PREFIX = "sample_";
    private static final String EXTENSION = ".wav";

    private static final int ENTRY_SIZE = 8;
    private static final int MIN_ENTRIES = 32;
    private static final int MAX_ENTRIES = 256;
    private static final int MIN_VALID_PERCENT = 90;
    private static final int MAX_LENGTH = 0xFFFF;

    /** El reproductor Z80 direcciona la ROM por una ventana de 32 KB, así que un sample no debería cruzarla. */
    private static final int BANK_SIZE = 0x8000;

    /**
     * El reproductor del Z80 lleva un acumulador de 8 bits al que suma la velocidad de la entrada en cada
     * vuelta de su bucle, y saca una muestra por el DAC cada vez que se desborda. Por tanto la frecuencia es
     * reloj * velocidad / (ciclosBucle * 256 + ciclosMuestra * velocidad).
     * <p>
     * El bucle son 152 ciclos, y sacar una muestra cuesta 72 más. Aquí se usan 176 y 83, que son esos mismos
     * ciclos contando las esperas del YM2612, calibrados para que la velocidad más habitual de la tabla
     * (0x6A) dé los 7040 Hz medidos en el juego.
     */
    private static final int Z80_CLOCK = 3579545;
    private static final int LOOP_CYCLES = 176;
    private static final int OUTPUT_CYCLES = 83;
    private static final int MIN_RATE = 1;
    private static final int MAX_RATE = 0xFF;

    private SampleService() {}

    public static int frequencyOf(int rate) {
        return (int) Math.round((double) Z80_CLOCK * rate
                / ((double) LOOP_CYCLES * 256 + (double) OUTPUT_CYCLES * rate));
    }

    /** Inversa de frequencyOf, recortada a lo que cabe en un byte. */
    public static int rateOf(int frequency) {
        double divisor = (double) Z80_CLOCK - (double) OUTPUT_CYCLES * frequency;
        if (divisor <= 0) {
            return MAX_RATE;
        }
        long rate = Math.round((double) frequency * LOOP_CYCLES * 256 / divisor);
        return (int) Math.min(MAX_RATE, Math.max(MIN_RATE, rate));
    }

    /**
     * Busca la tabla de samples: la tira más larga de entradas de ocho bytes cuyo identificador va 0, 1, 2...
     * y cuyas direcciones y longitudes caben en la ROM.
     */
    public static List<Sample> findTable(byte[] fileData) {
        List<Sample> best = List.of();
        for (int address = 0; address + ENTRY_SIZE * MIN_ENTRIES <= fileData.length; address += 2) {
            if (fileData[address] != 0 || fileData[address + ENTRY_SIZE] != 1) {
                continue;
            }
            List<Sample> table = readTable(fileData, address);
            if (table.size() > best.size()) {
                best = table;
            }
        }
        return best;
    }

    private static List<Sample> readTable(byte[] fileData, int address) {
        List<Sample> table = new ArrayList<>();
        int valid = 0;
        while (table.size() < MAX_ENTRIES && address + ENTRY_SIZE * (table.size() + 1) <= fileData.length) {
            int entryAddress = address + ENTRY_SIZE * table.size();
            if ((fileData[entryAddress] & 0xFF) != table.size()) {
                break;
            }
            Sample sample = new Sample(fileData[entryAddress] & 0xFF, entryAddress,
                    readThreeBytes(fileData, entryAddress + 1),
                    readWord(fileData, entryAddress + 4),
                    readWord(fileData, entryAddress + 6));
            if (sample.isEmpty() && !table.isEmpty() && isBlank(fileData, entryAddress)) {
                break;
            }
            if (sample.fitsInRom(fileData.length)) {
                valid++;
            }
            table.add(sample);
        }
        if (table.size() < MIN_ENTRIES || valid * 100 < table.size() * MIN_VALID_PERCENT) {
            return List.of();
        }
        return table;
    }

    private static boolean isBlank(byte[] fileData, int entryAddress) {
        for (int i = 0; i < ENTRY_SIZE; i++) {
            if (fileData[entryAddress + i] != 0) {
                return false;
            }
        }
        return true;
    }

    public static void extract(byte[] fileData) throws IOException {
        List<Sample> table = findTable(fileData);
        if (table.isEmpty()) {
            Log.pnl("No se ha encontrado la tabla de samples PCM, no se extraerá ningún sonido.");
            return;
        }
        Log.pnl("Tabla de samples en {0}, {1} entradas.", toHex(table.getFirst().entryAddress()), table.size());
        int written = 0;
        for (Sample sample : table) {
            if (sample.isEmpty()) {
                continue;
            }
            if (!sample.fitsInRom(fileData.length)) {
                Log.pnl("El sample {0} apunta fuera de la ROM ({1}, {2} bytes), no se extrae.",
                        sample.id(), toHex(sample.offset()), sample.length());
                continue;
            }
            byte[] pcm = Arrays.copyOfRange(fileData, sample.offset(), sample.offset() + sample.length());
            WavService.write(pcm, frequencyOf(sample.rate()), new File("extracted", sample.fileName()));
            written++;
        }
        Log.pnl("Extraidos {0} samples en la carpeta extracted.", written);
    }

    /**
     * Inyecta los WAV de la carpeta "extracted". Los samples borrados o sin cambios no se tocan; los que no
     * caben se llevan al espacio libre y se corrigen la dirección y la longitud de su entrada en la tabla.
     */
    public static void inject(File[] extractedFiles, byte[] fileData, byte[] originalData) throws IOException {
        List<Sample> table = findTable(originalData);
        if (table.isEmpty()) {
            Log.pnl("No se ha encontrado la tabla de samples PCM, no se inyectará ningún sonido.");
            return;
        }
        Map<Integer, Sample> byId = new HashMap<>();
        for (Sample sample : table) {
            byId.put(sample.id(), sample);
        }
        Set<Integer> found = new HashSet<>();
        for (File file : extractedFiles) {
            String name = file.getName();
            if (!name.startsWith(PREFIX) || !name.endsWith(EXTENSION)) {
                continue;
            }
            Integer id = parseId(name);
            if (Objects.isNull(id)) {
                Log.pnl("Nombre de sample no válido: {0}", name);
                continue;
            }
            Sample sample = byId.get(id);
            if (Objects.isNull(sample) || sample.isEmpty()) {
                Log.pnl("El sample {0} no está en la tabla de la ROM, se ignora {1}.", id, name);
                continue;
            }
            found.add(id);
            injectSample(sample, file, fileData, originalData, isAlone(sample, table));
        }
        List<String> deleted = new ArrayList<>();
        for (Sample sample : table) {
            if (!sample.isEmpty() && sample.fitsInRom(originalData.length) && !found.contains(sample.id())) {
                deleted.add(String.format("%02x", sample.id()));
            }
        }
        if (!deleted.isEmpty()) {
            Log.pnl();
            Log.p("Samples borrados, se dejan como estaban: {0}", String.join(", ", deleted));
        }
    }

    /**
     * Si los bytes de este sample no los comparte ningún otro de la tabla.
     * <p>
     * En esta ROM es de lo más normal que sí: hay entradas distintas que apuntan al mismo sitio y otras que
     * son un trozo de la de al lado. El hueco de uno así no se puede dar por libre aunque se mueva.
     */
    private static boolean isAlone(Sample sample, List<Sample> table) {
        for (Sample other : table) {
            if (other.entryAddress() == sample.entryAddress() || other.isEmpty()) {
                continue;
            }
            if (other.offset() < sample.offset() + sample.length()
                    && sample.offset() < other.offset() + other.length()) {
                return false;
            }
        }
        return true;
    }

    private static void injectSample(Sample sample, File file, byte[] fileData, byte[] originalData,
                                     boolean canFree) throws IOException {
        WavService.WavData wav = WavService.read(file);
        byte[] pcm = wav.pcm();
        int rate = rateOf(wav.sampleRate());
        if (isUnmodified(sample, pcm, rate, originalData)) {
            return;
        }
        if (pcm.length == 0 || pcm.length > MAX_LENGTH) {
            Log.pnl();
            Log.pnl("{0} tiene {1} bytes y la tabla solo admite de 1 a {2}, no se inyectará.",
                    file.getName(), pcm.length, MAX_LENGTH);
            return;
        }
        if (wav.sampleRate() != frequencyOf(rate)) {
            Log.pnl();
            Log.pnl("{0} se reproducirá a {1} Hz, lo más cercano a los {2} Hz del WAV.",
                    file.getName(), frequencyOf(rate), wav.sampleRate());
        }
        int offset = sample.offset();
        if (pcm.length > sample.length()) {
            Integer newOffset = TexticleService.getNewAddress(pcm.length, BANK_SIZE);
            if (Objects.isNull(newOffset) || newOffset + pcm.length > fileData.length) {
                Log.pnl();
                Log.pnl("{0} ocupa {1} bytes, más que los {2} de su hueco, y no hay espacio libre. No se inyectará.",
                        file.getName(), pcm.length, sample.length());
                return;
            }
            offset = newOffset;
            Log.pnl();
            Log.pnl("{0} ocupa {1} bytes, más que los {2} de su hueco: se mueve a {3}.",
                    file.getName(), pcm.length, sample.length(), toHex(offset));
            // los samples se apuntan unos a otros, así que el hueco sólo se suelta si era sólo suyo
            if (canFree) {
                TexticleService.freeSpace(sample.offset(), sample.length());
            }
        }
        // Los samples van pegados unos a otros, así que si el nuevo es más corto no se rellena el sobrante:
        // solo se acorta la longitud de la entrada.
        System.arraycopy(pcm, 0, fileData, offset, pcm.length);
        writeThreeBytes(fileData, sample.entryAddress() + 1, offset);
        writeWord(fileData, sample.entryAddress() + 4, pcm.length);
        writeWord(fileData, sample.entryAddress() + 6, rate);
        Log.p(" " + sample.fileName());
    }

    private static boolean isUnmodified(Sample sample, byte[] pcm, int rate, byte[] originalData) {
        return rate == sample.rate() && pcm.length == sample.length()
                && Arrays.equals(pcm, 0, pcm.length, originalData, sample.offset(), sample.offset() + pcm.length);
    }

    private static Integer parseId(String name) {
        try {
            int end = name.indexOf('_', PREFIX.length());
            return Integer.parseInt(name.substring(PREFIX.length(), end), 16);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String toHex(int address) {
        return String.format("%06x", address);
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readThreeBytes(byte[] data, int offset) {
        return (readWord(data, offset) << 8) | (data[offset + 2] & 0xFF);
    }

    private static void writeWord(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeThreeBytes(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 16) & 0xFF);
        writeWord(data, offset + 1, value);
    }

}
