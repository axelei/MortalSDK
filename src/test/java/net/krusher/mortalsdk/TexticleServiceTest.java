package net.krusher.mortalsdk;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TexticleServiceTest {

    @Before
    public void useDefaultConfig() {
        App.config = new Config();
    }

    /** Escribe un lea (d16,PC),a0 en {@code at} apuntando a {@code target}. */
    private static void putLea(byte[] rom, int at, int target) {
        rom[at] = 0x41;
        rom[at + 1] = (byte) 0xFA;
        int displacement = target - (at + 2);
        rom[at + 2] = (byte) (displacement >> 8);
        rom[at + 3] = (byte) displacement;
    }

    @Test
    public void findsAPcRelativeLea() {
        byte[] rom = new byte[0x2000];
        putLea(rom, 0x100, 0x800);
        Texticle.Pointer pointer = TexticleService.findPointer(0x800, rom);
        assertTrue("debería reconocerse como lea", pointer.lea());
        assertEquals(0x100, pointer.address());
    }

    /** Un lea vale más que una coincidencia suelta de tres bytes, que puede ser casualidad. */
    @Test
    public void prefersTheLeaOverAnAbsoluteMatch() {
        byte[] rom = new byte[0x2000];
        rom[0x40] = 0x00;
        rom[0x41] = 0x08;
        rom[0x42] = 0x00;      // 000800 suelto, antes del lea
        putLea(rom, 0x100, 0x800);
        assertTrue(TexticleService.findPointer(0x800, rom).lea());
    }

    @Test
    public void fallsBackToTheAbsolutePointer() {
        byte[] rom = new byte[0x2000];
        rom[0x40] = 0x00;
        rom[0x41] = 0x08;
        rom[0x42] = 0x00;
        Texticle.Pointer pointer = TexticleService.findPointer(0x800, rom);
        assertFalse(pointer.lea());
        assertEquals(0x40, pointer.address());
    }

    @Test
    public void writesBackBothKindsOfPointer() {
        byte[] rom = new byte[0x2000];
        putLea(rom, 0x100, 0x800);

        assertTrue(TexticleService.writePointer(new Texticle.Pointer(0x100, true), 0x900, rom));
        assertEquals(0x100, TexticleService.findLeaAddress(0x900, rom).intValue());

        assertTrue(TexticleService.writePointer(new Texticle.Pointer(0x40, false), 0x1234, rom));
        // findPointerAddress devuelve dónde está el puntero, no lo que vale
        assertEquals(0x40, TexticleService.findPointerAddress(0x1234, rom).intValue());
    }

    /** La distancia de un lea son 16 bits con signo: más allá de 32 KB no se puede reubicar. */
    @Test
    public void refusesToMoveALeaTooFarAway() {
        byte[] rom = new byte[0x400000];
        putLea(rom, 0x100, 0x800);
        assertFalse(TexticleService.writePointer(new Texticle.Pointer(0x100, true), 0x3F0000, rom));
        // y el lea se queda como estaba, apuntando a 0x800
        assertEquals(0x100, TexticleService.findLeaAddress(0x800, rom).intValue());
    }

    @Test
    public void readsAndWritesTheLineFormat() {
        Texticle texticle = new Texticle(0x2fe6, 33, "MORTAL KOMBAT*CAST OF CHARACTERS:",
                new Texticle.Pointer(0x5a00, true));
        assertEquals("002fe6#0033#MORTAL KOMBAT*CAST OF CHARACTERS:#lea:005a00", texticle.format());

        String[] parts = texticle.format().split("#");
        assertEquals(0x2fe6, Texticle.parseAddress(parts[0]));
        Texticle.Pointer back = Texticle.Pointer.parse(parts[3]);
        assertTrue(back.lea());
        assertEquals(0x5a00, back.address());
    }

    @Test
    public void writesAnAbsolutePointerWithoutTheLeaMark() {
        Texticle texticle = new Texticle(0x100, 4, "WOOD", new Texticle.Pointer(0x1663b, false));
        assertEquals("000100#0004#WOOD#abs:01663b", texticle.format());
        assertFalse(Texticle.Pointer.parse("abs:01663b").lea());
    }

    @Test
    public void acceptsATexticleWithoutPointer() {
        assertEquals("000100#0004#WOOD", new Texticle(0x100, 4, "WOOD", null).format());
        assertNull(Texticle.Pointer.parse("  "));
    }

}
