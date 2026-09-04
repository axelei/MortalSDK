package net.krusher.mortalsdk;

import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RncServiceTest {

    private static byte[] compressibleData(int size, int seed) {
        Random random = new Random(seed);
        byte[] data = new byte[size];
        int at = 0;
        while (at < size) {
            if (random.nextInt(3) == 0 || at == 0) {
                data[at++] = (byte) random.nextInt(256);
            } else {
                int from = random.nextInt(at);
                int length = Math.min(1 + random.nextInt(60), Math.min(size - at, at - from));
                for (int i = 0; i < length; i++) {
                    data[at++] = data[from + i];
                }
            }
        }
        return data;
    }

    private void assertRoundTrip(byte[] original, int method) throws Exception {
        byte[] packed = RncService.pack(original, method);
        assertTrue("debería tener cabecera RNC", RncService.isRncAt(packed, 0));
        assertEquals("método", method, packed[3] & 3);
        assertArrayEquals(original, RncService.unpack(packed, 0));
    }

    @Test
    public void roundTripsBothMethods() throws Exception {
        for (int seed = 0; seed < 8; seed++) {
            byte[] data = compressibleData(3000 + seed * 1500, seed);
            assertRoundTrip(data, RncService.METHOD_1);
            assertRoundTrip(data, RncService.METHOD_2);
        }
    }

    @Test
    public void roundTripsDataBiggerThanTheDictionary() throws Exception {
        assertRoundTrip(compressibleData(0x20000, 99), RncService.METHOD_1);
    }

    /** Datos muy repetitivos: las coincidencias llegan al tope de maxMatches y se usa el desempate. */
    @Test
    public void roundTripsVeryRepetitiveData() throws Exception {
        byte[] data = new byte[8000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i & 1) == 0 ? 0x00 : 0xE0);
        }
        assertRoundTrip(data, RncService.METHOD_1);
        assertRoundTrip(data, RncService.METHOD_2);
    }

    @Test
    public void findsSeveralBlocksInsideABiggerFile() throws Exception {
        byte[] first = compressibleData(4000, 1);
        byte[] second = compressibleData(2500, 2);
        byte[] packedFirst = RncService.pack(first, RncService.METHOD_1);
        byte[] packedSecond = RncService.pack(second, RncService.METHOD_2);

        int gap = 100;
        byte[] file = new byte[gap + packedFirst.length + gap + packedSecond.length + gap];
        System.arraycopy(packedFirst, 0, file, gap, packedFirst.length);
        System.arraycopy(packedSecond, 0, file, gap + packedFirst.length + gap, packedSecond.length);

        List<RncService.Block> blocks = RncService.search(file);
        assertEquals(2, blocks.size());
        assertEquals(gap, blocks.get(0).address());
        assertArrayEquals(first, blocks.get(0).data());
        assertEquals(packedFirst.length, blocks.get(0).packedSize());
        assertEquals(gap + packedFirst.length + gap, blocks.get(1).address());
        assertArrayEquals(second, blocks.get(1).data());
    }

    @Test
    public void refusesDataThatDoesNotCompress() {
        Random random = new Random(7);
        byte[] noise = new byte[4096];
        random.nextBytes(noise);
        try {
            byte[] packed = RncService.pack(noise, RncService.METHOD_1);
            // si acaso comprimiera, al menos tiene que poder deshacerse
            assertArrayEquals(noise, RncService.unpack(packed, 0));
        } catch (RncException e) {
            assertTrue(e.getMessage().contains("no se comprimen"));
        } catch (Exception e) {
            fail("no debería lanzar " + e);
        }
    }

    @Test
    public void rejectsGarbage() {
        byte[] garbage = new byte[64];
        try {
            RncService.unpack(garbage, 0);
            fail("tendría que haber fallado");
        } catch (RncException e) {
            assertTrue(e.getMessage().contains("firma"));
        }
    }

}
