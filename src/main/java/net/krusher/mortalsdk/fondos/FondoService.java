package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.Checksum;
import net.krusher.mortalsdk.Log;
import net.krusher.mortalsdk.Range;
import net.krusher.mortalsdk.RncException;
import net.krusher.mortalsdk.RncService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Mete un {@link Fondo} en la ROM sin tocar los bloques ni las rutinas originales.
 * <p>
 * Por cada escenario escribe en espacio libre una rutina de 126 bytes que llama a la rutina original del
 * hack (para que haga lo suyo: sus tiles, sus mapas y sus efectos laterales) y encima carga nuestro bloque de
 * tiles ({@code jsr $B94}, a VRAM desde {@code $ACBE}) y nuestros dos mapas completos ({@code jsr $B88} a RAM y
 * {@code jsr $C7A} por bloques de 16 filas de 128 celdas). Como la copia suma {@code $ACBE} a cada palabra, los
 * mapas se guardan restándoselo. Las dos primeras filas del plano B son del marcador y no se copian. Al final
 * se cambia la entrada del escenario en la tabla de {@code 0x510}.
 * <p>
 * Las animaciones son tiles crudos: se reescriben en su sitio. Las paletas también.
 */
public final class FondoService {

    static final Set<Integer> RUTINAS_ORIGINALES = Set.of(0x60C, 0x3D14CE, 0x3D14DA, 0x9AA, 0x788, 0x568, 0xB2A);
    static final int CARGA_TILES = 0xB94, CARGA_RAM = 0xB88, COPIA = 0xC7A;
    public static final String ESPACIO_POR_DEFECTO = "0x3F6800-0x3FFF00";

    private FondoService() {}

    /** Huecos libres de la ROM donde colocar lo nuestro; comprueba que están a cero antes de escribir. */
    public static final class Espacio {
        private final List<int[]> rangos = new ArrayList<>();
        public final List<int[]> usado = new ArrayList<>();

        public Espacio(String texto) {
            for (String parte : texto.split(",")) {
                String[] ab = parte.trim().split("-");
                if (ab.length != 2) {
                    throw new IllegalArgumentException("Rango de espacio mal escrito: " + parte);
                }
                rangos.add(new int[]{Integer.decode(ab[0].trim()), Integer.decode(ab[1].trim())});
            }
        }

        /** Los huecos tal y como los da la configuración, en {@code fondosSpace}. */
        public Espacio(Collection<Range> huecos) {
            for (Range r : huecos) {
                rangos.add(new int[]{r.getFrom(), r.getTo()});
            }
        }

        int reservar(byte[] rom, int n) throws IOException {
            for (int[] r : rangos) {
                int a = (r[0] + 15) / 16 * 16;
                if (a + n <= r[1]) {
                    for (int i = a; i < a + n; i++) {
                        if (rom[i] != 0 && rom[i] != (byte) 0xFF) {
                            throw new IOException(String.format("El hueco 0x%06X-0x%06X no está vacío: revisa el espacio libre", a, a + n));
                        }
                    }
                    r[0] = a + n;
                    usado.add(new int[]{a, n});
                    return a;
                }
            }
            throw new IOException("No queda espacio libre para " + n + " bytes; amplía los huecos");
        }

        /** Cuánto queda en total. */
        public int libre() {
            int total = 0;
            for (int[] r : rangos) {
                total += Math.max(0, r[1] - (r[0] + 15) / 16 * 16);
            }
            return total;
        }
    }

    /** Lo que ha hecho con un escenario. */
    public record Resultado(int escenario, int rutina, int tiles, int mapaA, int mapaB, int bytesTiles, int bytesMapaA,
                            int bytesMapaB, int tilesAnimacionCambiados, boolean paletaCambiada) {}

    /** Los tres bloques ya comprimidos, para saber cuánto ocupan antes de escribir nada. */
    public record Bloques(byte[] tiles, byte[] mapaA, byte[] mapaB) {
        public int total() {
            return tiles.length + mapaA.length + mapaB.length + 126;
        }
    }

    public static Bloques comprimir(Fondo fondo) throws IOException {
        ByteArrayOutputStream tiles = new ByteArrayOutputStream();
        for (int[] t : fondo.tiles) {
            tiles.writeBytes(Fondo.empaquetar(t));
        }
        try {
            return new Bloques(RncService.pack(tiles.toByteArray(), RncService.METHOD_1),
                    RncService.pack(mapaRelativo(fondo.mapaA, fondo.escenario.baseTiles()), RncService.METHOD_1),
                    RncService.pack(mapaRelativo(fondo.mapaB, fondo.escenario.baseTiles()), RncService.METHOD_1));
        } catch (RncException e) {
            throw new IOException("No se pudo comprimir en RNC: " + e.getMessage(), e);
        }
    }

    static byte[] mapaRelativo(int[] mapa, int base) {
        byte[] out = new byte[mapa.length * 2];
        for (int i = 0; i < mapa.length; i++) {
            int w = (mapa[i] - base) & 0xFFFF;
            out[i * 2] = (byte) (w >> 8);
            out[i * 2 + 1] = (byte) w;
        }
        return out;
    }

