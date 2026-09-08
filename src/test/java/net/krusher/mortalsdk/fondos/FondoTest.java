package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.Bitmap;
import net.krusher.mortalsdk.Png;
import net.krusher.mortalsdk.RncService;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Pruebas con los datos de verdad: la carpeta {@code fondos/} de KombateSDK y la ROM v2-7. Si no están a mano
 * (otro ordenador, otra copia del proyecto), los tests se saltan en vez de fallar.
 */
public class FondoTest {

    private static File carpetaFondos;
    private static byte[] rom;

    @BeforeClass
    public static void buscarLosDatos() throws IOException {
        for (String base : new String[]{"../KombateSDK", "KombateSDK", "../../KombateSDK"}) {
            File fondos = new File(base, "fondos");
            File romFile = new File(base, "Mortal Kombat Arcade Edition v2-7.bin");
            if (fondos.isDirectory() && romFile.isFile()) {
                carpetaFondos = fondos;
                rom = Files.readAllBytes(romFile.toPath());
                return;
            }
        }
    }

    private static void hacenFaltaLosDatos() {
        assumeTrue("no está la carpeta fondos/ de KombateSDK al lado; se salta", carpetaFondos != null);
    }

    @Test
    public void losSieteFondosSeCarganConSusTilesYSusMapas() throws IOException {
        hacenFaltaLosDatos();
        for (Escenario e : Escenario.TODOS) {
            Fondo f = Fondo.cargar(carpetaFondos, e, rom);
            assertEquals(e.nombre(), Escenario.CELDAS, f.mapaA.length);
            assertTrue(e.nombre() + ": no hay tiles", f.tiles.length > 0);
            assertTrue(e.nombre() + ": más tiles de los que caben", f.tiles.length <= e.presupuestoTiles());
            int[] uso = f.usoTiles();
            int enUso = 0;
            for (int u : uso) {
                if (u > 0) {
                    enUso++;
                }
            }
            assertTrue(e.nombre() + ": ninguna celda usa el banco", enUso > 100);
        }
    }

    @Test
    public void elPlanoQueDibujaJavaEsElMismoPngQueYaHabia() throws IOException {
        hacenFaltaLosDatos();
        for (Escenario e : Escenario.TODOS) {
            Fondo f = Fondo.cargar(carpetaFondos, e, rom);
            for (char plano : new char[]{'A', 'B'}) {
                File png = new File(carpetaFondos, e.carpeta() + "/plano_" + plano + ".png");
                if (!png.isFile()) {
                    continue;
                }
                Bitmap esperado = Png.read(png);
                Bitmap dibujado = f.renderIndexado(plano);
                assertEquals(esperado.getWidth(), dibujado.getWidth());
                assertEquals(esperado.getHeight(), dibujado.getHeight());
                int distintos = 0;
                for (int y = 0; y < esperado.getHeight(); y++) {
                    for (int x = 0; x < esperado.getWidth(); x++) {
                        if (esperado.indexAt(x, y) != dibujado.indexAt(x, y)) {
                            distintos++;
                        }
                    }
                }
                assertEquals(e.nombre() + " plano " + plano + ": píxeles distintos", 0, distintos);
            }
        }
    }

    @Test
    public void loQueSeInyectaEsExactamenteLoQueSeVeraEnVram() throws IOException {
        hacenFaltaLosDatos();
        for (Escenario e : Escenario.TODOS) {
            Fondo f = Fondo.cargar(carpetaFondos, e, rom);
            byte[] copia = rom.clone();
            FondoService.Espacio espacio = new FondoService.Espacio(FondoService.ESPACIO_POR_DEFECTO);
            FondoService.Resultado r = FondoService.inyectar(copia, f, espacio);

            // los tiles vuelven enteros
            byte[] tiles = RncService.unpack(copia, r.tiles());
            assertEquals(e.nombre(), f.tiles.length * 32, tiles.length);
            for (int i = 0; i < f.tiles.length; i++) {
                assertArrayEquals(e.nombre() + ": tile " + i, Fondo.empaquetar(f.tiles[i]),
                        java.util.Arrays.copyOfRange(tiles, i * 32, i * 32 + 32));
            }
            // y los mapas también, con la base restada como espera la rutina de copia
            comprobarMapa(e, copia, r.mapaA(), f.mapaA, e.baseTiles());
            comprobarMapa(e, copia, r.mapaB(), f.mapaB, e.baseTiles());
            // la tabla de escenarios apunta a la rutina nueva, y la rutina llama a la vieja
            assertEquals(e.nombre() + ": la tabla no apunta a la rutina nueva", r.rutina(),
                    FondoService.leerLong(copia, Escenario.TABLA_RUTINAS + 4 * e.numero()));
            assertEquals(e.nombre() + ": la rutina nueva no llama a la original", e.rutinaOriginal(),
                    FondoService.leerLong(copia, r.rutina() + 2));
        }
    }

