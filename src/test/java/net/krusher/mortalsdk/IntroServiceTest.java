package net.krusher.mortalsdk;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IntroServiceTest {

    @Before
    public void useDefaultConfig() {
        App.config = new Config();
    }

    // ---- RLE ----

    private void assertRleRoundTrip(byte[] data) {
        byte[] packed = IntroService.rleCompress(data);
        assertArrayEquals(data, IntroService.rleExpand(packed));
    }

    @Test
    public void rleSurvivesTheRoundTrip() {
        assertRleRoundTrip(new byte[0x400]);                       // todo ceros: una sola tirada
        byte[] noise = new byte[0x400];
        new Random(1).nextBytes(noise);
        assertRleRoundTrip(noise);                                 // sin tiradas: todo literales
        byte[] mixed = new byte[0x800];
        new Random(2).nextBytes(mixed);
        java.util.Arrays.fill(mixed, 0x100, 0x600, (byte) 0x77);   // una tirada larga en medio
        assertRleRoundTrip(mixed);
    }

    @Test
    public void rleActuallyCompressesRepeatedWords() {
        byte[] flat = new byte[0x1000];
        assertTrue("una pantalla plana debería quedar en nada",
                IntroService.rleCompress(flat).length < 16);
    }

    // ---- reparto de huecos ----

    @Test
    public void placerHandsOutTheHolesAndRespectsAlignment() throws Exception {
        IntroService.Placer placer = new IntroService.Placer(
                List.of(new IntroService.Region(0x1000, 0x800)), 0x400000);
        assertEquals(0x1000, placer.place("a", 0x100, 2));
        assertEquals(0x1100, placer.place("b", 0x100, 2));
        // alineado a 256: 0x1200 ya lo está
        assertEquals(0x1200, placer.place("c", 0x100, 256));
    }

    @Test
    public void placerComplainsWhenSomethingDoesNotFit() {
        IntroService.Placer placer = new IntroService.Placer(
                List.of(new IntroService.Region(0x1000, 0x100)), 0x400000);
        try {
            placer.place("gordo", 0x200, 2);
            fail("tendría que haber fallado");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("No cabe gordo"));
        }
    }

    // ---- el ensamblador ----

    @Test
    public void theAssemblerResolvesLabelsAndRejectsLongBranches() {
        IntroService.Asm asm = new IntroService.Asm(0x1000);
        asm.label("aqui");
        asm.nop();
        asm.bra("aqui");
        byte[] code = asm.link();
        assertEquals(0x4E71, IntroService.readU16(code, 0));      // nop
        assertEquals(0x6000, IntroService.readU16(code, 2));      // bra.w
        assertEquals(-4, (short) IntroService.readU16(code, 4));  // hacia atrás

        IntroService.Asm far = new IntroService.Asm(0);
        far.bra(0x40000);                                          // a 256 KB: no cabe en 16 bits
        try {
            far.link();
            fail("tendría que haber fallado");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("fuera de alcance"));
        }
    }

    /** El arranque tiene que caber en un bra.w desde el bucle final de la intro. */
    @Test
    public void theStubBuildsAndStartsByMaskingInterrupts() {
        IntroService.Stub stub = IntroService.buildStub(0x3E9472, 0x3E9500, 0x3E90A4, 0x3E918E,
                0xFFFF0200, 0x000200, 0xFF0100, false, new int[16]);
        assertEquals(0x46FC, IntroService.readU16(stub.near, 0));   // move.w #imm,sr
        assertEquals(0x2700, IntroService.readU16(stub.near, 2));
        assertTrue(stub.labels.containsKey("entrada"));
        assertTrue(stub.labels.containsKey("salir"));
        assertTrue(stub.labels.containsKey("descomp"));
        assertTrue(stub.labels.containsKey("vsync"));
    }

    // ---- comprobaciones de seguridad ----

    @Test
    public void refusesAnIntroItDoesNotKnow() {
        App.config = new Config();
        byte[] rom = new byte[0x400000];
        try {
            IntroService.place(rom, rom.clone(), new byte[100]);
            fail("tendría que haber fallado");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Intro desconocida"));
        }
    }

    /** Si se pide una intro y falta el fichero, hay que parar, no seguir como si nada. */
    @Test
    public void complainsWhenTheIntroFileIsMissing() {
        App.config = new Config(4, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Set.of(),
                "no-existe-esta-intro.md", java.util.Set.of(Range.of(0x1000, 0x2000)), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of());
        try {
            IntroService.inject(new byte[0x400000], new byte[0x400000]);
            fail("tendría que haber fallado");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("No se encuentra la intro"));
        }
    }

    @Test
    public void doesNothingWhenThereIsNoIntroConfigured() throws Exception {
        byte[] rom = new byte[0x400000];
        byte[] before = rom.clone();
        IntroService.inject(rom, before);
        assertArrayEquals("sin intro configurada no debe tocar nada", before, rom);
    }

}
