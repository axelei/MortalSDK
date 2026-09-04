package net.krusher.mortalsdk;

import org.junit.After;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CodeServiceTest {

    @After
    public void useDefaultConfig() {
        App.config = new Config();
    }

    private static void configure(Integer... routines) {
        App.config = new Config(4, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of(), null,
                Set.of(), Set.of(), Set.of(routines));
    }

    @Test
    public void putsAnRtsAtTheStartOfEachRoutine() {
        configure(0x100, 0x200);
        byte[] rom = new byte[0x400];
        CodeService.skipRoutines(rom);
        assertEquals(CodeService.RTS, TexticleService.readWord(rom, 0x100));
        assertEquals(CodeService.RTS, TexticleService.readWord(rom, 0x200));
        assertEquals("no se toca nada más", 0, TexticleService.readWord(rom, 0x102));
    }

    @Test
    public void doesNothingWithoutRoutinesToSkip() {
        App.config = new Config();
        byte[] rom = new byte[0x400];
        CodeService.skipRoutines(rom);
        assertEquals(0, TexticleService.readWord(rom, 0));
    }

    /** Las instrucciones del 68000 van en direcciones pares: una impar es una errata de la configuración. */
    @Test
    public void refusesAnOddAddress() {
        configure(0x101);
        try {
            CodeService.skipRoutines(new byte[0x400]);
            fail("tendría que haber fallado");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("impar"));
        }
    }

    @Test
    public void refusesAnAddressOutsideTheRom() {
        configure(0x400);
        try {
            CodeService.skipRoutines(new byte[0x400]);
            fail("tendría que haber fallado");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("fuera de la ROM"));
        }
    }

}
