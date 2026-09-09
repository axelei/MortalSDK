package net.krusher.mortalsdk;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * La inyección de samples cuando varias entradas de la tabla comparten los mismos bytes, que en esta ROM es
 * de lo más normal: si se escribe encima del tramo, todas las que lo comparten suenan mezcladas.
 */
public class SampleServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final int ROM_SIZE = 0x10000;
    private static final int TABLE = 0x100;
    private static final int ENTRIES = 40;
    private static final int SHARED = 0x1500;
    private static final int RATE = 0x6A;
    private static final int FREE = 0x8000;

    @After
    public void useDefaultConfig() {
        App.config = new Config();
    }

    private static void withSpace(Range... ranges) {
        App.config = new Config(4, Set.of(), Set.of(), new HashSet<>(Set.of(ranges)), Map.of(), Map.of(),
                Set.of(), null, Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), null, null, Set.of(), null, Set.of());
    }

    /**
     * Una ROM de mentira con su tabla de samples. Las entradas 5 y 6 apuntan las dos al mismo sitio, y la 6
     * es un trozo de la 5, igual que pasa de verdad con los gritos de Sub-Zero y Sonya.
     */
    private static byte[] rom() {
        byte[] data = new byte[ROM_SIZE];
        for (int id = 0; id < ENTRIES; id++) {
            int entry = TABLE + id * 8;
            int offset = id == 6 ? SHARED : 0x1000 + id * 0x100;
            int length = id == 6 ? 0x40 : 0x80;
            data[entry] = (byte) id;
            data[entry + 1] = (byte) (offset >> 16);
            data[entry + 2] = (byte) (offset >> 8);
            data[entry + 3] = (byte) offset;
            data[entry + 4] = (byte) (length >> 8);
            data[entry + 5] = (byte) length;
            data[entry + 6] = 0;
            data[entry + 7] = (byte) RATE;
            for (int i = 0; i < length; i++) {
                data[offset + i] = (byte) (id * 31 + i);
            }
        }
        return data;
    }

    private static int offsetOf(byte[] data, int id) {
        int entry = TABLE + id * 8;
        return ((data[entry + 1] & 0xFF) << 16) | ((data[entry + 2] & 0xFF) << 8) | (data[entry + 3] & 0xFF);
    }

    private static int lengthOf(byte[] data, int id) {
        int entry = TABLE + id * 8;
        return ((data[entry + 4] & 0xFF) << 8) | (data[entry + 5] & 0xFF);
    }

    private static byte[] tone(int size, int seed) {
        byte[] pcm = new byte[size];
        for (int i = 0; i < size; i++) {
            pcm[i] = (byte) (seed * 13 + i * 5);
        }
        return pcm;
    }

    private File wav(int id, int offset, byte[] pcm) throws Exception {
        File file = folder.newFile(String.format("sample_%02x_%06x.wav", id, offset));
        WavService.write(pcm, SampleService.frequencyOf(RATE), file);
        return file;
    }

    @Test
    public void findsTheTable() {
        assertEquals(ENTRIES, SampleService.findTable(rom()).size());
        assertEquals(TABLE, SampleService.findTable(rom()).getFirst().entryAddress());
    }

    /** Lo que se comparte se separa: cada entrada acaba con sus bytes en un sitio distinto. */
    @Test
    public void sharedSamplesAreSeparated() throws Exception {
        withSpace(Range.of(FREE, FREE + 0xFFF));
        byte[] original = rom();
        byte[] data = original.clone();
        byte[] five = tone(100, 1);
        byte[] six = tone(100, 2);

        SampleService.inject(new File[]{wav(5, SHARED, five), wav(6, SHARED, six)}, data, original);

        int fiveAt = offsetOf(data, 5);
        int sixAt = offsetOf(data, 6);
        assertEquals(100, lengthOf(data, 5));
        assertEquals(100, lengthOf(data, 6));
        assertTrue("no pueden seguir compartiendo bytes",
                fiveAt + 100 <= sixAt || sixAt + 100 <= fiveAt);
        assertArrayEquals(five, Arrays.copyOfRange(data, fiveAt, fiveAt + 100));
        assertArrayEquals(six, Arrays.copyOfRange(data, sixAt, sixAt + 100));
        // el primero se queda en el tramo, que ya era suyo, y solo el que no cabe sale del espacio libre
        assertEquals(SHARED, fiveAt);
        assertEquals(FREE, sixAt);
    }

    /** Si solo cambia una de las que comparten, la otra se lleva sus bytes originales intactos. */
    @Test
    public void theUntouchedHalfKeepsItsOwnBytes() throws Exception {
        withSpace(Range.of(FREE, FREE + 0xFFF));
        byte[] original = rom();
        byte[] data = original.clone();
        byte[] six = Arrays.copyOfRange(original, SHARED, SHARED + 0x40);
        byte[] five = tone(0x80, 9);

        SampleService.inject(new File[]{wav(5, SHARED, five), wav(6, SHARED, six)}, data, original);

        assertArrayEquals(six, Arrays.copyOfRange(data, offsetOf(data, 6), offsetOf(data, 6) + 0x40));
        assertArrayEquals(five, Arrays.copyOfRange(data, offsetOf(data, 5), offsetOf(data, 5) + 0x80));
    }

    /** Y si una de las que comparten ni siquiera tiene fichero, tampoco se pierde. */
    @Test
    public void aDeletedSampleOfASharedRunIsCarriedAlong() throws Exception {
        withSpace(Range.of(FREE, FREE + 0xFFF));
        byte[] original = rom();
        byte[] data = original.clone();
        byte[] six = Arrays.copyOfRange(original, SHARED, SHARED + 0x40);

        SampleService.inject(new File[]{wav(5, SHARED, tone(0x80, 4))}, data, original);

        assertArrayEquals(six, Arrays.copyOfRange(data, offsetOf(data, 6), offsetOf(data, 6) + 0x40));
    }

    /** Si nadie ha tocado el tramo compartido no se mueve nada. */
    @Test
    public void anUnchangedSharedRunIsLeftWhereItWas() throws Exception {
        withSpace(Range.of(FREE, FREE + 0xFFF));
        byte[] original = rom();
        byte[] data = original.clone();

        SampleService.inject(new File[]{
                wav(5, SHARED, Arrays.copyOfRange(original, SHARED, SHARED + 0x80)),
                wav(6, SHARED, Arrays.copyOfRange(original, SHARED, SHARED + 0x40))}, data, original);

        assertArrayEquals(original, data);
    }

    /** El que no comparte nada y no crece se sigue escribiendo en su sitio. */
    @Test
    public void aLoneSampleStaysInPlace() throws Exception {
        withSpace(Range.of(FREE, FREE + 0xFFF));
        byte[] original = rom();
        byte[] data = original.clone();
        byte[] pcm = tone(0x40, 3);

        SampleService.inject(new File[]{wav(7, 0x1700, pcm)}, data, original);

        assertEquals(0x1700, offsetOf(data, 7));
        assertEquals(0x40, lengthOf(data, 7));
        assertArrayEquals(pcm, Arrays.copyOfRange(data, 0x1700, 0x1700 + 0x40));
    }

    /** Sin sitio para separarlos se deja el tramo como estaba, mezclado pero sin estropear nada más. */
    @Test
    public void withoutRoomTheSharedRunIsLeftUntouched() throws Exception {
        withSpace(Range.of(FREE, FREE + 8));
        byte[] original = rom();
        byte[] data = original.clone();

        SampleService.inject(new File[]{wav(5, SHARED, tone(100, 1)), wav(6, SHARED, tone(100, 2))},
                data, original);

        assertArrayEquals(original, data);
    }
}
