package net.krusher.mortalsdk;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class WavService {

    private WavService() {}

    public static void exportPcmFiles(File directory, int sampleRate) throws IOException {
        File[] pcmFiles = directory.listFiles((dir, name) -> name.startsWith("pcm_") && name.endsWith(".pcm"));
        if (pcmFiles == null) {
            return;
        }
        for (File pcmFile : pcmFiles) {
            byte[] pcm = Files.readAllBytes(pcmFile.toPath());
            File wavFile = new File(directory, pcmFile.getName().replace(".pcm", ".wav"));
            writeSigned8BitMono(pcm, sampleRate, wavFile);
        }
    }

    public static void injectWavFiles(File[] files, byte[] rom) throws IOException {
        for (File file : files) {
            if (!file.getName().startsWith("pcm_") || !file.getName().endsWith(".wav")) {
                continue;
            }
            int address = parseAddress(file);
            byte[] pcm = readSigned8BitMono(file, App.config.pcmSampleRate());
            int room = App.config.sounds().stream()
                    .filter(range -> range.getFrom() == address)
                    .map(range -> range.getTo() - range.getFrom() + 1)
                    .findFirst()
                    .orElseThrow(() -> new IOException("No hay rango PCM configurado para " + file.getName()));
            if (pcm.length != room) {
                throw new IOException("El WAV " + file.getName() + " contiene " + pcm.length
                        + " muestras; se esperaban " + room);
            }
            System.arraycopy(pcm, 0, rom, address, pcm.length);
        }
    }

    static void writeSigned8BitMono(byte[] pcm, int sampleRate, File output) throws IOException {
        byte[] wavSamples = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            wavSamples[i] = (byte) ((pcm[i] & 0xff) ^ 0x80);
        }
        AudioFormat format = new AudioFormat(sampleRate, 8, 1, false, false);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(wavSamples), format, wavSamples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output);
        }
    }

    static byte[] readSigned8BitMono(File input, int expectedSampleRate) throws IOException {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(input)) {
            AudioFormat format = stream.getFormat();
            if (format.getChannels() != 1 || format.getSampleSizeInBits() != 8
                    || format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED
                    || Math.round(format.getSampleRate()) != expectedSampleRate) {
                throw new IOException("Formato WAV no válido: se requiere PCM de 8 bits, mono, "
                        + expectedSampleRate + " Hz");
            }
            byte[] wavSamples = stream.readAllBytes();
            byte[] pcm = new byte[wavSamples.length];
            for (int i = 0; i < wavSamples.length; i++) {
                pcm[i] = (byte) ((wavSamples[i] & 0xff) ^ 0x80);
            }
            return pcm;
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("WAV no compatible: " + input.getName(), e);
        }
    }

    private static int parseAddress(File file) throws IOException {
        String name = file.getName();
        int underscore = name.lastIndexOf('_');
        int dot = name.lastIndexOf('.');
        try {
            return Integer.parseInt(name.substring(underscore + 1, dot), 16);
        } catch (RuntimeException e) {
            throw new IOException("Nombre WAV no válido: " + name, e);
        }
    }
}
