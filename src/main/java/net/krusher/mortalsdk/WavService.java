package net.krusher.mortalsdk;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

/**
 * Conversión entre el PCM de 8 bits con signo de la ROM y ficheros WAV.
 * <p>
 * WAV guarda el PCM de 8 bits sin signo, así que la conversión es un XOR con 0x80 en los dos sentidos.
 */
public final class WavService {

    private static final int SIGN_FLIP = 0x80;

    private WavService() {}

    /**
     * El PCM leído de un WAV junto con su frecuencia de muestreo.
     */
    public record WavData(byte[] pcm, int sampleRate) {}

    public static void write(byte[] pcm, int sampleRate, File output) throws IOException {
        byte[] samples = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            samples[i] = (byte) ((pcm[i] & 0xFF) ^ SIGN_FLIP);
        }
        AudioFormat format = new AudioFormat(sampleRate, 8, 1, false, false);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(samples), format, samples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output);
        }
    }

    /**
     * Lee un WAV y lo convierte a PCM de 8 bits con signo y mono. Se aceptan 8 y 16 bits, mono y estéreo,
     * y cualquier frecuencia: el reproductor de la ROM la reproduce cambiando la velocidad de la entrada.
     */
    public static WavData read(File input) throws IOException {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(input)) {
            AudioFormat format = stream.getFormat();
            int bits = format.getSampleSizeInBits();
            int channels = format.getChannels();
            AudioFormat.Encoding encoding = format.getEncoding();
            if ((bits != 8 && bits != 16) || channels < 1 || channels > 2
                    || (!AudioFormat.Encoding.PCM_SIGNED.equals(encoding) && !AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding))) {
                throw new IOException("Formato WAV no admitido en " + input.getName()
                        + ": se necesita PCM de 8 o 16 bits, mono o estéreo");
            }
            byte[] raw = stream.readAllBytes();
            int bytesPerSample = bits / 8;
            int frames = raw.length / (bytesPerSample * channels);
            byte[] pcm = new byte[frames];
            for (int frame = 0; frame < frames; frame++) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int at = (frame * channels + channel) * bytesPerSample;
                    sum += bits == 8
                            ? readEightBits(raw, at, encoding)
                            : readSixteenBits(raw, at, format.isBigEndian());
                }
                pcm[frame] = (byte) (sum / channels);
            }
            return new WavData(pcm, Math.round(format.getSampleRate()));
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("WAV no compatible: " + input.getName(), e);
        }
    }

    private static int readEightBits(byte[] raw, int at, AudioFormat.Encoding encoding) {
        return AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)
                ? (byte) ((raw[at] & 0xFF) ^ SIGN_FLIP)
                : raw[at];
    }

    private static int readSixteenBits(byte[] raw, int at, boolean bigEndian) {
        return bigEndian ? raw[at] : raw[at + 1];
    }

}
