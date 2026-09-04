package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Conversión entre el PCM de 8 bits con signo de la ROM y ficheros WAV.
 * <p>
 * WAV guarda el PCM de 8 bits sin signo, así que la conversión es un XOR con 0x80 en los dos sentidos.
 * <p>
 * El contenedor RIFF se lee y se escribe aquí a mano en vez de usar {@code javax.sound}: esa API vive en el
 * módulo java.desktop y su implementación tira de código nativo, así que al compilar con GraalVM habría que
 * repartir un {@code sound.dll} junto al ejecutable. Para leer y escribir PCM sin comprimir no hace falta.
 */
public final class WavService {

    private static final int SIGN_FLIP = 0x80;
    private static final int HEADER_SIZE = 44;
    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;
    private static final int FMT_CHUNK_SIZE = 16;
    private static final int EXTENSIBLE_SIZE = 40;

    private WavService() {}

    /**
     * El PCM leído de un WAV junto con su frecuencia de muestreo.
     */
    public record WavData(byte[] pcm, int sampleRate) {}

    /** Escribe el PCM (8 bits con signo) como WAV mono de 8 bits sin signo. */
    public static void write(byte[] pcm, int sampleRate, File output) throws IOException {
        byte[] wav = new byte[HEADER_SIZE + pcm.length];

        putTag(wav, 0, "RIFF");
        putInt(wav, 4, wav.length - 8);
        putTag(wav, 8, "WAVE");

        putTag(wav, 12, "fmt ");
        putInt(wav, 16, FMT_CHUNK_SIZE);
        putShort(wav, 20, FORMAT_PCM);
        putShort(wav, 22, 1);              // mono
        putInt(wav, 24, sampleRate);
        putInt(wav, 28, sampleRate);       // bytes por segundo
        putShort(wav, 32, 1);              // bytes por bloque
        putShort(wav, 34, 8);              // bits por muestra

        putTag(wav, 36, "data");
        putInt(wav, 40, pcm.length);
        for (int i = 0; i < pcm.length; i++) {
            wav[HEADER_SIZE + i] = (byte) ((pcm[i] & 0xFF) ^ SIGN_FLIP);
        }

        Files.write(output.toPath(), wav);
    }

    /**
     * Lee un WAV y lo convierte a PCM de 8 bits con signo y mono. Se aceptan 8 y 16 bits, mono y estéreo,
     * y cualquier frecuencia: el reproductor de la ROM la reproduce cambiando la velocidad de la entrada.
     */
    public static WavData read(File input) throws IOException {
        byte[] wav = Files.readAllBytes(input.toPath());
        if (wav.length < 12 || !isTag(wav, 0, "RIFF") || !isTag(wav, 8, "WAVE")) {
            throw new IOException("No es un fichero WAV: " + input.getName());
        }

        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int dataAt = -1;
        int dataSize = 0;

        int at = 12;
        while (at + 8 <= wav.length) {
            int size = getInt(wav, at + 4);
            int body = at + 8;
            if (size < 0 || body + size > wav.length) {
                size = wav.length - body;
            }
            if (isTag(wav, at, "fmt ") && size >= FMT_CHUNK_SIZE) {
                format = getShort(wav, body);
                channels = getShort(wav, body + 2);
                sampleRate = getInt(wav, body + 4);
                bits = getShort(wav, body + 14);
                if (format == FORMAT_EXTENSIBLE && size >= EXTENSIBLE_SIZE) {
                    format = getShort(wav, body + 24);
                }
            } else if (isTag(wav, at, "data")) {
                dataAt = body;
                dataSize = size;
            }
            at = body + size + (size & 1);
        }

        if (dataAt < 0 || format < 0) {
            throw new IOException("Al WAV " + input.getName() + " le falta la cabecera fmt o los datos");
        }
        if (format != FORMAT_PCM || (bits != 8 && bits != 16) || channels < 1 || channels > 2) {
            throw new IOException("Formato WAV no admitido en " + input.getName()
                    + ": se necesita PCM de 8 o 16 bits, mono o estéreo");
        }

        int bytesPerSample = bits / 8;
        int frames = dataSize / (bytesPerSample * channels);
        byte[] pcm = new byte[frames];
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                int sampleAt = dataAt + (frame * channels + channel) * bytesPerSample;
                // de 16 bits nos quedamos con el byte alto, que ya es la muestra de 8 bits con signo
                sum += bits == 8 ? (wav[sampleAt] & 0xFF) ^ SIGN_FLIP : wav[sampleAt + 1];
            }
            pcm[frame] = (byte) (sum / channels);
        }
        return new WavData(pcm, sampleRate);
    }

    private static void putTag(byte[] data, int at, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            data[at + i] = (byte) tag.charAt(i);
        }
    }

    private static boolean isTag(byte[] data, int at, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            if (data[at + i] != (byte) tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static void putShort(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >>> 8);
    }

    private static void putInt(byte[] data, int at, int value) {
        putShort(data, at, value & 0xFFFF);
        putShort(data, at + 2, value >>> 16);
    }

    private static int getShort(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static int getInt(byte[] data, int at) {
        return getShort(data, at) | (getShort(data, at + 2) << 16);
    }

}
