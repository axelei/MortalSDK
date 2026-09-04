package net.krusher.mortalsdk;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Lo que promete el parche: aplicado sobre la ROM original tiene que dar exactamente la parcheada. Todos los
 * casos se comprueban aplicándolo de verdad con el lector de más abajo, que sigue la especificación del
 * formato y no lo que hace {@link IpsService}: comprobar un escritor consigo mismo no demuestra nada.
 */
public class IpsServiceTest {

    /** Una ROM de mentira con cambios de todas las clases: sueltos, seguidos y una tirada larga. */
    private static byte[][] romPair() {
        byte[] base = new byte[0x40000];
        new Random(7).nextBytes(base);
        byte[] patched = base.clone();
        patched[0x10] = (byte) (base[0x10] + 1);
        for (int i = 0x1000; i < 0x1400; i++) {
            patched[i] = (byte) i;
        }
        Arrays.fill(patched, 0x20000, 0x30000, (byte) 0xAB);
        return new byte[][]{base, patched};
    }

    @Test
    public void thePatchTurnsTheOriginalRomIntoThePatchedOne() {
        byte[][] roms = romPair();
        assertArrayEquals(roms[1], apply(roms[0], IpsService.build(roms[0], roms[1])));
    }

    @Test
    public void thePatchIsMuchSmallerThanTheRom() {
        byte[][] roms = romPair();
        byte[] ips = IpsService.build(roms[0], roms[1]);
        assertTrue("el parche ocupa " + ips.length + " para una ROM de " + roms[1].length
                + ": algo está metiendo bytes que no han cambiado", ips.length < roms[1].length / 2);
    }

    @Test
    public void theHeaderAndTheEndMarkerAreInPlace() {
        byte[][] roms = romPair();
        byte[] ips = IpsService.build(roms[0], roms[1]);
        assertArrayEquals(IpsService.MAGIC, Arrays.copyOf(ips, 5));
        assertArrayEquals(IpsService.EOF_MARKER, Arrays.copyOfRange(ips, ips.length - 3, ips.length));
    }

    /**
     * Un parche vacío merece un aviso: quiere decir que la inyección no ha cambiado nada, y además hay
     * programas que buscan la marca de final sólo después de leer un registro y lo dan por incompleto.
     */
    @Test
    public void anEmptyPatchIsRecognisedAsEmpty() {
        byte[][] roms = romPair();
        byte[] ips = IpsService.build(roms[0], roms[0].clone());
        assertTrue(IpsService.isEmpty(ips));
        assertEquals(IpsService.MAGIC.length + IpsService.EOF_MARKER.length, ips.length);
        assertArrayEquals(roms[0], apply(roms[0], ips));
        assertFalse(IpsService.isEmpty(IpsService.build(roms[0], roms[1])));
    }

    @Test
    public void aRunLongerThanTheSizeFieldIsSplitAcrossRecords() {
        // bytes al azar para que no se pueda comprimir nada en RLE: la única forma de decirlo son varios
        // registros seguidos
        byte[] base = new byte[0x30000];
        byte[] patched = base.clone();
        new Random(1).nextBytes(patched);
        for (int i = 0; i < patched.length; i++) {
            if (patched[i] == 0) {
                patched[i] = 1;      // todos los bytes tienen que ser distintos
            }
        }
        byte[] ips = IpsService.build(base, patched);
        assertArrayEquals(patched, apply(base, ips));
        assertTrue("0x30000 bytes cambiados necesitan al menos tres registros, y hay " + recordCount(ips),
                recordCount(ips) >= 3);
    }

    @Test
    public void aLongRunOfOneByteIsStoredAsRle() {
        byte[] base = new byte[0x20000];
        byte[] patched = base.clone();
        Arrays.fill(patched, 0x1000, 0x1F000, (byte) 0xAB);
        byte[] ips = IpsService.build(base, patched);
        assertArrayEquals(patched, apply(base, ips));
        assertTrue("una tirada RLE cuesta cuatro bytes, no " + ips.length, ips.length < 100);
    }