    /** Inyecta un fondo en la ROM (que se modifica). No arregla el checksum: eso lo hace quien termina. */
    public static Resultado inyectar(byte[] rom, Fondo fondo, Espacio espacio) throws IOException {
        Escenario e = fondo.escenario;
        if (fondo.tiles.length > e.presupuestoTiles()) {
            throw new IOException(e.nombre() + ": " + fondo.tiles.length + " tiles, y sólo caben " + e.presupuestoTiles()
                    + " antes de los del marcador");
        }
        int tabla = Escenario.TABLA_RUTINAS + 4 * e.numero();
        int original = leerLong(rom, tabla);
        if (!RUTINAS_ORIGINALES.contains(original)) {
            throw new IOException(String.format("El escenario %d ya lleva una rutina parcheada (0x%06X). Parte de una ROM limpia.",
                    e.numero(), original));
        }
        boolean paletaCambiada = escribirPaleta(rom, fondo);
        Bloques b = comprimir(fondo);
        int aTiles = espacio.reservar(rom, b.tiles().length);
        System.arraycopy(b.tiles(), 0, rom, aTiles, b.tiles().length);
        int aMapaA = espacio.reservar(rom, b.mapaA().length);
        System.arraycopy(b.mapaA(), 0, rom, aMapaA, b.mapaA().length);
        int aMapaB = espacio.reservar(rom, b.mapaB().length);
        System.arraycopy(b.mapaB(), 0, rom, aMapaB, b.mapaB().length);
        byte[] codigo = rutina(original, aTiles, aMapaA, aMapaB);
        int aRutina = espacio.reservar(rom, codigo.length);
        System.arraycopy(codigo, 0, rom, aRutina, codigo.length);
        escribirLong(rom, tabla, aRutina);
        int anim = escribirAnimaciones(rom, fondo);
        return new Resultado(e.numero(), aRutina, aTiles, aMapaA, aMapaB, b.tiles().length, b.mapaA().length,
                b.mapaB().length, anim, paletaCambiada);
    }

    /** Sólo las animaciones y la paleta, para cuando el fondo no ha cambiado pero ellas sí. */
    public static int soloAnimaciones(byte[] rom, Fondo fondo) {
        return escribirAnimaciones(rom, fondo);
    }

    public static boolean escribirPaleta(byte[] rom, Fondo fondo) {
        boolean cambiada = false;
        for (int linea = 2; linea <= 3; linea++) {
            int direccion = linea == 2 ? fondo.escenario.paletaLinea2() : fondo.escenario.paletaLinea3();
            for (int i = 0; i < 16; i++) {
                int w = fondo.cram[linea * 16 + i] & 0x0EEE;
                if (Fondo.palabra(rom, direccion + i * 2) != w) {
                    rom[direccion + i * 2] = (byte) (w >> 8);
                    rom[direccion + i * 2 + 1] = (byte) w;
                    cambiada = true;
                }
            }
        }
        return cambiada;
    }

    static int escribirAnimaciones(byte[] rom, Fondo fondo) {
        int cambiados = 0;
        for (int k = 0; k < fondo.escenario.animaciones().size(); k++) {
            Escenario.Animacion a = fondo.escenario.animaciones().get(k);
            int[][][] frames = fondo.fotogramas.get(k);
            for (int f = 0; f < a.fotogramas(); f++) {
                for (int i = 0; i < a.tilesPorFotograma(); i++) {
                    int at = a.rom() + f * a.salto() + i * 32;
                    byte[] nuevo = Fondo.empaquetar(frames[f][i]);
                    boolean distinto = false;
                    for (int j = 0; j < 32; j++) {
                        if (rom[at + j] != nuevo[j]) {
                            distinto = true;
                            break;
                        }
                    }
                    if (distinto) {
                        System.arraycopy(nuevo, 0, rom, at, 32);
                        cambiados++;
                    }
                }
            }
        }
        return cambiados;
    }

