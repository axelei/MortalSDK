package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pone una intro delante del juego: al encender se ve la intro, y START (o A), o pasado un tiempo, entra al
 * juego.
 * <p>
 * El juego se queda donde está, porque su código está lleno de direcciones absolutas y no se puede mover. La
 * intro es una ROM de Mega Drive independiente que también espera vivir en 0x000000, así que se parte en
 * trozos (código, 16 fotogramas, la muestra PCM, el driver Z80/XGM), los fotogramas se comprimen con RLE, y
 * los trozos se reparten por el relleno que el juego no lee. Las pocas direcciones absolutas del código de la
 * intro se recolocan a donde haya caído cada trozo. Después:
 * <ul>
 *   <li>el vector RESET del juego (0x000004) pasa a apuntar a un arranque nuestro, que vive en la propia
 *       tabla de vectores de la intro, muerta porque la intro ya no arranca desde 0;</li>
 *   <li>ese arranque desbloquea el TMSS, pone a cero el contador de fotogramas y salta a la intro;</li>
 *   <li>la espera de vblank de la intro se desvía a la nuestra, que lee el mando en cada fotograma, así que
 *       START salta la intro desde el primer momento y no sólo al final;</li>
 *   <li>la rutina que vuelca fotogramas se desvía a nuestro descompresor, que descomprime directamente
 *       contra el puerto de datos del VDP, sin búfer;</li>
 *   <li>el bucle final de la intro pasa por nuestra comprobación, que sale a los {@value #ESPERA}
 *       fotogramas;</li>
 *   <li>al salir se callan el PSG y el YM2612, se resetea el Z80, se apaga la pantalla y se limpian los
 *       registros del mando para que el juego vea un arranque en frío, y se salta a su entrada original.</li>
 * </ul>
 * <p>
 * El epílogo de salida se copia a RAM y se ejecuta desde allí, porque habilitar la SRAM puede tapar la parte
 * alta de la ROM, y en una consola de verdad eso incluiría al propio código que la habilita.
 * <p>
 * Esto va al final de la inyección, sobre una ROM que el resto ya ha reescrito, así que cada trozo se
 * comprueba contra la ROM original: si un byte de la zona ya lo había cambiado un paso anterior, se aborta en
 * vez de comerse en silencio un texto reubicado. Por lo mismo, el espacio libre de {@code spaceRanges}, que
 * es de donde tiran los textos y los bloques, se quita del reparto de la intro.
 * <p>
 * Portado del IntroInserter de CholeilSDK, que a su vez viene de insertar_intro.py (ScorpioN-MsX). Lo que
 * era propio de aquel juego (quitar el logo de arranque, la lista de huecos medida a mano, la comprobación
 * del código de producto) no ha venido: aquí los huecos se indican en la configuración.
 */
public final class IntroService {

    // ---- la intro: "Charnego Translations INTRO FINAL (XGM, con fundido)" ----
    // Dónde está cada trozo dentro de la ROM de la intro, y las direcciones absolutas de su código 68000
    // que hay que recolocar cuando los trozos se mueven.

    private static final int INTRO_SIZE = 622630;
    private static final int ENTRADA = 0x00200;   // punto de entrada de la intro
    private static final int BUCLE = 0x002EA;     // principio de su bucle final
    private static final int BRA = 0x002EE;       // el bra.w que lo cierra
    private static final int VSYNC = 0x003FA;     // su espera de vblank
    private static final int SUBIR = 0x00358;     // su rutina de volcado de fotogramas
    private static final int ESPERA = 60;         // fotogramas parado en la última pantalla

    private static final int COD_OFF = 0x00200;
    private static final int COD_TAM = 0x003CE;   // código y paletas
    private static final int FR_OFF = 0x005CE;
    private static final int FR_TAM = 0x08C00;
    private static final int FR_N = 16;
    private static final int PCM_OFF = 0x8C600;
    private static final int PCM_TAM = 0x09E92;   // muestra PCM
    private static final int VAC_OFF = 0x96500;
    private static final int VAC_TAM = 0x00100;   // muestra vacía
    private static final int DL_OFF = 0x96600;
    private static final int DL_TAM = 0x01A26;    // driver Z80 y librería XGM

    /** posición dentro de la intro -> dirección absoluta que hay guardada ahí. */
    private static final int[][] RELOCS = {
            {0x00226, 0x097DAE},   // jsr  XGM_init
            {0x0023E, 0x097F20},   // jsr  XGM_setPCM
            {0x00232, 0x08C600},   // puntero a los datos XGM (música y muestras)
            {0x002CA, 0x097F6C},   // jsr  XGM_playPCM
            {0x002DE, 0x097FB8},   // jsr  XGM_vblankProcess
            {0x97DC8, 0x096600},   // lea  driver Z80 del XGM
            {0x97DE4, 0x096500},   // muestra vacía por defecto
            {0x97E86, 0x096500},   // muestra vacía por defecto
    };

    private static final int BRA_W = 0x6000;

    /** RAM de trabajo: contador de fotogramas en +0, índice de fotograma en +4. */
    private static final int CONT_RAM = 0xFF0100;

    private static final int SP_VECTOR = 0x00;
    private static final int RESET_VECTOR = 0x04;
    private static final int CHECKSUM_FIELD = 0x18E;
    private static final int SRAM_MARK = 0x1B0;
    private static final int SRAM_START = 0x1B4;

    private IntroService() {}

    /**
     * Mete la intro en la ROM. Si la configuración no dice cuál, no se hace nada.
     *
     * @param fileData     la ROM que se está construyendo, ya con todo lo demás inyectado
     * @param originalData la ROM tal y como estaba, para no pisar lo que hayan escrito los pasos anteriores
     */
    public static void inject(byte[] fileData, byte[] originalData) throws IOException {
        if (App.config.intro() == null || App.config.intro().isBlank()) {
            return;
        }
        // Si se ha pedido intro y no se puede poner, se para: es lo que se ha pedido, y un aviso suelto
        // entre el resto de la salida se pasa por alto con facilidad.
        File file = new File(App.config.intro());
        if (!file.exists()) {
            throw new IOException("No se encuentra la intro " + file.getAbsolutePath()
                    + ". Quita la propiedad intro de la configuración o pon el fichero ahí.");
        }
        if (App.config.introSpace().isEmpty()) {
            throw new IOException("Hay una intro configurada pero no introSpace, así que no hay dónde"
                    + " meterla. Indica las zonas de la ROM que puede usar.");
        }
        place(fileData, originalData, Files.readAllBytes(file.toPath()));
    }

    static void place(byte[] fileData, byte[] originalData, byte[] intro) throws IOException {
        checkIntro(intro);

        int gameSp = readU32(fileData, SP_VECTOR);
        int gamePc = readU32(fileData, RESET_VECTOR);
        if (gamePc >= fileData.length) {
            throw new IOException(String.format(
                    "El vector RESET de la ROM (0x%06X) apunta fuera de ella. ¿Ya tiene una intro?", gamePc));
        }

        byte[] code = Arrays.copyOfRange(intro, COD_OFF, COD_OFF + COD_TAM);
        byte[] driver = Arrays.copyOfRange(intro, DL_OFF, DL_OFF + DL_TAM);
        byte[] pcm = Arrays.copyOfRange(intro, PCM_OFF, PCM_OFF + PCM_TAM);
        byte[] empty = Arrays.copyOfRange(intro, VAC_OFF, VAC_OFF + VAC_TAM);

        byte[][] frames = new byte[FR_N][];
        int raw = 0;
        int packed = 0;
        for (int i = 0; i < FR_N; i++) {
            byte[] frame = Arrays.copyOfRange(intro, FR_OFF + i * FR_TAM, FR_OFF + (i + 1) * FR_TAM);
            frames[i] = rleCompress(frame);
            if (!Arrays.equals(rleExpand(frames[i]), frame)) {   // red de seguridad
                throw new IOException("El compresor RLE no es reversible en el fotograma " + i);
            }
            raw += frame.length;
            packed += frames[i].length;
        }

        // El arranque cambia de tamaño según haga falta remapear la SRAM, y eso depende de si la ROM crece:
        // se reparte hasta que las dos cosas coincidan. Aquí la ROM nunca crece, pero se conserva el bucle.
        boolean sram = fileData[SRAM_MARK] == 'R' && fileData[SRAM_MARK + 1] == 'A';
        int sramStart = sram ? readU32(fileData, SRAM_START) : 0;

        boolean remap = sram;
        Placer placer = null;
        Map<String, Integer> where = null;
        int nearSize = 0;
        int farSize = 0;
        for (int pass = 0; pass < 3; pass++) {
            placer = new Placer(pool(), fileData.length);

            Stub probe = buildStub(0, 0, 0, 0, gameSp, gamePc, CONT_RAM, remap, new int[FR_N]);
            nearSize = probe.near.length;
            farSize = probe.far.length;

            // De mayor a menor: si no, los trozos pequeños fragmentan los huecos grandes y deja de caber la
            // muestra PCM o un fotograma gordo. El arranque "cerca" tiene que quedar a un bra.w del código,
            // así que comparten bloque.
            List<Object[]> pieces = new ArrayList<>();
            pieces.add(new Object[]{"codigo", COD_TAM + nearSize, 2});
            pieces.add(new Object[]{"lejos", farSize, 2});
            pieces.add(new Object[]{"pcm", pcm.length, 256});
            pieces.add(new Object[]{"vacio", empty.length, 256});
            pieces.add(new Object[]{"driver", driver.length, 2});
            for (int i = 0; i < FR_N; i++) {
                pieces.add(new Object[]{"f" + i, frames[i].length, 2});
            }
            pieces.sort((x, y) -> (Integer) y[1] - (Integer) x[1]);

            where = new LinkedHashMap<>();
            for (Object[] piece : pieces) {
                where.put((String) piece[0], placer.place((String) piece[0], (Integer) piece[1], (Integer) piece[2]));
            }

            boolean grown = sram && placer.end > sramStart;
            if (grown == remap) {
                break;
            }
            remap = grown;
        }

        int atCode = where.get("codigo");
        int atNear = atCode + COD_TAM;
        int atFar = where.get("lejos");
        int atPcm = where.get("pcm");
        int atEmpty = where.get("vacio");
        int atDriver = where.get("driver");
        int[] atFrames = new int[FR_N];
        for (int i = 0; i < FR_N; i++) {
            atFrames[i] = where.get("f" + i);
        }

        // segunda pasada, ya con las direcciones de verdad
        Stub stub = buildStub(atNear, atFar, atCode + (ENTRADA - COD_OFF), atCode + (BUCLE - COD_OFF),
                gameSp, gamePc, CONT_RAM, remap, atFrames);
        if (stub.near.length != nearSize || stub.far.length != farSize) {
            throw new IOException("Error interno: el arranque ha cambiado de tamaño entre pasadas");
        }

        // ---- recolocar las direcciones absolutas de la intro ----
        for (int[] reloc : RELOCS) {
            int at = reloc[0];
            int value = reloc[1];
            int moved;
            if (value >= DL_OFF && value < DL_OFF + DL_TAM) {
                moved = atDriver + (value - DL_OFF);
            } else if (value >= PCM_OFF && value < PCM_OFF + PCM_TAM) {
                moved = atPcm + (value - PCM_OFF);
            } else if (value >= VAC_OFF && value < VAC_OFF + VAC_TAM) {
                moved = atEmpty + (value - VAC_OFF);
            } else {
                throw new IOException(String.format("No se sabe a qué trozo pertenece 0x%06X", value));
            }
            if (at >= COD_OFF && at < COD_OFF + COD_TAM) {
                writeU32(code, at - COD_OFF, moved);
            } else if (at >= DL_OFF && at < DL_OFF + DL_TAM) {
                writeU32(driver, at - DL_OFF, moved);
            } else {
                throw new IOException(String.format("La recolocación de 0x%05X cae fuera de los trozos", at));
            }
        }

        // ---- parches dentro del propio código de la intro ----
        writeU16(code, SUBIR - COD_OFF, 0x4EF9);                          // su volcado de fotogramas ->
        writeU32(code, SUBIR - COD_OFF + 2, stub.labels.get("descomp"));  // nuestro descompresor
        writeU16(code, VSYNC - COD_OFF, 0x4EF9);                          // su espera de vblank ->
        writeU32(code, VSYNC - COD_OFF + 2, stub.labels.get("vsync"));    // la nuestra, que lee el mando
        int displacement = stub.labels.get("comprobar") - (atCode + (BRA - COD_OFF) + 2);
        writeU16(code, BRA - COD_OFF, BRA_W);                             // el bra.w del bucle final ->
        writeU16(code, BRA - COD_OFF + 2, displacement);                  // nuestra comprobación

        // ---- escribir ----
        List<Object[]> writes = new ArrayList<>();
        writes.add(new Object[]{"código", atCode, code});
        writes.add(new Object[]{"arranque", atNear, stub.near});
        writes.add(new Object[]{"salida", atFar, stub.far});
        writes.add(new Object[]{"muestra PCM", atPcm, pcm});
        writes.add(new Object[]{"muestra vacía", atEmpty, empty});
        writes.add(new Object[]{"driver Z80", atDriver, driver});
        for (int i = 0; i < FR_N; i++) {
            writes.add(new Object[]{"fotograma " + i, atFrames[i], frames[i]});
        }

        assertUntouched(writes, fileData, originalData);
        for (Object[] write : writes) {
            byte[] data = (byte[]) write[2];
            System.arraycopy(data, 0, fileData, (Integer) write[1], data.length);
        }

        writeU32(fileData, RESET_VECTOR, stub.labels.get("entrada"));   // vector RESET -> nuestro arranque

        Log.pnl("Intro puesta: {0} KB en total, {1} KB de fotogramas comprimidos (de {2} KB).",
                (COD_TAM + nearSize + farSize + pcm.length + empty.length + driver.length + packed) / 1024,
                packed / 1024, raw / 1024);
        Log.pnl("   código {0}  arranque {1}  salida {2}  PCM {3}  driver {4}",
                hex(atCode), hex(atNear), hex(atFar), hex(atPcm), hex(atDriver));
        Log.pnl("   entrada original del juego: SP={0} PC={1}", hex(gameSp), hex(gamePc));
        if (remap) {
            Log.pnl("   la SRAM de {0} se remapea al salir", hex(sramStart));
        }
    }

    private static String hex(int value) {
        return String.format("%06x", value);
    }

    // ---- comprobaciones ----

    private static void checkIntro(byte[] intro) throws IOException {
        if (intro.length != INTRO_SIZE) {
            throw new IOException("Intro desconocida: se esperaban " + INTRO_SIZE + " bytes y tiene "
                    + intro.length + ". Recolocar a ciegas una intro que no se conoce daría una ROM rota.");
        }
        for (int[] reloc : RELOCS) {
            if (readU32(intro, reloc[0]) != reloc[1]) {
                throw new IOException(String.format(
                        "Intro desconocida: se esperaba 0x%06X en 0x%05X y hay 0x%06X",
                        reloc[1], reloc[0], readU32(intro, reloc[0])));
            }
        }
        if (readU16(intro, BRA) != BRA_W || (short) readU16(intro, BRA + 2) != BUCLE - BRA - 2) {
            throw new IOException("Intro desconocida: su bucle final no está donde debería");
        }
    }

    /**
     * Nada de lo que escribe la intro puede caer sobre un byte que ya haya cambiado un paso anterior. Los
     * huecos de la configuración se midieron sobre la ROM original, así que sin esto un texto reubicado
     * podría desaparecer sin avisar.
     */
    private static void assertUntouched(List<Object[]> writes, byte[] fileData, byte[] originalData)
            throws IOException {
        for (Object[] write : writes) {
            int at = (Integer) write[1];
            int length = ((byte[]) write[2]).length;
            for (int i = at; i < at + length && i < originalData.length; i++) {
                if (fileData[i] != originalData[i]) {
                    throw new IOException(String.format(
                            "La intro quiere escribir %s en 0x%06X..0x%06X, pero 0x%06X ya lo había cambiado "
                                    + "un paso anterior. Quita esa zona de introSpace o libera espacio en otro sitio.",
                            write[0], at, at + length - 1, i));
                }
            }
        }
    }

    /** Los huecos de la configuración, menos el espacio libre del que tiran textos y bloques. */
    private static List<Region> pool() {
        List<Region> out = new ArrayList<>();
        for (Range range : App.config.introSpace()) {
            out.add(new Region(range.getFrom(), range.size()));
        }
        for (Range banned : App.config.spaceRanges()) {
            List<Region> next = new ArrayList<>();
            for (Region region : out) {
                int start = Math.max(region.start, banned.getFrom());
                int end = Math.min(region.end(), banned.getTo() + 1);
                if (start >= end) {
                    next.add(region);
                    continue;
                }
                if (region.start < start) {
                    next.add(new Region(region.start, start - region.start));
                }
                if (end < region.end()) {
                    next.add(new Region(end, region.end() - end));
                }
            }
            out = next;
        }
        out.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start)
                : Integer.compare(a.length, b.length));
        return out;
    }

    record Region(int start, int length) {
        int end() {
            return start + length;
        }
    }

    /** Reparte los huecos. Aquí la ROM no puede crecer, así que si algo no cabe se avisa. */
    static final class Placer {
        private final List<int[]> free = new ArrayList<>();
        private final int romSize;
        int end;
        int used;

        Placer(List<Region> holes, int romSize) {
            for (Region region : holes) {
                free.add(new int[]{region.start(), region.length()});
            }
            this.romSize = romSize;
            this.end = romSize;
        }

        int place(String name, int size, int alignment) throws IOException {
            for (int[] region : free) {
                int start = (region[0] + alignment - 1) / alignment * alignment;
                if (start + size <= region[0] + region[1]) {
                    int left = region[0] + region[1] - (start + size);
                    region[0] = start + size;
                    region[1] = left;
                    used += size;
                    return start;
                }
            }
            throw new IOException(String.format(
                    "No cabe %s (%d bytes) en el espacio de introSpace. La ROM ya ocupa %d KB y no puede "
                            + "crecer, así que hay que darle más huecos.", name, size, romSize / 1024));
        }
    }

    // ---- el arranque ----

    static final class Stub {
        byte[] near;
        byte[] far;
        Map<String, Integer> labels;
    }

    /**
     * Dos trozos:
     * <ul>
     *   <li>"cerca": entrada, lectura del mando y contador de fotogramas. Va en la tabla de vectores de la
     *       intro, porque su bucle final sólo llega ahí con un bra.w (±32 KB).</li>
     *   <li>"lejos": la salida al juego, con su tabla del YM y el epílogo. Ahí no aprieta el tamaño, así que
     *       va donde quepa.</li>
     * </ul>
     */
    static Stub buildStub(int atNear, int atFar, int atEntry, int atLoop, int gameSp, int gamePc,
                          int contRam, boolean remap, int[] frameTable) {
        final int VDP_CTRL = 0xC00004;
        final int VDP_DATA = 0xC00000;
        final int PSG = 0xC00011;
        final int Z80_BUS = 0xA11100;
        final int Z80_RST = 0xA11200;
        final int YM_A0 = 0xA04000;
        final int YM_D0 = 0xA04001;
        final int IO_CTRL1 = 0xA10009;
        final int IO_DATA1 = 0xA10003;
        final int VER_REG = 0xA10001;
        final int EPI_RAM = 0xFF0200;        // el epílogo se copia aquí y se ejecuta desde RAM

        // ---- el epílogo, que correrá desde RAM: sólo direccionamiento absoluto ----
        Asm epilogueAsm = new Asm(0);
        if (remap) {
            // Se deja la ROM a la vista, que es como está al encender y como la espera el juego: éste
            // enciende la SRAM sólo para leer o escribir la partida y la vuelve a apagar enseguida. Si se
            // le entrega encendida, todo lo que lea de la ROM por encima de donde empieza la SRAM le
            // devuelve la SRAM. Los emuladores tapan más o menos según lo fino que hilen, así que una ROM
            // así falla en unos y en otros no.
            epilogueAsm.moveBImmAbs(0x00, 0xA130F1);
        }
        epilogueAsm.clrLAbs(0xA10008);       // limpiar los registros del mando: el juego
        epilogueAsm.clrWAbs(0xA1000C);       // tiene que ver un arranque en frío
        epilogueAsm.moveWSr(0x2700);
        epilogueAsm.moveaLImmA7(gameSp);
        epilogueAsm.jmpAbs(gamePc);          // -> el juego original
        byte[] epilogue = epilogueAsm.link();

        // ---- la salida al juego (el trozo "lejos") ----
        Asm far = new Asm(atFar);
        far.label("salir");
        far.moveWSr(0x2700);
        far.moveWAbsD0(VDP_CTRL);            // leer estado: limpia el cerrojo de escritura del VDP
        far.moveWImmAbs(0x8004, VDP_CTRL);   // registro 0: sin interrupciones
        far.moveWImmAbs(0x8104, VDP_CTRL);   // registro 1: pantalla apagada
        for (int value : new int[]{0x9F, 0xBF, 0xDF, 0xFF}) {
            far.moveBImmAbs(value, PSG);     // callar el PSG
        }

        // El YM2612 se calla ANTES de tocar la línea de reset del Z80: esa línea también apaga el YM y deja
        // al 68000 sin su bus, y a partir de ahí no se le puede escribir ni leer su bit de ocupado (en una
        // consola de verdad se queda a 1 y la salida se colgaba ahí para siempre).
        far.moveWImmAbs(0x0100, Z80_BUS);    // pedir el bus del Z80
        far.label("esperar_bus");
        far.btstImmAbs(0, Z80_BUS);
        far.bne("esperar_bus");
        int[] ym = {0x2B, 0x00, 0x27, 0x00,
                    0x28, 0x00, 0x28, 0x01, 0x28, 0x02,
                    0x28, 0x04, 0x28, 0x05, 0x28, 0x06};
        far.leaPcA0("ym_tabla");
        far.moveq(ym.length / 2 - 1, 1);
        far.label("ym_bucle");
        far.moveBIncAbs(YM_A0);              // registro
        far.retardo(24);                     // espera fija, sin leer el YM
        far.moveBIncAbs(YM_D0);              // valor
        far.retardo(24);
        far.dbra(1, "ym_bucle");
        far.moveWImmAbs(0x0000, Z80_RST);    // Z80 (y con él el YM) a reset
        far.retardo(64);
        far.moveWImmAbs(0x0000, Z80_BUS);    // soltar el bus

        far.leaPcA0("epilogo");
        far.leaAbs(EPI_RAM, 1);
        far.moveq(epilogue.length / 2 - 1, 0);
        far.label("copiar");
        far.moveWIncInc();
        far.dbra(0, "copiar");
        far.jmpAbs(EPI_RAM);                 // -> epílogo en RAM -> el juego

        // Sustituye a la rutina que volcaba un fotograma en bruto a VRAM: coge del índice el fotograma que
        // toca, lo descomprime y lo mete directo en el puerto de datos del VDP, sin búfer.
        far.label("descomp");
        far.moveLImmAbs(0x40000000, VDP_CTRL);
        far.leaAbs(VDP_DATA, 1);
        far.moveWAbsDn(contRam + 4, 2);
        far.addqWAbs(1, contRam + 4);
        far.lslWImm(2, 2);
        far.leaPcA0("frames_tabla");
        far.moveaLIdxA0(2, 3);
        far.label("d_bucle");
        far.moveWIncDn(0);
        far.beq("d_fin");
        far.bmi("d_repe");
        far.subqWDn(1, 0);
        far.label("d_lit");
        far.moveWIncIndA1();
        far.dbra(0, "d_lit");
        far.bra("d_bucle");
        far.label("d_repe");
        far.negW(0);
        far.subqWDn(1, 0);
        far.moveWIncDn(1);
        far.label("d_rep");
        far.moveWDnIndA1(1);
        far.dbra(0, "d_rep");
        far.bra("d_bucle");
        far.label("d_fin");
        far.rts();

        far.label("ym_tabla");
        far.db(ym);
        far.label("epilogo");
        far.db(epilogue);
        far.label("frames_tabla");
        for (int frame : frameTable) {
            far.l(frame);
        }
        byte[] farData = far.link();

        // ---- el trozo "cerca", en la tabla de vectores ----
        Asm near = new Asm(atNear);

        near.label("entrada");               // el vector RESET
        near.moveWSr(0x2700);
        near.moveBAbsD0(VER_REG);            // ¿consola con TMSS?
        near.andiBD0(0x0F);
        near.beq("sin_tmss");
        near.moveLImmAbs(0x53454741, 0xA14000);   // 'SEGA'
        near.label("sin_tmss");
        near.moveBImmAbs(0x40, IO_CTRL1);    // mando 1: TH como salida
        near.moveBImmAbs(0x40, IO_DATA1);
        near.clrWAbs(contRam);               // contador de fotogramas a 0
        near.clrWAbs(contRam + 4);           // índice de fotograma a 0
        near.jmpAbs(atEntry);                // -> la intro original

        // Sustituye a la espera de vblank de la intro: enganchando aquí, el mando se lee en CADA fotograma
        // y no sólo en el bucle final, así que START salta la intro desde el principio.
        near.label("vsync");
        near.moveBImmAbs(0x00, IO_DATA1);    // TH=0 -> START y A
        near.nop();
        near.nop();
        near.nop();
        near.nop();
        near.moveBAbsD0(IO_DATA1);
        near.moveBImmAbs(0x40, IO_DATA1);
        near.btstImmD0(5);
        near.beq("trampolin");               // START (activo a cero)
        near.btstImmD0(4);
        near.beq("trampolin");               // A
        near.label("vs1");
        near.moveWAbsD0(VDP_CTRL);
        near.btstImmD0(3);
        near.bne("vs1");
        near.label("vs2");
        near.moveWAbsD0(VDP_CTRL);
        near.btstImmD0(3);
        near.beq("vs2");
        near.rts();

        near.label("comprobar");             // una vez por fotograma, en el bucle final
        near.addqWAbs(1, contRam);
        near.cmpiWAbs(ESPERA, contRam);
        near.bcc("trampolin");
        near.bra(atLoop);                    // seguir con la intro

        near.label("trampolin");             // un bra.w no llega al trozo lejano
        near.jmpAbs(far.labels.get("salir"));

        Stub stub = new Stub();
        stub.near = near.link();
        stub.far = farData;
        stub.labels = new HashMap<>(near.labels);
        stub.labels.putAll(far.labels);
        return stub;
    }

    // ---- un mini ensamblador de 68000: sólo lo que el arranque necesita ----

    static final class Asm {
        private final int base;
        private byte[] buf = new byte[256];
        private int len;
        private final List<Object[]> fix = new ArrayList<>();
        final Map<String, Integer> labels = new HashMap<>();

        Asm(int base) {
            this.base = base;
        }

        int pc() {
            return base + len;
        }

        void label(String name) {
            labels.put(name, pc());
        }

        void w(int value) {
            if (len + 2 > buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[len++] = (byte) (value >> 8);
            buf[len++] = (byte) value;
        }

        void l(int value) {
            w(value >>> 16);
            w(value);
        }

        void db(int[] data) {
            for (int value : data) {
                if (len + 1 > buf.length) {
                    buf = Arrays.copyOf(buf, buf.length * 2);
                }
                buf[len++] = (byte) value;
            }
        }

        void db(byte[] data) {
            if (len + data.length > buf.length) {
                buf = Arrays.copyOf(buf, len + data.length);
            }
            System.arraycopy(data, 0, buf, len, data.length);
            len += data.length;
        }

        void moveWSr(int v) { w(0x46FC); w(v); }
        void moveBImmAbs(int v, int a) { w(0x13FC); w(v & 0xFF); l(a); }
        void moveWImmAbs(int v, int a) { w(0x33FC); w(v); l(a); }
        void moveLImmAbs(int v, int a) { w(0x23FC); l(v); l(a); }
        void moveBAbsD0(int a) { w(0x1039); l(a); }
        void moveWAbsD0(int a) { w(0x3039); l(a); }
        void andiBD0(int v) { w(0x0200); w(v & 0xFF); }
        void clrLAbs(int a) { w(0x42B9); l(a); }
        void clrWAbs(int a) { w(0x4279); l(a); }
        void addqWAbs(int n, int a) { w(0x5079 | ((n & 7) << 9)); l(a); }
        void cmpiWAbs(int v, int a) { w(0x0C79); w(v); l(a); }
        void btstImmD0(int b) { w(0x0800); w(b); }
        void btstImmAbs(int b, int a) { w(0x0839); w(b); l(a); }
        void moveaLImmA7(int v) { w(0x2E7C); l(v); }
        void jmpAbs(int a) { w(0x4EF9); l(a); }
        void nop() { w(0x4E71); }
        void moveq(int v, int reg) { w(0x7000 | (reg << 9) | (v & 0xFF)); }
        void moveWImmDn(int v, int r) { w(0x303C | (r << 9)); w(v); }
        void leaAbs(int a, int reg) { w(0x41F9 | (reg << 9)); l(a); }
        void moveWIncInc() { w(0x32D8); }
        void moveBIncAbs(int a) { w(0x13D8); l(a); }
        void moveWAbsDn(int a, int r) { w(0x3039 | (r << 9)); l(a); }
        void moveWIncDn(int r) { w(0x301B | (r << 9)); }
        void moveWIncIndA1() { w(0x329B); }
        void moveWDnIndA1(int r) { w(0x3280 | r); }
        void lslWImm(int n, int r) { w(0xE148 | ((n & 7) << 9) | r); }
        void negW(int r) { w(0x4440 | r); }
        void subqWDn(int n, int r) { w(0x5140 | ((n & 7) << 9) | r); }
        void moveaLIdxA0(int r, int dst) { w(0x2070 | (dst << 9)); w(r << 12); }
        void rts() { w(0x4E75); }

        void leaPcA0(String target) { fix.add(new Object[]{len, target}); w(0x41FA); w(0); }
        void dbra(int reg, String target) { fix.add(new Object[]{len, target}); w(0x51C8 | reg); w(0); }

        private void br(int opcode, Object target) { fix.add(new Object[]{len, target}); w(opcode); w(0); }

        void bra(Object target) { br(0x6000, target); }
        void beq(Object target) { br(0x6700, target); }
        void bne(Object target) { br(0x6600, target); }
        void bcc(Object target) { br(0x6400, target); }
        void bmi(Object target) { br(0x6B00, target); }

        /**
         * Una espera fija. En una consola de verdad no se puede consultar el bit de ocupado del YM2612 (se
         * queda a 1 en cuanto el Z80 entra en reset), así que se espera a ciegas.
         */
        void retardo(int turns) {
            moveWImmDn(turns, 2);
            String name = "ret" + len;
            label(name);
            dbra(2, name);
        }

        byte[] link() {
            for (Object[] pending : fix) {
                int at = (Integer) pending[0];
                Object target = pending[1];
                Integer destination = target instanceof String ? labels.get(target) : (Integer) target;
                if (destination == null) {
                    throw new IllegalStateException("Etiqueta sin definir: " + target);
                }
                int displacement = destination - (base + at + 2);
                if (displacement < Short.MIN_VALUE || displacement > Short.MAX_VALUE) {
                    throw new IllegalStateException("Salto fuera de alcance: " + target + " (" + displacement + ")");
                }
                buf[at + 2] = (byte) (displacement >> 8);
                buf[at + 3] = (byte) displacement;
            }
            return Arrays.copyOf(buf, len);
        }
    }

    // ---- RLE por palabras ----

    /**
     * Todo en palabras big-endian:
     * <pre>
     *   n &gt; 0   n palabras literales a continuación
     *   n &lt; 0   la siguiente palabra se repite -n veces
     *   n == 0  fin
     * </pre>
     * Se descomprime directamente contra el puerto de datos del VDP, sin búfer.
     */
    static byte[] rleCompress(byte[] data) {
        int n = data.length / 2;
        int[] words = new int[n];
        for (int i = 0; i < n; i++) {
            words[i] = ((data[2 * i] & 0xFF) << 8) | (data[2 * i + 1] & 0xFF);
        }

        byte[] out = new byte[64];
        int len = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && words[j + 1] == words[i] && j - i < 0x7FFE) {
                j++;
            }
            int need = (j - i >= 2) ? 4 : 2 + 2 * (n - i);
            if (len + need > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, len + need));
            }
            if (j - i >= 2) {                       // una tirada de 3 o más
                int count = -(j - i + 1);
                out[len++] = (byte) (count >> 8);
                out[len++] = (byte) count;
                out[len++] = (byte) (words[i] >> 8);
                out[len++] = (byte) words[i];
                i = j + 1;
            } else {                                // literales hasta la siguiente tirada
                int k = i;
                while (k < n && k - i < 0x7FFE) {
                    if (k + 2 < n && words[k] == words[k + 1] && words[k + 1] == words[k + 2]) {
                        break;
                    }
                    k++;
                }
                out[len++] = (byte) ((k - i) >> 8);
                out[len++] = (byte) (k - i);
                for (int q = i; q < k; q++) {
                    out[len++] = (byte) (words[q] >> 8);
                    out[len++] = (byte) words[q];
                }
                i = k;
            }
        }
        return Arrays.copyOf(out, len + 2);         // el cero del final
    }

    /** Sólo se usa para comprobar que el compresor es reversible antes de fiarse de él. */
    static byte[] rleExpand(byte[] data) {
        byte[] out = new byte[data.length * 4];
        int len = 0;
        int i = 0;
        while (true) {
            short n = (short) (((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF));
            i += 2;
            if (n == 0) {
                return Arrays.copyOf(out, len);
            }
            int bytes = Math.abs((int) n) * 2;
            if (len + bytes > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, len + bytes));
            }
            if (n > 0) {
                System.arraycopy(data, i, out, len, n * 2);
                len += n * 2;
                i += n * 2;
            } else {
                for (int q = 0; q < -n; q++) {
                    out[len++] = data[i];
                    out[len++] = data[i + 1];
                }
                i += 2;
            }
        }
    }

    static int readU16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    static int readU32(byte[] data, int at) {
        return (readU16(data, at) << 16) | readU16(data, at + 2);
    }

    static void writeU16(byte[] data, int at, int value) {
        data[at] = (byte) (value >> 8);
        data[at + 1] = (byte) value;
    }

    static void writeU32(byte[] data, int at, int value) {
        writeU16(data, at, value >>> 16);
        writeU16(data, at + 2, value);
    }

}
