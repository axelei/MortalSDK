package net.krusher.mortalsdk;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TexticleServiceTest {

    @Before
    public void useDefaultConfig() {
        App.config = new Config();
    }

    private static void withFixed(Range... fixed) {
        App.config = new Config(4, Set.of(), Set.of(), new java.util.HashSet<>(), Map.of(), Map.of(),
                Set.of(), null, Set.of(), Set.of(), Set.of(), Set.of(fixed));
    }

    /** Escribe una cadena terminada en cero. */
    private static void putText(byte[] rom, int at, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, rom, at, bytes.length);
        rom[at + bytes.length] = 0;
    }

    /** Escribe un lea (d16,PC),a0 en {@code at} apuntando a {@code target}. */
    private static void putLea(byte[] rom, int at, int target) {
        rom[at] = 0x41;
        rom[at + 1] = (byte) 0xFA;
        int displacement = target - (at + 2);
        rom[at + 2] = (byte) (displacement >> 8);
        rom[at + 3] = (byte) displacement;
    }

    @Test
    public void findsAPcRelativeLea() {
        byte[] rom = new byte[0x2000];
        putLea(rom, 0x100, 0x800);
        Texticle.Pointer pointer = TexticleService.findPointer(0x800, rom);
        assertTrue("debería reconocerse como lea", pointer.lea());
        assertEquals(0x100, pointer.address());
    }

    /** Un lea vale más que una coincidencia suelta de tres bytes, que puede ser casualidad. */
    @Test
    public void prefersTheLeaOverAnAbsoluteMatch() {
        byte[] rom = new byte[0x2000];
        rom[0x40] = 0x00;
        rom[0x41] = 0x08;
        rom[0x42] = 0x00;      // 000800 suelto, antes del lea
        putLea(rom, 0x100, 0x800);
        assertTrue(TexticleService.findPointer(0x800, rom).lea());
    }

    @Test
    public void fallsBackToTheAbsolutePointer() {
        byte[] rom = new byte[0x2000];
        rom[0x41] = 0x00;
        rom[0x42] = 0x08;
        rom[0x43] = 0x00;
        Texticle.Pointer pointer = TexticleService.findPointer(0x800, rom);
        assertFalse(pointer.lea());
        assertEquals(0x41, pointer.address());
    }

    /**
     * Tres bytes sueltos que valen lo mismo que la direccion no son un puntero: uno de verdad es la parte
     * baja de un long 00xxxxxx, o sea que empieza en impar y lleva delante un cero.
     */
    @Test
    public void ignoresThreeBytesThatOnlyLookLikeAPointer() {
        byte[] rom = new byte[0x2000];
        rom[0x40] = 0x00;      // en par: seria la parte alta de un long, no la baja
        rom[0x41] = 0x08;
        rom[0x42] = 0x00;
        rom[0x50] = 0x11;      // en impar pero sin el cero delante
        rom[0x51] = 0x00;
        rom[0x52] = 0x08;
        rom[0x53] = 0x00;
        assertNull(TexticleService.findPointer(0x800, rom));
    }

    /** Un puntero de verdad esta en la tabla que hay al lado de su texto, no a media ROM de distancia. */
    @Test
    public void ignoresAnAbsoluteMatchTooFarFromItsText() {
        byte[] rom = new byte[0x400000];
        int text = 0x3F0000;
        rom[0x41] = (byte) ((text >> 16) & 0xFF);
        rom[0x42] = (byte) ((text >> 8) & 0xFF);
        rom[0x43] = (byte) (text & 0xFF);
        assertNull(TexticleService.findPointer(text, rom));
        // el mismo valor pegado al texto si vale
        rom[0x3EFF01] = rom[0x41];
        rom[0x3EFF02] = rom[0x42];
        rom[0x3EFF03] = rom[0x43];
        assertEquals(0x3EFF01, TexticleService.findPointer(text, rom).address());
    }

    @Test
    public void writesBackBothKindsOfPointer() {
        byte[] rom = new byte[0x2000];
        putLea(rom, 0x100, 0x800);

        assertTrue(TexticleService.writePointer(new Texticle.Pointer(0x100, true), 0x900, rom));
        assertEquals(0x100, TexticleService.findLeaAddress(0x900, rom).intValue());

        assertTrue(TexticleService.writePointer(new Texticle.Pointer(0x41, false), 0x1234, rom));
        // findPointerAddress devuelve dónde está el puntero, no lo que vale
        assertEquals(0x41, TexticleService.findPointerAddress(0x1234, rom).intValue());
    }

    /**
     * La distancia de un lea son 16 bits con signo: más allá de 32 KB hay que desviarlo por un trampolín, y
     * si no hay hueco de código donde ponerlo no se puede reubicar el texto.
     */
    @Test
    public void refusesToMoveALeaTooFarAwayWithNowhereToPutTheTrampoline() {
        byte[] rom = new byte[0x400000];
        putLea(rom, 0x100, 0x800);
        assertFalse(TexticleService.writePointer(new Texticle.Pointer(0x100, true), 0x3F0000, rom));
        // y el lea se queda como estaba, apuntando a 0x800
        assertEquals(0x100, TexticleService.findLeaAddress(0x800, rom).intValue());
    }

    /** Con un hueco cerca, el lea se cambia por un bsr al trampolín, que carga la dirección entera. */
    @Test
    public void divertsALeaThroughATrampoline() {
        App.config = new Config(4, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of(), null,
                Set.of(), Set.of(Range.of(0x1000, 0x1020)), Set.of(), Set.of());
        byte[] rom = new byte[0x400000];
        putLea(rom, 0x100, 0x800);
        rom[0x100] = 0x47;                 // lea (d16,PC),a3, para comprobar que se respeta el registro

        assertTrue(TexticleService.writePointer(new Texticle.Pointer(0x100, true), 0x3F1234, rom));

        assertEquals(0x6100, TexticleService.readWord(rom, 0x100));               // bsr.w
        int trampoline = 0x102 + (short) TexticleService.readWord(rom, 0x102);
        assertEquals(0x1000, trampoline);
        assertEquals(0x47F9, TexticleService.readWord(rom, trampoline));          // lea (xxx).l,a3
        assertEquals(0x003F, TexticleService.readWord(rom, trampoline + 2));
        assertEquals(0x1234, TexticleService.readWord(rom, trampoline + 4));
        assertEquals(0x4E75, TexticleService.readWord(rom, trampoline + 6));      // rts
    }

    /**
     * Los créditos y sitios así no tienen un puntero por línea: el juego apunta al primero y va sacando los
     * demás de terminador en terminador, así que forman una cadena que sólo se puede mover entera.
     */
    @Test
    public void gathersTheChainThatHangsFromATextWithoutOwnPointers() {
        byte[] rom = new byte[0x2000];
        putText(rom, 0x800, "PRIMERO");
        putText(rom, 0x808, "SEGUNDO");
        putText(rom, 0x810, "TERCERO");
        putLea(rom, 0x100, 0x800);

        Texticle head = new Texticle(0x800, 7, "PRIMERO", new Texticle.Pointer(0x100, true));
        List<Texticle> chain = TexticleService.chainOf(head, List.of(head), rom,
                TexticleService.pointedAddresses(rom));
        assertEquals(3, chain.size());
        assertEquals(0x808, chain.get(1).address());
        assertEquals("SEGUNDO", chain.get(1).text());   // el que falta en el fichero sale de la ROM
        assertEquals(0x810, chain.get(2).address());
    }

    /** Si el de detrás tiene puntero propio, el juego llega a él por su cuenta y no hay cadena. */
    @Test
    public void doesNotChainATextThatIsPointedAtOnItsOwn() {
        byte[] rom = new byte[0x2000];
        putText(rom, 0x800, "PRIMERO");
        putText(rom, 0x808, "SEGUNDO");
        putLea(rom, 0x100, 0x800);
        putLea(rom, 0x110, 0x808);

        Texticle head = new Texticle(0x800, 7, "PRIMERO", new Texticle.Pointer(0x100, true));
        assertEquals(1, TexticleService.chainOf(head, List.of(head), rom,
                TexticleService.pointedAddresses(rom)).size());
    }

    /**
     * Los ficheros de textos viejos traen punteros que no lo son, y usarlos estropea la ROM. Se repasan al
     * inyectar, asi que no hay que volver a extraer.
     */
    @Test
    public void throwsAwayThePointersOfTheFileThatAreNotReallyPointers() {
        byte[] rom = new byte[0x400000];
        putLea(rom, 0x100, 0x800);
        rom[0x201] = 0x00;
        rom[0x202] = 0x08;
        rom[0x203] = 0x00;      // puntero absoluto de verdad a 0x800
        rom[0x3F0000] = 0x00;
        rom[0x3F0001] = 0x08;
        rom[0x3F0002] = 0x00;   // tres bytes que valen lo mismo, en medio de los graficos

        List<Texticle> checked = TexticleService.checkPointers(List.of(
                new Texticle(0x800, 4, "UNO", new Texticle.Pointer(0x100, true)),
                new Texticle(0x800, 4, "DOS", new Texticle.Pointer(0x201, false)),
                new Texticle(0x800, 4, "TRES", new Texticle.Pointer(0x3F0000, false)),
                new Texticle(0x800, 4, "CUATRO", new Texticle.Pointer(0x300, true))), rom);

        assertEquals(0x100, checked.get(0).pointer().address());   // el lea se mantiene
        assertEquals(0x201, checked.get(1).pointer().address());   // el absoluto de verdad tambien
        assertNull(checked.get(2).pointer());                      // la casualidad se tira
        assertNull(checked.get(3).pointer());                      // y el lea que no esta donde dice
    }

    /** Un campo de tamaño fijo sale entero y con su tamaño, no por lo que ocupe el texto que tenga. */
    @Test
    public void pullsOutAFixedFieldWholeAndWithItsOwnSize() {
        withFixed(Range.of(0x120, 0x14F));
        byte[] rom = new byte[0x2000];
        java.util.Arrays.fill(rom, 0x120, 0x150, Texticle.ASCII_SPACE);
        System.arraycopy("MORTAL KOMBAT".getBytes(StandardCharsets.ISO_8859_1), 0, rom, 0x120, 13);

        Texticle campo = TexticleService.findTexticles(rom).stream()
                .filter(t -> t.address() == 0x120).findFirst().orElseThrow();
        assertEquals(48, campo.size());
        assertEquals("MORTAL KOMBAT                                   ", campo.text());
        assertNull("no se le busca puntero: el juego lo lee siempre de ahí", campo.pointer());
    }

    /** Saltarse los bytes de un campo fijo tiene que cortar el texto de al lado, no pegarse con él. */
    @Test
    public void doesNotWeldTogetherTheTextsOnEachSideOfAFixedField() {
        withFixed(Range.of(0x120, 0x14F));
        byte[] rom = new byte[0x2000];
        System.arraycopy("ANTES".getBytes(StandardCharsets.ISO_8859_1), 0, rom, 0x118, 5);
        java.util.Arrays.fill(rom, 0x120, 0x150, (byte) 'X');
        System.arraycopy("DESPUES".getBytes(StandardCharsets.ISO_8859_1), 0, rom, 0x150, 7);

        List<Texticle> texts = TexticleService.findTexticles(rom);
        assertTrue("el de antes sale con su dirección",
                texts.stream().anyMatch(t -> t.address() == 0x118 && t.text().equals("ANTES")));
        assertTrue("el de después también",
                texts.stream().anyMatch(t -> t.address() == 0x150 && t.text().equals("DESPUES")));
    }

    /** Al inyectar se rellena con espacios lo que sobre y se corta lo que se pase, sin salirse del campo. */
    @Test
    public void writesAFixedFieldPaddedAndNeverPastItsEnd() throws Exception {
        withFixed(Range.of(0x120, 0x12F));
        byte[] rom = new byte[0x2000];
        java.util.Arrays.fill(rom, 0x130, 0x140, (byte) 0x7E);      // lo de detrás no se toca

        File file = File.createTempFile("textos", "");
        file.deleteOnExit();
        String base = file.getAbsolutePath();
        java.nio.file.Files.writeString(java.nio.file.Path.of(base + ".txt"),
                "000120#0016#CORTO" + System.lineSeparator());
        TexticleService.insertTexticles(base, rom, rom.clone());
        assertEquals("CORTO           ",
                new String(rom, 0x120, 16, StandardCharsets.ISO_8859_1));

        java.nio.file.Files.writeString(java.nio.file.Path.of(base + ".txt"),
                "000120#0016#ESTE NOMBRE NO CABE NI DE BROMA" + System.lineSeparator());
        TexticleService.insertTexticles(base, rom, rom.clone());
        assertEquals("ESTE NOMBRE NO C",
                new String(rom, 0x120, 16, StandardCharsets.ISO_8859_1));
        assertEquals("no se sale del campo", 0x7E, rom[0x130]);
    }

    @Test
    public void readsAndWritesTheLineFormat() {
        Texticle texticle = new Texticle(0x2fe6, 33, "MORTAL KOMBAT*CAST OF CHARACTERS:",
                new Texticle.Pointer(0x5a00, true));
        assertEquals("002fe6#0033#MORTAL KOMBAT*CAST OF CHARACTERS:#lea:005a00", texticle.format());

        String[] parts = texticle.format().split("#");
        assertEquals(0x2fe6, Texticle.parseAddress(parts[0]));
        Texticle.Pointer back = Texticle.Pointer.parse(parts[3]);
        assertTrue(back.lea());
        assertEquals(0x5a00, back.address());
    }

    @Test
    public void writesAnAbsolutePointerWithoutTheLeaMark() {
        Texticle texticle = new Texticle(0x100, 4, "WOOD", new Texticle.Pointer(0x1663b, false));
        assertEquals("000100#0004#WOOD#abs:01663b", texticle.format());
        assertFalse(Texticle.Pointer.parse("abs:01663b").lea());
    }

    @Test
    public void acceptsATexticleWithoutPointer() {
        assertEquals("000100#0004#WOOD", new Texticle(0x100, 4, "WOOD", null).format());
        assertNull(Texticle.Pointer.parse("  "));
    }

}
