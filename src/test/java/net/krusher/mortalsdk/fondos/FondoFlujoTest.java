package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.Bitmap;
import net.krusher.mortalsdk.Png;
import net.krusher.mortalsdk.Range;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/** Los dos pasos que hacen {@code x} e {@code i} con los fondos. */
public class FondoFlujoTest {

    @Rule
    public TemporaryFolder temporal = new TemporaryFolder();

    private static File carpetaFondos;
    private static byte[] rom;
    private final Escenario patio = Escenario.porNumero(0);

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

    private File copiaDelPatio() throws IOException {
        assumeTrue("no está la carpeta fondos/ de KombateSDK al lado; se salta", carpetaFondos != null);
        File destino = temporal.getRoot();
        Path origen = new File(carpetaFondos, patio.carpeta()).toPath();
        try (Stream<Path> ficheros = Files.walk(origen)) {
            for (Path p : ficheros.toList()) {
                Path relativa = origen.relativize(p);
                Path fin = destino.toPath().resolve(patio.carpeta()).resolve(relativa);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(fin);
                } else {
                    Files.createDirectories(fin.getParent());
                    Files.copy(p, fin);
                }
            }
        }
        return destino;
    }

    // ------------------------------------------------------------------ extraer

    @Test
    public void extraerRehaceLosFicherosTalComoEstaban() throws IOException {
        File carpeta = copiaDelPatio();
        File plano = new File(carpeta, patio.carpeta() + "/plano_A.png");
        assertTrue(plano.delete());

        FondoService.extraer(carpeta, rom);

        assertTrue("no ha vuelto a escribir el plano", plano.isFile());
        // el PNG que había lo escribió otra herramienta, así que los bytes no tienen por qué coincidir;
        // lo que sí tiene que coincidir es lo que se ve, píxel a píxel
        Bitmap rehecho = Png.read(plano);
        Bitmap original = Png.read(new File(carpetaFondos, patio.carpeta() + "/plano_A.png"));
        assertEquals(original.getWidth(), rehecho.getWidth());
        assertEquals(original.getHeight(), rehecho.getHeight());
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                assertEquals("píxel " + x + "," + y, original.indexAt(x, y), rehecho.indexAt(x, y));
            }
        }
        // y extraer dos veces seguidas sí tiene que dar exactamente lo mismo
        byte[] primera = Files.readAllBytes(plano.toPath());
        FondoService.extraer(carpeta, rom);
        assertArrayEquals("extraer no es estable: dos pasadas dan ficheros distintos",
                primera, Files.readAllBytes(plano.toPath()));
    }

    @Test
    public void extraerNoDeclaraEditadoLoQueSoloHaRehecho() throws IOException {
        File carpeta = copiaDelPatio();
        FondoService.extraer(carpeta, rom);
        assertFalse("rehacer los ficheros no es haberlos editado",
                FondoService.editado(carpeta, patio));
    }

    @Test
    public void extraerNoPisaLoQueElEditorHaGuardado() throws IOException {
        File carpeta = copiaDelPatio();
        File marca = new File(carpeta, patio.carpeta() + "/fondo.properties");
        Files.writeString(marca.toPath(), "tiles=752\nescenario=0\n");
        File tiles = new File(carpeta, patio.carpeta() + "/tiles.png");
        Fondo.cargar(carpeta, patio, rom, false).guardar(false);   // deja un tiles.png coherente
        byte[] antes = Files.readAllBytes(tiles.toPath());

        FondoService.extraer(carpeta, rom);

        assertArrayEquals("le ha pasado por encima a lo guardado", antes, Files.readAllBytes(tiles.toPath()));
    }

    @Test
    public void sinCarpetaDeFondosNoPasaNada() throws IOException {
        FondoService.extraer(new File(temporal.getRoot(), "no-existe"), new byte[0x400000]);
    }

    // ------------------------------------------------------------------ inyectar

    @Test
    public void sinFondosEditadosLaRomNoSeToca() throws IOException {
        File carpeta = copiaDelPatio();
        byte[] copia = rom.clone();
        assertEquals(0, FondoService.inyectar(carpeta, copia, List.of(Range.of(0x3F6800, 0x3FFF00))));
        assertArrayEquals("no debería haber tocado un solo byte", rom, copia);
    }

    @Test
    public void unFondoEditadoSinSitioDondePonerloSeCanta() throws IOException {
        File carpeta = copiaDelPatio();
        Files.writeString(new File(carpeta, patio.carpeta() + "/fondo.properties").toPath(), "tiles=752\n");
        Fondo.cargar(carpeta, patio, rom, false).guardar(true);
        try {
            FondoService.inyectar(carpeta, rom.clone(), Set.of());
            fail("tendría que haber pedido la propiedad fondosSpace");
        } catch (IOException esperado) {
            assertTrue(esperado.getMessage(), esperado.getMessage().contains("fondosSpace"));
        }
    }

    @Test
    public void loEditadoEntraEnLaRomYSoloCambiaSuEntradaDeLaTabla() throws IOException {
        File carpeta = copiaDelPatio();
        Fondo fondo = Fondo.cargar(carpeta, patio, rom, false);
        fondo.guardar(true);
        byte[] copia = rom.clone();

        assertEquals(1, FondoService.inyectar(carpeta, copia, List.of(Range.of(0x3F6800, 0x3FFF00))));

        int entrada = Escenario.TABLA_RUTINAS + 4 * patio.numero();
        int rutina = FondoService.leerLong(copia, entrada);
        assertTrue("la tabla no apunta al espacio libre", rutina >= 0x3F6800 && rutina < 0x3FFF00);
        assertEquals("la rutina nueva no llama a la original", patio.rutinaOriginal(),
                FondoService.leerLong(copia, rutina + 2));
        for (int i = 0; i < 0x3F6800; i++) {
            if (rom[i] != copia[i]) {
                assertTrue(String.format("ha cambiado el byte 0x%06X, que no es de la tabla 0x510", i),
                        i >= entrada && i < entrada + 4);
            }
        }
    }

    @Test
    public void elEspacioTambienSeLeeDeLosRangosDeLaConfiguracion() throws IOException {
        byte[] romVacia = new byte[0x400000];
        FondoService.Espacio espacio = new FondoService.Espacio(List.of(Range.of(0x3F6800, 0x3F6900)));
        assertEquals(0x3F6800, espacio.reservar(romVacia, 0x40));
        assertEquals(0x3F6840, espacio.reservar(romVacia, 0x40));
    }
}