    /** La rutina de carga nueva: 126 bytes de 68000, siempre iguales salvo las cuatro direcciones. */
    static byte[] rutina(int original, int tiles, int mapaA, int mapaB) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        jsr(b, original);                                   // la rutina original del hack
        leaA0(b, tiles); jsr(b, CARGA_TILES);               // nuestros tiles -> VRAM desde $ACBE
        leaA0(b, mapaA); jsr(b, CARGA_RAM);                 // mapa A -> RAM 0xFF0000
        b.writeBytes(new byte[]{0x34, 0x3C, 0x00, (byte) 0x80});   // move.w #$80,d2  (128 celdas por fila)
        b.writeBytes(new byte[]{0x7C, 0x00});                     // moveq #0,d6     (atributos ya van en el mapa)
        b.writeBytes(new byte[]{0x3E, 0x38, (byte) 0xAC, (byte) 0xBE}); // move.w $ACBE.w,d7 (se suma a cada palabra)
        copia(b, 0xE000, 0x0000, 16);
        copia(b, 0xF000, 0x1000, 16);
        leaA0(b, mapaB); jsr(b, CARGA_RAM);
        copia(b, 0xC200, 0x0200, 16);                       // filas 0-1 del plano B son del marcador
        copia(b, 0xD200, 0x1200, 14);
        b.writeBytes(new byte[]{0x4E, 0x75});               // rts
        return b.toByteArray();
    }

    private static void copia(ByteArrayOutputStream b, int vram, int ram, int filas) {
        b.writeBytes(new byte[]{0x32, 0x3C, (byte) (vram >> 8), (byte) vram});   // move.w #vram,d1
        leaA0(b, 0xFFFF0000 | ram);
        b.writeBytes(new byte[]{0x76, (byte) filas});                            // moveq #filas,d3
        jsr(b, COPIA);
    }

    private static void jsr(ByteArrayOutputStream b, int direccion) {
        b.writeBytes(new byte[]{0x4E, (byte) 0xB9});
        b.writeBytes(longBytes(direccion));
    }

    private static void leaA0(ByteArrayOutputStream b, int direccion) {
        b.writeBytes(new byte[]{0x41, (byte) 0xF9});
        b.writeBytes(longBytes(direccion));
    }

    private static byte[] longBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static int leerLong(byte[] d, int at) {
        return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
    }

    static void escribirLong(byte[] d, int at, int v) {
        System.arraycopy(longBytes(v), 0, d, at, 4);
    }

    /** Termina la ROM: checksum de la cabecera. */
    public static void terminar(byte[] rom) {
        Checksum.fixChecksum(rom);
    }

    // ------------------------------------------------------------------ los dos pasos del flujo normal

    /**
     * Un escenario entra en la ROM cuando el editor ha guardado lo suyo. Los demás se dejan en paz para no
     * mover bloques ni gastar hueco sin motivo.
     */
    public static boolean editado(File carpeta, Escenario e) {
        return new File(carpeta, e.carpeta() + "/fondo.properties").isFile();
    }

    /**
     * Rehace los ficheros con los que se trabaja (los planos, la hoja de tiles, los mapas, la paleta y las
     * animaciones) desde los volcados de la memoria de vídeo y la ROM. Lo que ya haya editado el editor no se
     * toca, que rehacerlo se llevaría por delante lo pintado.
     * <p>
     * Los volcados no salen de aquí: los saca el emulador, con el guion que hay en la carpeta de fondos.
     */
    public static void extraer(File carpeta, byte[] rom) throws IOException {
        if (!carpeta.isDirectory()) {
            Log.pnl("No hay carpeta de fondos en {0}, no se extrae ninguno.", carpeta.getPath());
            return;
        }
        for (Escenario e : Escenario.TODOS) {
            File suya = new File(carpeta, e.carpeta());
            if (!suya.isDirectory()) {
                Log.pnl("  {0}: no está su carpeta, se salta.", e.nombre());
                continue;
            }
            if (editado(carpeta, e)) {
                Log.pnl("  {0}: lo tiene guardado el editor, se deja como está.", e.nombre());
                continue;
            }
            Fondo fondo = Fondo.cargar(carpeta, e, rom, false);
            fondo.guardar(false);
            Log.pnl("  {0}: {1} tiles, los dos planos, la paleta y {2} animaciones.",
                    e.nombre(), fondo.tiles.length, e.animaciones().size());
        }
    }

    /** Mete en la ROM los fondos que se hayan editado. Devuelve cuántos han entrado. */
    public static int inyectar(File carpeta, byte[] rom, Collection<Range> huecos) throws IOException {
        if (!carpeta.isDirectory()) {
            Log.pnl("No hay carpeta de fondos en {0}, no se inyecta ninguno.", carpeta.getPath());
            return 0;
        }
        List<Escenario> editados = new ArrayList<>();
        for (Escenario e : Escenario.TODOS) {
            if (editado(carpeta, e)) {
                editados.add(e);
            }
        }
        if (editados.isEmpty()) {
            Log.pnl("Ningún fondo editado, la ROM se queda con los suyos.");
            return 0;
        }
        if (huecos.isEmpty()) {
            throw new IOException("Hay " + editados.size() + " fondo(s) editado(s) pero la configuración no dice"
                    + " dónde ponerlos: hace falta la propiedad fondosSpace.");
        }
        Espacio espacio = new Espacio(huecos);
        for (Escenario e : editados) {
            Fondo fondo = Fondo.cargar(carpeta, e, rom);
            Resultado r = inyectar(rom, fondo, espacio);
            Log.pf("  %s: %d tiles; rutina en 0x%06X, tiles en 0x%06X (%d B), mapas en 0x%06X (%d B) y 0x%06X (%d B)%n",
                    e.nombre(), fondo.tiles.length, r.rutina(), r.tiles(), r.bytesTiles(), r.mapaA(), r.bytesMapaA(),
                    r.mapaB(), r.bytesMapaB());
            if (r.tilesAnimacionCambiados() > 0) {
                Log.pnl("    tiles de animación reescritos: {0}", r.tilesAnimacionCambiados());
            }
            if (r.paletaCambiada()) {
                Log.pnl("    paleta del escenario reescrita.");
            }
        }
        return editados.size();
    }
}
