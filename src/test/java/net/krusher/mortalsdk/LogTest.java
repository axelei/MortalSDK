package net.krusher.mortalsdk;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Las cabeceras de sección, que son lo que separa un paso del proceso del siguiente. */
public class LogTest {

    private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
    private PrintStream original;

    @Before
    public void desviarLaSalida() {
        original = System.out;
        System.setOut(new PrintStream(salida, true, StandardCharsets.UTF_8));
    }

    @After
    public void devolverLaSalida() {
        System.setOut(original);
    }

    private String impreso() {
        return salida.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void unaSeccionVaEntreIgualesYConUnaLineaEnBlancoDelante() {
        Log.seccion("Inyectando textos");
        String texto = impreso();
        assertTrue("falta la línea en blanco de separación",
                texto.startsWith(System.lineSeparator()));
        assertTrue("la cabecera no sale entre iguales: " + texto,
                texto.contains("==== Inyectando textos ===="));
        assertTrue("la cabecera tiene que quedar en su propia línea",
                texto.endsWith("====" + System.lineSeparator()));
    }

    @Test
    public void laSeccionTambienAdmiteParametros() {
        Log.seccion("Inyectando {0} fondos", 3);
        assertTrue(impreso().contains("==== Inyectando 3 fondos ===="));
    }

    @Test
    public void unaLineaNormalNoLlevaAdornos() {
        Log.pnl("Leyendo archivo: rom.bin");
        String texto = impreso();
        assertFalse("una línea normal no debería llevar iguales", texto.contains("===="));
        assertEquals("Leyendo archivo: rom.bin" + System.lineSeparator(), texto);
    }
}
