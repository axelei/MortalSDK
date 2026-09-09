package net.krusher.mortalsdk.fondos;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Lo que decide el subcomando antes de tocar la ROM: qué escenarios entran y con qué opciones. */
public class FondoCliTest {

    @Rule
    public TemporaryFolder carpeta = new TemporaryFolder();

    @Test
    public void lasOpcionesSeLeenComoClaveValorYLasSueltasComoInterruptor() {
        Map<String, String> o = FondoCli.opciones(
                new String[]{"fondos-rom", "fondos", "rom.bin", "salida.bin", "escenarios=0,4", "FORZAR",
                        "espacio=0x2D6C90-0x2D9FF0"}, 4);
        assertEquals("0,4", o.get("escenarios"));
        assertEquals("0x2D6C90-0x2D9FF0", o.get("espacio"));
        assertTrue("una opción suelta tiene que contar como puesta", o.containsKey("forzar"));
        assertEquals("", o.get("forzar"));
        assertEquals(3, o.size());
    }

    @Test
    public void sinOpcionesNoHayOpciones() {
        assertTrue(FondoCli.opciones(new String[]{"fondos-rom", "a", "b", "c"}, 4).isEmpty());
    }

    @Test
    public void sinDecirNadaEntranLosSieteEscenarios() {
        assertEquals(7, FondoCli.cuales(null).size());
        assertEquals(7, FondoCli.cuales("  ").size());
    }

    @Test
    public void seEligenLosEscenariosPorNumeroYEnElOrdenQueSePiden() {
        List<Escenario> elegidos = FondoCli.cuales("4, 0");
        assertEquals(2, elegidos.size());
        assertEquals(4, elegidos.get(0).numero());
        assertEquals("patio", elegidos.get(1).nombre());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unEscenarioQueNoExisteSeCanta() {
        FondoCli.cuales("9");
    }

    @Test
    public void soloCuentaComoEditadoLoQueElEditorHaGuardado() throws IOException {
        Escenario patio = Escenario.porNumero(0);
        File raiz = carpeta.getRoot();
        assertFalse("una carpeta que no existe no está editada", FondoCli.editado(raiz, patio));
        File suya = carpeta.newFolder(patio.carpeta());
        assertFalse("con la carpeta vacía tampoco", FondoCli.editado(raiz, patio));
        assertTrue(new File(suya, "fondo.properties").createNewFile());
        assertTrue("con lo que guarda el editor, sí", FondoCli.editado(raiz, patio));
    }
}