    private static void comprobarMapa(Escenario e, byte[] rom, int direccion, int[] esperado, int base) throws IOException {
        byte[] mapa = RncService.unpack(rom, direccion);
        assertEquals(e.nombre() + ": el mapa no mide 128x32", Escenario.CELDAS * 2, mapa.length);
        for (int c = 0; c < Escenario.CELDAS; c++) {
            int leido = (((mapa[c * 2] & 0xFF) << 8) | (mapa[c * 2 + 1] & 0xFF));
            assertEquals(e.nombre() + ": celda " + c, esperado[c] & 0xFFFF, (leido + base) & 0xFFFF);
        }
    }

    @Test
    public void inyectarNoTocaLosBloquesNiLasRutinasOriginales() throws IOException {
        hacenFaltaLosDatos();
        Escenario e = Escenario.porNumero(0);
        Fondo f = Fondo.cargar(carpetaFondos, e, rom);
        byte[] copia = rom.clone();
        FondoService.inyectar(copia, f, new FondoService.Espacio(FondoService.ESPACIO_POR_DEFECTO));
        // el bloque de tiles original sigue donde estaba, byte a byte
        byte[] originalTiles = RncService.unpack(rom, e.bloqueTiles());
        assertArrayEquals(originalTiles, RncService.unpack(copia, e.bloqueTiles()));
        // y de la ROM sólo ha cambiado la entrada de este escenario en la tabla, más lo que va al espacio libre
        int entrada = Escenario.TABLA_RUTINAS + 4 * e.numero();
        for (int i = 0; i < 0x3F6800; i++) {
            if (rom[i] != copia[i]) {
                assertTrue(String.format("ha cambiado el byte 0x%06X, que no es de la tabla 0x510", i),
                        i >= entrada && i < entrada + 4);
            }
        }
        assertTrue("la entrada de la tabla no ha cambiado",
                FondoService.leerLong(rom, entrada) != FondoService.leerLong(copia, entrada));
    }

    @Test
    public void unEscenarioYaParcheadoNoSeVuelveAParchear() throws IOException {
        hacenFaltaLosDatos();
        Escenario e = Escenario.porNumero(0);
        Fondo f = Fondo.cargar(carpetaFondos, e, rom);
        byte[] copia = rom.clone();
        FondoService.Espacio espacio = new FondoService.Espacio("0x3F6800-0x3FFF00");
        FondoService.inyectar(copia, f, espacio);
        try {
            FondoService.inyectar(copia, f, espacio);
            org.junit.Assert.fail("tendría que haberse quejado de que ya está parcheado");
        } catch (IOException esperado) {
            assertTrue(esperado.getMessage().contains("ya lleva una rutina parcheada"));
        }
    }

    @Test
    public void pintarUnaCeldaSoloCambiaEsaCeldaSiSeLeDaTilePropio() throws IOException {
        hacenFaltaLosDatos();
        Fondo f = Fondo.cargar(carpetaFondos, Escenario.porNumero(0), rom);
        int celda = 20 * Escenario.ANCHO + 40;
        int compartido = f.slotDe(f.mapaA[celda]);
        int antes = f.usoTiles()[compartido];
        assumeTrue("hace falta una celda que comparta tile con otras", antes > 1);
        int[] comoEraElCompartido = f.tiles[compartido].clone();

        int nuevo = f.separarTile('A', celda);
        assertTrue("no hubo sitio para el tile nuevo", nuevo >= 0);
        assertEquals("la celda no apunta al tile nuevo", nuevo, f.slotDe(f.mapaA[celda]));
        assertEquals("el tile nuevo lo usa sólo esa celda", 1, f.usoTiles()[nuevo]);
        assertEquals("el compartido pierde justo esa celda", antes - 1, f.usoTiles()[compartido]);

        f.tiles[nuevo][0] = (f.tiles[nuevo][0] + 1) & 0xF;
        assertArrayEquals("pintar en el tile nuevo ha tocado el compartido", comoEraElCompartido, f.tiles[compartido]);
    }

    @Test
    public void compactarNoCambiaLoQueSeVe() throws IOException {
        hacenFaltaLosDatos();
        Fondo f = Fondo.cargar(carpetaFondos, Escenario.porNumero(0), rom);
        Bitmap antesA = f.renderIndexado('A');
        Bitmap antesB = f.renderIndexado('B');
        f.compactar();
        comparar(antesA, f.renderIndexado('A'));
        comparar(antesB, f.renderIndexado('B'));
    }

    private static void comparar(Bitmap a, Bitmap b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                assertEquals("píxel " + x + "," + y, a.indexAt(x, y), b.indexAt(x, y));
            }
        }
    }
}
