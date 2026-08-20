package net.krusher.mortalsdk;

import junit.framework.TestCase;

public class SampleTableServiceTest extends TestCase {

    public void testReadsSampleTableEntry() throws Exception {
        byte[] rom = new byte[0x200];
        byte[] entry = new byte[] {2, 0, 1, 0x20, 0, 0x10, 0, (byte) 0xb5};
        System.arraycopy(entry, 0, rom, 4, entry.length);
        var result = SampleTableService.readTable(rom, 4, 1).getFirst();
        assertEquals(0xb5, result.flags());
        assertEquals(2, result.id());
        assertEquals(0x120, result.offset());
        assertEquals(0x10, result.length());
        assertEquals("sample_02_000120_0010_00b5.wav", result.fileName());
    }

    public void testRelocatesSampleAndUpdatesTable() throws Exception {
        byte[] rom = new byte[0x400];
        byte[] entry = new byte[] {2, 0, 1, 0x20, 0, 4, 0, (byte) 0xb5};
        System.arraycopy(entry, 0, rom, 4, entry.length);
        Config config = new Config(4, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), new java.util.HashSet<>(java.util.Set.of(Range.of(0x300, 0x3ff))),
                null, 7040, 4, 1);
        byte[] replacement = new byte[] {9, 8, 7, 6, 5, 4};
        var result = SampleTableService.applyReplacements(rom, config, java.util.Map.of(2, replacement)).getFirst();
        assertEquals(0x300, result.newOffset());
        assertEquals(6, result.newLength());
        assertEquals(0x300, (rom[5] & 0xff) << 16 | (rom[6] & 0xff) << 8 | rom[7] & 0xff);
        assertEquals(6, (rom[8] & 0xff) << 8 | rom[9] & 0xff);
        assertTrue(java.util.Arrays.equals(replacement, java.util.Arrays.copyOfRange(rom, 0x300, 0x306)));
    }

    public void testRejectsFreeSpaceOutsideRom() throws Exception {
        byte[] rom = new byte[0x200];
        byte[] entry = new byte[] {2, 0, 1, 0x20, 0, 4, 0, (byte) 0xb5};
        System.arraycopy(entry, 0, rom, 4, entry.length);
        Config config = new Config(4, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(Range.of(0x300, 0x3ff)), null, 7040, 4, 1);
        try {
            SampleTableService.applyReplacements(rom, config, java.util.Map.of(2, new byte[] {1}));
            fail("Debia rechazar un rango fuera de la ROM");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("fuera de la ROM"));
        }
    }

    public void testRejectsOverlappingFreeSpaceRanges() throws Exception {
        byte[] rom = new byte[0x400];
        byte[] entry = new byte[] {2, 0, 1, 0x20, 0, 4, 0, (byte) 0xb5};
        System.arraycopy(entry, 0, rom, 4, entry.length);
        Config config = new Config(4, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(Range.of(0x300, 0x350), Range.of(0x340, 0x3ff)),
                null, 7040, 4, 1);
        try {
            SampleTableService.applyReplacements(rom, config, java.util.Map.of(2, new byte[] {1}));
            fail("Debia rechazar rangos solapados");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("solapan"));
        }
    }
}
