package net.krusher.mortalsdk;

import org.junit.After;
import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** El reparto del espacio libre de spaceRanges, y los huecos que se devuelven al mover algo. */
public class SpaceTest {

    @After
    public void useDefaultConfig() {
        App.config = new Config();
    }

    private static void withSpace(Range... ranges) {
        App.config = new Config(4, Set.of(), Set.of(), new HashSet<>(Set.of(ranges)), Map.of(), Map.of(),
                Set.of(), null, Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), null, null);
    }

    /** Sin rangos no hay sitio, y pedirlo no revienta. */
    @Test
    public void everyAddressIsEvenEvenAfterAnOddSizedBlock() {
        withSpace(Range.of(0x1000, 0x2000));
        // un tamaño par dejaba el siguiente hueco en impar por el "+ 1" que separa las reservas
        assertEquals(0x1000, TexticleService.getNewAddress(0x10).intValue());
        int second = TexticleService.getNewAddress(0x10).intValue();
        assertEquals("la segunda reserva tiene que caer en par y no en 0x1011", 0x1012, second);
        // y un tamaño impar tampoco puede dejarla impar
        int third = TexticleService.getNewAddress(0x11).intValue();
        assertEquals(0, third % 2);
        assertEquals(0, TexticleService.getNewAddress(8).intValue() % 2);
    }

    @Test
    public void takesAnOddHoleFromItsFirstEvenByte() {
        withSpace(Range.of(0x1001, 0x2000));
        assertEquals(0x1002, TexticleService.getNewAddress(8).intValue());
    }

    @Test
    public void neverHandsOutSpaceThatTheSramWillCover() {
        try {
            TexticleService.setSramWindow(Range.of(0x200000, 0x20FFFF));
            withSpace(Range.of(0x206000, 0x206FFF), Range.of(0x300000, 0x300FFF));
            // el primer hueco cae entero bajo la SRAM, así que se salta
            assertEquals(0x300000, TexticleService.getNewAddress(0x100).intValue());
        } finally {
            TexticleService.setSramWindow(null);
        }
    }

    @Test
    public void withoutSramEveryHoleIsUsable() {
        TexticleService.setSramWindow(null);
        withSpace(Range.of(0x206000, 0x206FFF));
        assertEquals(0x206000, TexticleService.getNewAddress(0x100).intValue());
    }

    @Test
    public void givesNothingWhenThereIsNoFreeSpace() {
        App.config = new Config();
        assertNull(TexticleService.getNewAddress(16));
    }

    /** Se coge siempre el hueco más bajo donde quepa, sin depender del orden del conjunto. */
    @Test
    public void takesTheLowestHoleThatFits() {
        withSpace(Range.of(0x9000, 0x9FFF), Range.of(0x1000, 0x100F), Range.of(0x5000, 0x5FFF));
        assertEquals(0x1000, TexticleService.getNewAddress(8).intValue());
        assertEquals(0x5000, TexticleService.getNewAddress(0x100).intValue());   // ya no cabe en el de 0x1000
    }

    /** Un hueco que se agota desaparece, y lo siguiente se pide al de más abajo que quede. */
    @Test
    public void dropsAHoleOnceItIsUsedUp() {
        withSpace(Range.of(0x1000, 0x100F), Range.of(0x2000, 0x2FFF));
        assertEquals(0x1000, TexticleService.getNewAddress(0x10).intValue());
        assertEquals(1, App.config.spaceRanges().size());
        assertEquals(0x2000, TexticleService.getNewAddress(0x10).intValue());
    }

    /** Lo que deja algo al moverse vuelve al espacio libre y lo aprovecha lo siguiente. */
    @Test
    public void aFreedHoleCanBeUsedAgain() {
        withSpace(Range.of(0x9000, 0x9010));
        TexticleService.freeSpace(0x3000, 0x400);
        assertEquals(0x3000, TexticleService.getNewAddress(0x300).intValue());
    }

    /** Un hueco pegado a otro se junta con él, para que no se pierda nada por el camino. */
    @Test
    public void adjoiningHolesAreMerged() {
        withSpace();
        TexticleService.freeSpace(0x3000, 0x100);
        TexticleService.freeSpace(0x3100, 0x100);
        assertEquals(1, App.config.spaceRanges().size());
        Range merged = App.config.spaceRanges().iterator().next();
        assertEquals(0x3000, merged.getFrom().intValue());
        assertEquals(0x31FF, merged.getTo().intValue());
        // y juntos dan para algo que no cabía en ninguno de los dos
        assertEquals(0x3000, TexticleService.getNewAddress(0x180).intValue());
    }

    @Test
    public void ignoresAnEmptyHole() {
        withSpace();
        TexticleService.freeSpace(0x3000, 0);
        assertEquals(0, App.config.spaceRanges().size());
    }

    /** El reproductor de samples direcciona por ventanas de 32 KB: un sample no puede cruzarlas. */
    @Test
    public void doesNotLetABlockCrossABankBoundary() {
        withSpace(Range.of(0x7F00, 0x1FFFF));
        assertEquals(0x8000, TexticleService.getNewAddress(0x200, 0x8000).intValue());
    }

    /** Si por la alineación de banco no cabe en ese hueco, se prueba con otro en vez de tirarlo. */
    @Test
    public void keepsAHoleThatOnlyFailsBecauseOfTheBank() {
        withSpace(Range.of(0x7FF0, 0x7FFF), Range.of(0x10000, 0x1FFFF));
        assertEquals(0x10000, TexticleService.getNewAddress(0x100, 0x8000).intValue());
        // el de 0x7ff0 sigue ahí para algo pequeño que sí quepa
        assertEquals(0x7FF0, TexticleService.getNewAddress(8).intValue());
    }

}
