package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.Range;
import net.krusher.mortalsdk.RncService;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Lo que se puede comprobar sin ROM ni ficheros: la rutina que se inyecta, los mapas y los colores. */
public class FondoServiceTest {

    @Test
    public void laRutinaMideLoQueDiceYLlamaALaOriginal() {
        byte[] codigo = FondoService.rutina(0x60C, 0x3F6800, 0x3F8A10, 0x3F8E10);
        assertEquals(126, codigo.length);
        // empieza con jsr $60C
        assertEquals((byte) 0x4E, codigo[0]);
        assertEquals((byte) 0xB9, codigo[1]);
        assertEquals(0x60C, FondoService.leerLong(codigo, 2));
        // y termina en rts
        assertEquals((byte) 0x4E, codigo[124]);
        assertEquals((byte) 0x75, codigo[125]);
    }

    @Test
    public void laRutinaLlevaLasCuatroDireccionesQueSeLePasan() {
        byte[] codigo = FondoService.rutina(0x9AA, 0x111100, 0x222200, 0x333300);
        String hex = hex(codigo);
        assertTrue("falta el jsr a la rutina original", hex.contains("4eb9000009aa"));
        assertTrue("falta el bloque de tiles", hex.contains("41f900111100"));
        assertTrue("falta el mapa A", hex.contains("41f900222200"));
        assertTrue("falta el mapa B", hex.contains("41f900333300"));
        assertTrue("falta la llamada que carga tiles a VRAM", hex.contains("4eb900000b94"));
        assertTrue("falta la llamada que descomprime a RAM", hex.contains("4eb900000b88"));
        assertTrue("falta move.w $ACBE,d7", hex.contains("3e38acbe"));
    }

    @Test
    public void elMapaSeGuardaRestandoLaBasePorqueLaCopiaLaSuma() {
        int[] mapa = {0x01E1, 0x81E5, 0x41E2};
        byte[] bytes = FondoService.mapaRelativo(mapa, 0x1E1);
        assertEquals(6, bytes.length);
        assertEquals(0x0000, palabra(bytes, 0));
        assertEquals(0x8004, palabra(bytes, 2));
        assertEquals(0x4001, palabra(bytes, 4));
    }

    @Test
    public void elEspacioSoloDaHuecosVaciosYAvisaCuandoSeAcaba() throws IOException {
        byte[] rom = new byte[0x400000];
        FondoService.Espacio espacio = new FondoService.Espacio(List.of(Range.of(0x3F6800, 0x3F6900)));
        int a = espacio.reservar(rom, 0x80);
        assertEquals(0x3F6800, a);
        int b = espacio.reservar(rom, 0x80);
        assertEquals(0x3F6880, b);
        try {
            espacio.reservar(rom, 0x80);
            fail("tendría que haberse quedado sin espacio");
        } catch (IOException esperado) {
            assertTrue(esperado.getMessage().contains("No queda espacio"));
        }
    }

    @Test
    public void unHuecoOcupadoNoSeUsa() {
        byte[] rom = new byte[0x400000];
        rom[0x3F6810] = 0x42;
        FondoService.Espacio espacio = new FondoService.Espacio(List.of(Range.of(0x3F6800, 0x3F6900)));
        try {
            espacio.reservar(rom, 0x80);
            fail("tendría que haber avisado de que el hueco está ocupado");
        } catch (IOException esperado) {
            assertTrue(esperado.getMessage().contains("no está vacío"));
        }
    }

    @Test
    public void losColoresVanYVuelvenDeCram() {
        for (int w = 0; w <= 0x0EEE; w += 2) {
            if ((w & 0x1111) != 0) {
                continue;   // los bits que no son de color no se usan
            }
            assertEquals("color " + Integer.toHexString(w), w, Fondo.rgbACram(Fondo.cramARgb(w)));
        }
    }

    @Test
    public void losTilesVanYVuelvenDeSuFormatoDe32Bytes() {
        Random random = new Random(7);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        assertArrayEquals(bytes, Fondo.empaquetar(Fondo.tileDe(bytes, 0)));
    }

    @Test
    public void voltearDosVecesDejaElTileComoEstaba() {
        Random random = new Random(11);
        int[] px = new int[64];
        for (int i = 0; i < px.length; i++) {
            px[i] = random.nextInt(16);
        }
        assertArrayEquals(px, Fondo.orientar(Fondo.orientar(px, true, true), true, true));
        assertNotEquals(Fondo.clave(px), Fondo.clave(Fondo.orientar(px, true, false)));
    }

    @Test
    public void laPalabraDelMapaSeMontaYSeLeeIgual() {
        int w = Fondo.palabraDe(0x1E5, true, false, 3, true);
        assertEquals(0x1E5, Fondo.indice(w));
        assertTrue(Fondo.volteoH(w));
        assertTrue(!Fondo.volteoV(w));
        assertEquals(3, Fondo.linea(w));
        assertTrue(Fondo.prioridad(w));
        // prioridad 8000 | linea 3 en los bits 13-14 (6000) | volteo H (0800) | indice 1E5
        assertEquals(0xE9E5, w);
    }

    @Test
    public void losSieteEscenariosCabenDebajoDelMarcador() {
        for (Escenario e : Escenario.TODOS) {
            assertTrue(e.nombre() + " no deja sitio para tiles", e.presupuestoTiles() > 0);
            assertTrue(e.nombre() + " pisaría los tiles del marcador",
                    e.baseTiles() + e.presupuestoTiles() <= Escenario.LIMITE_TILES);
            assertTrue(e.nombre() + " no tiene rutina conocida",
                    FondoService.RUTINAS_ORIGINALES.contains(e.rutinaOriginal()));
        }
        assertEquals(7, Escenario.TODOS.size());
    }

    @Test
    public void loQueSeComprimeSeVuelveADescomprimirIgual() throws Exception {
        Random random = new Random(3);
        byte[] datos = new byte[Escenario.CELDAS * 2];
        for (int i = 0; i < datos.length; i += 2) {
            datos[i] = (byte) (random.nextInt(4) == 0 ? random.nextInt(8) : 0);
            datos[i + 1] = (byte) random.nextInt(256);
        }
        byte[] comprimido = RncService.pack(datos, RncService.METHOD_1);
        assertArrayEquals(datos, RncService.unpack(comprimido));
    }

    private static int palabra(byte[] d, int at) {
        return ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF);
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
