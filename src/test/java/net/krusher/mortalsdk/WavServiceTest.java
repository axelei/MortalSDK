package net.krusher.mortalsdk;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WavServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] ramp(int size) {
        byte[] pcm = new byte[size];
        for (int i = 0; i < size; i++) {
            pcm[i] = (byte) (i * 7 - 128);
        }
        return pcm;
    }

    @Test
    public void writesAndReadsBackTheSamePcm() throws Exception {
        byte[] pcm = ramp(1000);
        File file = folder.newFile("round.wav");
        WavService.write(pcm, 7046, file);

        WavService.WavData read = WavService.read(file);
        assertArrayEquals(pcm, read.pcm());
        assertEquals(7046, read.sampleRate());
    }

    @Test
    public void writesACanonicalMono8BitHeader() throws Exception {
        byte[] pcm = ramp(10);
        File file = folder.newFile("header.wav");
        WavService.write(pcm, 8000, file);
        byte[] wav = Files.readAllBytes(file.toPath());

        assertEquals(44 + pcm.length, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        assertEquals("fmt ", new String(wav, 12, 4));
        assertEquals("data", new String(wav, 36, 4));
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16, header.getInt(16));            // tamaño del fmt
        assertEquals(1, header.getShort(20));           // PCM
        assertEquals(1, header.getShort(22));           // mono
        assertEquals(8000, header.getInt(24));          // frecuencia
        assertEquals(8, header.getShort(34));           // bits
        assertEquals(pcm.length, header.getInt(40));    // tamaño de los datos
        // el WAV de 8 bits va sin signo
        assertEquals((pcm[0] & 0xFF) ^ 0x80, wav[44] & 0xFF);
    }

    @Test
    public void readsSixteenBitStereo() throws Exception {
        int frames = 100;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < frames; i++) {
            short left = (short) ((i - 50) << 8);
            putShort(data, left);
            putShort(data, left);
        }
        File file = folder.newFile("s16.wav");
        Files.write(file.toPath(), wav(1, 2, 22050, 16, data.toByteArray()));

        WavService.WavData read = WavService.read(file);
        assertEquals(22050, read.sampleRate());
        assertEquals(frames, read.pcm().length);
        assertEquals((byte) -50, read.pcm()[0]);
    }

    @Test
    public void readsExtensibleAndSkipsUnknownChunks() throws Exception {
        byte[] samples = new byte[64];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (byte) i;
        }
        File file = folder.newFile("ext.wav");
        Files.write(file.toPath(), wavWithExtraChunk(samples));

        WavService.WavData read = WavService.read(file);
        assertEquals(8000, read.sampleRate());
        assertEquals(samples.length, read.pcm().length);
        assertEquals((byte) (samples[0] ^ 0x80), read.pcm()[0]);
    }

    @Test
    public void rejectsSomethingThatIsNotAWav() throws Exception {
        File file = folder.newFile("bad.wav");
        Files.write(file.toPath(), "esto no es un wav".getBytes());
        try {
            WavService.read(file);
            fail("tendría que haber fallado");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("No es un fichero WAV"));
        }
    }

    private static void putShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static byte[] wav(int format, int channels, int rate, int bits, byte[] data) throws IOException {
        ByteArrayOutputStream fmt = new ByteArrayOutputStream();
        int blockAlign = channels * bits / 8;
        putShort(fmt, format);
        putShort(fmt, channels);
        putInt(fmt, rate);
        putInt(fmt, rate * blockAlign);
        putShort(fmt, blockAlign);
        putShort(fmt, bits);
        return riff(chunk("fmt ", fmt.toByteArray()), chunk("data", data));
    }

    private static byte[] wavWithExtraChunk(byte[] data) throws IOException {
        ByteArrayOutputStream fmt = new ByteArrayOutputStream();
        putShort(fmt, 0xFFFE);
        putShort(fmt, 1);
        putInt(fmt, 8000);
        putInt(fmt, 8000);
        putShort(fmt, 1);
        putShort(fmt, 8);
        putShort(fmt, 22);          // cbSize
        putShort(fmt, 8);           // bits válidos
        putInt(fmt, 4);             // máscara de canales
        putShort(fmt, 1);           // subformato: PCM
        fmt.write(new byte[14]);    // resto del GUID
        return riff(chunk("fmt ", fmt.toByteArray()), chunk("LIST", "INFO".getBytes()), chunk("data", data));
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        putShort(out, value & 0xFFFF);
        putShort(out, value >>> 16);
    }

    private static byte[] chunk(String tag, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag.getBytes());
        putInt(out, body.length);
        out.write(body);
        if ((body.length & 1) != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] riff(byte[]... chunks) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("WAVE".getBytes());
        for (byte[] c : chunks) {
            body.write(c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes());
        putInt(out, body.size());
        out.write(body.toByteArray());
        return out.toByteArray();
    }

}
