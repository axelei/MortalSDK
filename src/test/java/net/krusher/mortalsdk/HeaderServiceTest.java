package net.krusher.mortalsdk;

import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class HeaderServiceTest {

    @After
    public void useDefaultConfig() {
        App.config = new Config();
    }

    private static void withName(String name) {
        App.config = new Config(4, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of(), null,
                Set.of(), Set.of(), Set.of(), Set.of(), name, null);
    }

    private static String nameAt(byte[] rom, int at) {
        return new String(rom, at, HeaderService.NAME_SIZE, StandardCharsets.ISO_8859_1);
    }

    /** El nombre va en los dos campos, relleno de espacios hasta los 48 bytes. */
    @Test
    public void writesTheNameInBothFields() {
        withName("KOMBATE MORTAS ARCADA EDITION");
        byte[] rom = new byte[0x400];
        Arrays.fill(rom, 0x180, 0x190, (byte) 0x7E);      // el número de serie no se toca
        HeaderService.writeName(rom);
        String esperado = "KOMBATE MORTAS ARCADA EDITION                   ";
        assertEquals(esperado, nameAt(rom, HeaderService.DOMESTIC_NAME));
        assertEquals(esperado, nameAt(rom, HeaderService.OVERSEAS_NAME));
        assertEquals(0x7E, rom[0x180]);
    }

    /** Lo que no quepa se corta, sin salirse del campo. */
    @Test
    public void cutsANameThatDoesNotFit() {
        withName("ESTE NOMBRE ES MUCHO MAS LARGO DE LO QUE CABE EN LA CABECERA");
        byte[] rom = new byte[0x400];
        Arrays.fill(rom, 0x180, 0x190, (byte) 0x7E);
        HeaderService.writeName(rom);
        assertEquals("ESTE NOMBRE ES MUCHO MAS LARGO DE LO QUE CABE EN",
                nameAt(rom, HeaderService.DOMESTIC_NAME));
        assertEquals("no se sale del campo", 0x7E, rom[0x180]);
    }

    /** Sin la propiedad no se toca la cabecera. */
    @Test
    public void doesNothingWithoutTheProperty() {
        App.config = new Config();
        byte[] rom = new byte[0x400];
        byte[] antes = rom.clone();
        HeaderService.writeName(rom);
        org.junit.Assert.assertArrayEquals(antes, rom);
    }

    @Test
    public void doesNothingWithABlankName() {
        withName("   ");
        byte[] rom = new byte[0x400];
        byte[] antes = rom.clone();
        HeaderService.writeName(rom);
        org.junit.Assert.assertArrayEquals(antes, rom);
    }

}
