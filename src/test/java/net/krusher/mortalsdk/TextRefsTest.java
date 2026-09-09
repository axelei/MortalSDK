package net.krusher.mortalsdk;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * El juego no solo apunta a los textos: en sitios como el anunciador de "X WINS" mira la dirección del texto
 * que tiene delante contra una constante metida en el código. Si un texto se mueve y la constante se queda
 * vieja, el juego cree que tiene delante otro texto y hace lo que no es.
 */
public class TextRefsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final int TEXT = 0x1000;
    private static final int REF = 0x40;
    private static final int FREE = 0x8000;

    @After
    public void useDefaultConfig() {
        App.config = new Config();
    }

    private static void configure(Set<Integer> refs, Range... space) {
        App.config = new Config(3, Set.of(), Set.of(), new HashSet<>(Set.of(space)), Map.of(), Map.of(),
                Set.of(), null, Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), null, null, refs, null, Set.of());
    }

    /** Una ROM con un texto, un puntero absoluto que lo alcanza y un cmpi con su dirección. */
    private static byte[] rom(String text) {
        byte[] data = new byte[0x10000];
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, data, TEXT, bytes.length);
        // puntero absoluto al texto, cerca de él para que el buscador se fíe
        data[TEXT - 8] = 0;
        data[TEXT - 7] = 0;
        data[TEXT - 6] = (byte) (TEXT >> 8);
        data[TEXT - 5] = (byte) TEXT;
        // cmpi.w #TEXT,($B572).w
        data[REF - 2] = 0x0C;
        data[REF - 1] = 0x78;
        data[REF] = (byte) (TEXT >> 8);
        data[REF + 1] = (byte) TEXT;
        data[REF + 2] = (byte) 0xB5;
        data[REF + 3] = 0x72;
        return data;
    }

    private File txt(String line) throws Exception {
        File file = folder.newFile("rom.bin");
        try (PrintWriter out = new PrintWriter(new FileWriter(new File(file.getPath() + ".txt")))) {
            out.println(line);
        }
        return file;
    }

    private static int word(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    /** El texto se va a espacio libre y la constante del cmpi se va con él. */
    @Test
    public void aMovedTextTakesItsCodeReferenceAlong() throws Exception {
        configure(Set.of(REF), Range.of(FREE, FREE + 0xFF));
        byte[] original = rom("CORTO");
        byte[] data = original.clone();
        File rom = txt(String.format("%06x#0005#ESTO NO CABE NI DE BROMA#abs:%06x", TEXT, TEXT - 7));

        TexticleService.insertTexticles(rom.getPath(), data, original);
        TexticleService.fixTextRefs(data, original);

        assertEquals("el texto se tiene que haber movido", FREE, word(data, TEXT - 6));
        assertEquals("y el cmpi tiene que apuntar donde ha ido", FREE, word(data, REF));
    }

    /** Si el texto no se mueve, la constante se queda como estaba. */
    @Test
    public void aTextThatStaysPutKeepsItsReference() throws Exception {
        configure(Set.of(REF), Range.of(FREE, FREE + 0xFF));
        byte[] original = rom("CORTO");
        byte[] data = original.clone();
        File rom = txt(String.format("%06x#0005#OTRO#abs:%06x", TEXT, TEXT - 7));

        TexticleService.insertTexticles(rom.getPath(), data, original);
        TexticleService.fixTextRefs(data, original);

        assertEquals(TEXT, word(data, REF));
    }

    /** Sin la propiedad no se toca ni una palabra del código. */
    @Test
    public void withoutTheListNothingIsTouched() throws Exception {
        configure(Set.of(), Range.of(FREE, FREE + 0xFF));
        byte[] original = rom("CORTO");
        byte[] data = original.clone();
        File rom = txt(String.format("%06x#0005#ESTO NO CABE NI DE BROMA#abs:%06x", TEXT, TEXT - 7));

        TexticleService.insertTexticles(rom.getPath(), data, original);
        TexticleService.fixTextRefs(data, original);

        assertEquals(TEXT, word(data, REF));
    }

    /** Rehacerlo dos veces sobre la misma ROM da lo mismo: la referencia se lee del original. */
    @Test
    public void injectingTwiceGivesTheSameResult() throws Exception {
        configure(Set.of(REF), Range.of(FREE, FREE + 0xFF));
        byte[] original = rom("CORTO");
        byte[] once = original.clone();
        File rom = txt(String.format("%06x#0005#ESTO NO CABE NI DE BROMA#abs:%06x", TEXT, TEXT - 7));

        TexticleService.insertTexticles(rom.getPath(), once, original);
        TexticleService.fixTextRefs(once, original);

        configure(Set.of(REF), Range.of(FREE, FREE + 0xFF));
        byte[] twice = original.clone();
        TexticleService.insertTexticles(rom.getPath(), twice, original);
        TexticleService.fixTextRefs(twice, original);

        assertEquals(word(once, REF), word(twice, REF));
    }
}