    /**
     * Un cambio justo en 0x454F46 escribiría los bytes "EOF" donde va la posición, y ahí pararía de leer
     * cualquier programa que aplique el parche.
     */
    @Test
    public void noRecordStartsAtTheOffsetThatLooksLikeTheEndMarker() {
        byte[] base = new byte[IpsService.EOF_OFFSET + 0x10];
        byte[] patched = base.clone();
        patched[IpsService.EOF_OFFSET] = 0x42;
        byte[] ips = IpsService.build(base, patched);
        assertArrayEquals("el cambio se escribe igual, un byte antes", patched, apply(base, ips));
        for (int offset : recordOffsets(ips)) {
            assertTrue("ningún registro puede empezar en 0x454f46", offset != IpsService.EOF_OFFSET);
        }
    }

    @Test
    public void bytesAddedPastTheEndOfTheOriginalRomAreInThePatch() {
        byte[] base = new byte[0x100];
        byte[] patched = Arrays.copyOf(base, 0x180);
        Arrays.fill(patched, 0x100, 0x180, (byte) 0x7F);
        assertArrayEquals(patched, apply(base, IpsService.build(base, patched)));
    }

    @Test
    public void aRomThatShrinksIsRejected() {
        try {
            IpsService.build(new byte[0x200], new byte[0x100]);
            fail("tendría que haber fallado");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("más pequeña"));
        }
    }

    @Test
    public void aRomTooBigForTheOffsetFieldIsRejected() {
        try {
            IpsService.build(new byte[1], new byte[IpsService.MAX_OFFSET + 2]);
            fail("tendría que haber fallado");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("24 bits"));
        }
    }

    // ---- un lector escrito a partir de la especificación, no de IpsService ----

    /** Aplica un parche IPS como dice la especificación que hay que aplicarlo. */
    private static byte[] apply(byte[] rom, byte[] ips) {
        assertArrayEquals(IpsService.MAGIC, Arrays.copyOf(ips, 5));
        byte[] out = rom.clone();
        int at = 5;
        while (true) {
            assertTrue("el parche se acaba sin la marca de final", at + 3 <= ips.length);
            if (Arrays.equals(Arrays.copyOfRange(ips, at, at + 3), IpsService.EOF_MARKER)) {
                assertEquals("hay bytes después de la marca de final", ips.length, at + 3);
                return out;
            }
            int offset = u24(ips, at);
            int size = u16(ips, at + 3);
            at += 5;
            if (size == 0) {
                int runLength = u16(ips, at);
                byte value = ips[at + 2];
                at += 3;
                out = grow(out, offset + runLength);
                Arrays.fill(out, offset, offset + runLength, value);
            } else {
                out = grow(out, offset + size);
                System.arraycopy(ips, at, out, offset, size);
                at += size;
            }
        }
    }

    /** Un registro puede escribir más allá del final del fichero que parchea. */
    private static byte[] grow(byte[] out, int needed) {
        return out.length >= needed ? out : Arrays.copyOf(out, needed);
    }

    private static int[] recordOffsets(byte[] ips) {
        int[] offsets = new int[recordCount(ips)];
        int at = 5;
        int n = 0;
        while (!Arrays.equals(Arrays.copyOfRange(ips, at, at + 3), IpsService.EOF_MARKER)) {
            offsets[n++] = u24(ips, at);
            int size = u16(ips, at + 3);
            at += size == 0 ? 8 : 5 + size;
        }
        return offsets;
    }

    private static int recordCount(byte[] ips) {
        int at = 5;
        int count = 0;
        while (!Arrays.equals(Arrays.copyOfRange(ips, at, at + 3), IpsService.EOF_MARKER)) {
            int size = u16(ips, at + 3);
            at += size == 0 ? 8 : 5 + size;
            count++;
        }
        return count;
    }

    private static int u24(byte[] b, int at) {
        return ((b[at] & 0xFF) << 16) | ((b[at + 1] & 0xFF) << 8) | (b[at + 2] & 0xFF);
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

}
