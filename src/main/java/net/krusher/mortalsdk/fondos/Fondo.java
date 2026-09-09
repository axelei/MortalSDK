package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.Bitmap;
import net.krusher.mortalsdk.Png;
import net.krusher.mortalsdk.RncException;
import net.krusher.mortalsdk.RncService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Un fondo de combate tal y como lo ve el VDP: un juego de tiles, dos mapas de 128x32 palabras (una por celda,
 * con índice de tile, volteos, prioridad y línea de paleta) y las dos líneas de paleta del fondo. Más las
 * animaciones, que son tiles aparte que el juego copia en marcha encima de unos slots fijos.
 * <p>
 * Se carga desde la carpeta {@code fondos/<n>_<nombre>/} de KombateSDK. La primera vez se monta desde los
 * volcados de VRAM ({@code raw/vram.bin}, {@code raw/cram.bin}) y el bloque de tiles de la ROM; en cuanto se
 * guarda, mandan los ficheros del editor: {@code tiles.png}, {@code mapa_A.txt}, {@code mapa_B.txt},
 * {@code paleta.txt} y {@code animaciones/*_tiles.png}. Los índices de tile de los mapas son absolutos (de
 * VRAM), como en el juego; los tiles del fondo van de {@link Escenario#baseTiles()} en adelante.
 */
public final class Fondo {

    public static final int TILE = 8;
    public static final int PIXELES_TILE = TILE * TILE;

    public final Escenario escenario;
    public final File carpeta;
    /** Pixeles (0-15) de cada tile del fondo, por slot relativo a la base. */
    public int[][] tiles;
    /** Slots que el juego reescribe después de cargar el fondo (animaciones y alguno más): no se tocan. */
    public boolean[] tocado;
    public final int[] mapaA = new int[Escenario.CELDAS];
    public final int[] mapaB = new int[Escenario.CELDAS];
    /** Las 64 entradas de la CRAM en el combate; el fondo usa las líneas 2 y 3 (32-63). */
    public final int[] cram = new int[64];
    /** Celdas de cada plano que se ven en pantalla al empezar el combate (con el scroll de la captura). */
    public final boolean[] visibleA = new boolean[Escenario.CELDAS];
    public final boolean[] visibleB = new boolean[Escenario.CELDAS];
    /** Fotogramas de cada animación: [animación][fotograma][tile][64 píxeles]. */
    public final List<int[][][]> fotogramas = new ArrayList<>();
    private byte[] vram;

    private Fondo(Escenario escenario, File carpeta) {
        this.escenario = escenario;
        this.carpeta = carpeta;
    }

    // ------------------------------------------------------------------ carga

    public static Fondo cargar(File carpetaFondos, Escenario escenario, byte[] rom) throws IOException {
        return cargar(carpetaFondos, escenario, rom, true);
    }

    /**
     * @param usarGuardado si es {@code false} se hace oídos sordos a lo que haya guardado el editor y se monta
     *                     todo otra vez desde los volcados y la ROM, que es lo que hace la extracción
     */
    public static Fondo cargar(File carpetaFondos, Escenario escenario, byte[] rom, boolean usarGuardado)
            throws IOException {
        File carpeta = new File(carpetaFondos, escenario.carpeta());
        if (!carpeta.isDirectory()) {
            throw new IOException("No existe la carpeta del escenario: " + carpeta);
        }
        File volcado = new File(carpeta, "raw/vram.bin");
        if (!volcado.isFile()) {
            throw new IOException("Falta " + volcado + ". Los volcados de la memoria de vídeo salen del emulador:"
                    + " mira extraer/LEEME.md en la carpeta de fondos.");
        }
        Fondo f = new Fondo(escenario, carpeta);
        f.vram = Files.readAllBytes(volcado.toPath());
        byte[] cramBytes = Files.readAllBytes(new File(carpeta, "raw/cram.bin").toPath());
        for (int i = 0; i < 64; i++) {
            f.cram[i] = palabra(cramBytes, i * 2);
        }
        // Las dos líneas del fondo se toman de la ROM, no del volcado: es lo que carga el juego al empezar el
        // combate. En la Guarida de Goro, además, el juego cicla un color en marcha, así que el volcado trae un
        // valor que no es el de partida y reinyectarlo pisaría el de la ROM.
        for (int i = 0; i < 16; i++) {
            f.cram[2 * 16 + i] = palabra(rom, escenario.paletaLinea2() + i * 2);
            f.cram[3 * 16 + i] = palabra(rom, escenario.paletaLinea3() + i * 2);
        }
        int[][] bloque = tilesDe(desempaquetar(rom, escenario.bloqueTiles()));
        int n = Math.min(bloque.length, escenario.presupuestoTiles());
        // qué slots toca el juego: los que en el volcado no coinciden con la ROM (animaciones, tile 0...)
        f.tocado = new boolean[escenario.presupuestoTiles()];
        for (int i = 0; i < n; i++) {
            f.tocado[i] = !java.util.Arrays.equals(bloque[i], tileDeVram(f.vram, escenario.baseTiles() + i));
        }
        for (Escenario.Animacion a : escenario.animaciones()) {
            for (int rel : a.slotsRelativos()) {
                f.tocado[rel] = true;
            }
        }

        File propiedades = new File(carpeta, "fondo.properties");
        if (usarGuardado && propiedades.isFile()) {
            f.cargarGuardado(propiedades);
        } else {
            f.tiles = new int[n][];
            for (int i = 0; i < n; i++) {
                f.tiles[i] = bloque[i].clone();
            }
            for (int c = 0; c < Escenario.CELDAS; c++) {
                f.mapaA[c] = palabra(f.vram, Escenario.PLANO_A + c * 2);
                f.mapaB[c] = palabra(f.vram, Escenario.PLANO_B + c * 2);
            }
            File paletaPng = new File(carpeta, "paleta_fondo.png");
            if (paletaPng.isFile()) {
                f.cargarPaletaPng(paletaPng);
            }
        }
        f.cargarAnimaciones(rom);
        f.calcularVisibilidad();
        return f;
    }

    private void cargarGuardado(File propiedades) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(propiedades.toPath())) {
            p.load(in);
        }
        int n = Integer.parseInt(p.getProperty("tiles"));
        Bitmap hoja = Png.read(new File(carpeta, "tiles.png"));
        if (!hoja.isIndexed()) {
            throw new IOException("tiles.png tiene que ser un PNG indexado");
        }
        tiles = new int[n][];
        for (int i = 0; i < n; i++) {
            tiles[i] = new int[PIXELES_TILE];
            int x0 = (i % 16) * TILE, y0 = (i / 16) * TILE;
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    tiles[i][y * TILE + x] = hoja.indexAt(x0 + x, y0 + y) & 0xF;
                }
            }
        }
        leerMapa(new File(carpeta, "mapa_A.txt"), mapaA);
        leerMapa(new File(carpeta, "mapa_B.txt"), mapaB);
        List<String> lineas = Files.readAllLines(new File(carpeta, "paleta.txt").toPath(), StandardCharsets.US_ASCII);
        int linea = 2;
        for (String l : lineas) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] partes = l.split("\\s+");
            for (int i = 0; i < 16 && i < partes.length; i++) {
                cram[linea * 16 + i] = Integer.parseInt(partes[i], 16);
            }
            linea++;
        }
    }

    private static void leerMapa(File fichero, int[] mapa) throws IOException {
        int c = 0;
        for (String l : Files.readAllLines(fichero.toPath(), StandardCharsets.US_ASCII)) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            for (String palabra : l.split("\\s+")) {
                if (c < mapa.length) {
                    mapa[c++] = Integer.parseInt(palabra, 16);
                }
            }
        }
        if (c != mapa.length) {
            throw new IOException(fichero.getName() + " tiene " + c + " palabras y hacen falta " + mapa.length);
        }
    }

    private void cargarPaletaPng(File fichero) throws IOException {
        Bitmap im = Png.read(fichero);
        int sw = im.getWidth() / 16, sh = im.getHeight() / 2;
        if (sw < 1 || sh < 1) {
            return;
        }
        for (int fila = 0; fila < 2; fila++) {
            for (int i = 0; i < 16; i++) {
                cram[(2 + fila) * 16 + i] = rgbACram(im.getRgb(i * sw + sw / 2, fila * sh + sh / 2));
            }
        }
    }

    private void cargarAnimaciones(byte[] rom) throws IOException {
        fotogramas.clear();
        for (Escenario.Animacion a : escenario.animaciones()) {
            int nt = a.tilesPorFotograma();
            int[][][] frames = new int[a.fotogramas()][nt][];
            File hoja = new File(carpeta, "animaciones/" + a.nombre() + "_tiles.png");
            Bitmap im = hoja.isFile() ? Png.read(hoja) : null;
            if (im != null && (!im.isIndexed() || im.getWidth() != nt * TILE || im.getHeight() != a.fotogramas() * TILE)) {
                im = null;
            }
            for (int f = 0; f < a.fotogramas(); f++) {
                for (int i = 0; i < nt; i++) {
                    int[] px = new int[PIXELES_TILE];
                    if (im != null) {
                        for (int y = 0; y < TILE; y++) {
                            for (int x = 0; x < TILE; x++) {
                                px[y * TILE + x] = im.indexAt(i * TILE + x, f * TILE + y) & 0xF;
                            }
                        }
                    } else {
                        px = tileDe(rom, a.rom() + f * a.salto() + i * 32);
                    }
                    frames[f][i] = px;
                }
            }
            fotogramas.add(frames);
        }
    }

    /** Qué celdas se ven en pantalla al empezar: mismo cálculo que hace el VDP con el scroll de la captura. */
    private void calcularVisibilidad() throws IOException {
        File vs = new File(carpeta, "raw/vsram.bin");
        if (!vs.isFile()) {
            java.util.Arrays.fill(visibleA, true);
            java.util.Arrays.fill(visibleB, true);
            return;
        }
        byte[] vsram = Files.readAllBytes(vs.toPath());
        int vsA = palabra(vsram, 0) & 0x3FF, vsB = palabra(vsram, 2) & 0x3FF;
        int pw = Escenario.ANCHO * TILE, ph = Escenario.ALTO * TILE;
        for (int y = 0; y < 224; y++) {
            int hsA = con10Bits(palabra(vram, 0xB800 + y * 4)), hsB = con10Bits(palabra(vram, 0xB800 + y * 4 + 2));
            int yA = (y + vsA) % ph, yB = (y + vsB) % ph;
            for (int x = 0; x < 320; x++) {
                int xA = Math.floorMod(x - hsA, pw), xB = Math.floorMod(x - hsB, pw);
                int cA = (yA / TILE) * Escenario.ANCHO + xA / TILE, cB = (yB / TILE) * Escenario.ANCHO + xB / TILE;
                int pa = pixel(mapaA, cA, xA % TILE, yA % TILE), pb = pixel(mapaB, cB, xB % TILE, yB % TILE);
                boolean prA = (mapaA[cA] & 0x8000) != 0, prB = (mapaB[cB] & 0x8000) != 0;
                // orden del VDP: B baja, A baja, B alta, A alta
                char gana = 0;
                if (pb != 0 && !prB) gana = 'B';
                if (pa != 0 && !prA) gana = 'A';
                if (pb != 0 && prB) gana = 'B';
                if (pa != 0 && prA) gana = 'A';
                if (gana == 'A') visibleA[cA] = true;
                if (gana == 'B') visibleB[cB] = true;
            }
        }
    }

    // ------------------------------------------------------------------ guardado

    public void guardar() throws IOException {
        guardar(true);
    }

    /**
     * @param marcarEditado escribe {@code fondo.properties}, que es lo que dice que este escenario lo ha tocado
     *                      el editor: a partir de ahí mandan estos ficheros y no los volcados. La extracción no
     *                      lo escribe, porque rehacer los ficheros no es haberlos editado.
     */
    public void guardar(boolean marcarEditado) throws IOException {
        if (marcarEditado) {
            Properties p = new Properties();
            p.setProperty("escenario", Integer.toString(escenario.numero()));
            p.setProperty("tiles", Integer.toString(tiles.length));
            p.setProperty("base", "0x" + Integer.toHexString(escenario.baseTiles()));
            try (var out = Files.newOutputStream(new File(carpeta, "fondo.properties").toPath())) {
                p.store(out, "Fondo de combate editado con MortalSDK. Mandan tiles.png, mapa_A.txt, mapa_B.txt y paleta.txt.");
            }
        }
        // hoja de tiles: 16 por fila, 4 bits, con la paleta de la línea 3 para verla
        int filas = (tiles.length + 15) / 16;
        Bitmap hoja = Bitmap.indexed(16 * TILE, Math.max(1, filas) * TILE, paletaLinea(3));
        for (int i = 0; i < tiles.length; i++) {
            int x0 = (i % 16) * TILE, y0 = (i / 16) * TILE;
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    hoja.setIndex(x0 + x, y0 + y, tiles[i][y * TILE + x]);
                }
            }
        }
        Png.write(hoja, new File(carpeta, "tiles.png"));
        escribirMapa(new File(carpeta, "mapa_A.txt"), mapaA, "plano A (VRAM 0xE000)");
        escribirMapa(new File(carpeta, "mapa_B.txt"), mapaB, "plano B (VRAM 0xC000)");
        StringBuilder pal = new StringBuilder("# lineas 2 y 3 de la CRAM, 16 palabras 0000BBB0GGG0RRR0 por linea\n");
        for (int linea = 2; linea <= 3; linea++) {
            for (int i = 0; i < 16; i++) {
                pal.append(String.format("%04X%s", cram[linea * 16 + i], i == 15 ? "\n" : " "));
            }
        }
        Files.writeString(new File(carpeta, "paleta.txt").toPath(), pal.toString(), StandardCharsets.US_ASCII);
        // los planos montados y la paleta, en el formato que entiende también reinyectar.py
        Png.write(renderIndexado('A'), new File(carpeta, "plano_A.png"));
        Png.write(renderIndexado('B'), new File(carpeta, "plano_B.png"));
        Bitmap muestras = Bitmap.indexed(256, 32, paletaCompleta());
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 256; x++) {
                muestras.setIndex(x, y, (2 + y / 16) * 16 + x / 16);
            }
        }
        Png.write(muestras, new File(carpeta, "paleta_fondo.png"));
        File dirAnim = new File(carpeta, "animaciones");
        for (int k = 0; k < escenario.animaciones().size(); k++) {
            Escenario.Animacion a = escenario.animaciones().get(k);
            Files.createDirectories(dirAnim.toPath());
            int nt = a.tilesPorFotograma();
            Bitmap im = Bitmap.indexed(nt * TILE, a.fotogramas() * TILE, paletaCompleta());
            for (int f = 0; f < a.fotogramas(); f++) {
                for (int i = 0; i < nt; i++) {
                    for (int y = 0; y < TILE; y++) {
                        for (int x = 0; x < TILE; x++) {
                            im.setIndex(i * TILE + x, f * TILE + y, a.linea() * 16 + fotogramas.get(k)[f][i][y * TILE + x]);
                        }
                    }
                }
            }
            Png.write(im, new File(dirAnim, a.nombre() + "_tiles.png"));
        }
    }

    private static void escribirMapa(File fichero, int[] mapa, String titulo) throws IOException {
        StringBuilder sb = new StringBuilder("# " + titulo + ": 32 filas de 128 palabras P CC V H NNNNNNNNNNN, indices absolutos de VRAM\n");
        for (int fila = 0; fila < Escenario.ALTO; fila++) {
            for (int col = 0; col < Escenario.ANCHO; col++) {
                sb.append(String.format("%04X%s", mapa[fila * Escenario.ANCHO + col], col == Escenario.ANCHO - 1 ? "\n" : " "));
            }
        }
        Files.writeString(fichero.toPath(), sb.toString(), StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ consulta y edición

    public int[] mapa(char plano) {
        return plano == 'A' ? mapaA : mapaB;
    }

    public boolean[] visible(char plano) {
        return plano == 'A' ? visibleA : visibleB;
    }

    public static int indice(int palabra) { return palabra & 0x7FF; }
    public static boolean volteoH(int palabra) { return (palabra & 0x0800) != 0; }
    public static boolean volteoV(int palabra) { return (palabra & 0x1000) != 0; }
    public static int linea(int palabra) { return (palabra >> 13) & 3; }
    public static boolean prioridad(int palabra) { return (palabra & 0x8000) != 0; }

    public static int palabraDe(int indice, boolean h, boolean v, int linea, boolean prioridad) {
        return (prioridad ? 0x8000 : 0) | ((linea & 3) << 13) | (v ? 0x1000 : 0) | (h ? 0x0800 : 0) | (indice & 0x7FF);
    }

    /** Slot relativo del tile de una celda, o -1 si la celda apunta fuera del bloque del fondo. */
    public int slotDe(int palabra) {
        int rel = indice(palabra) - escenario.baseTiles();
        return rel >= 0 && rel < tiles.length ? rel : -1;
    }

    /**
     * Píxel (0-15) que se ve en la posición (x,y) de una celda, con sus volteos.
     * <p>
     * Los slots que el juego reescribe en marcha (los de las animaciones) y los tiles de fuera del bloque se
     * enseñan tal y como estaban en la VRAM al capturar el escenario, que es lo que se ve de verdad en
     * pantalla; en {@link #tiles} se guarda lo que trae el bloque de la ROM, que es lo que se reinyecta.
     */
    public int pixel(int[] mapa, int celda, int x, int y) {
        int w = mapa[celda];
        int sx = volteoH(w) ? TILE - 1 - x : x, sy = volteoV(w) ? TILE - 1 - y : y;
        int slot = slotDe(w);
        if (slot >= 0 && !tocado[slot]) {
            return tiles[slot][sy * TILE + sx];
        }
        return tileDeVram(vram, indice(w))[sy * TILE + sx];
    }

    /** Cuántas celdas (de los dos planos) usan cada slot. */
    public int[] usoTiles() {
        int[] uso = new int[tiles.length];
        for (int[] mapa : new int[][]{mapaA, mapaB}) {
            for (int w : mapa) {
                int slot = slotDe(w);
                if (slot >= 0) {
                    uso[slot]++;
                }
            }
        }
        return uso;
    }

    /** Slots sin usar y que el juego no toca: donde se pueden meter tiles nuevos. */
    public List<Integer> slotsLibres() {
        int[] uso = usoTiles();
        List<Integer> libres = new ArrayList<>();
        for (int i = 0; i < tiles.length; i++) {
            if (uso[i] == 0 && !tocado[i]) {
                libres.add(i);
            }
        }
        return libres;
    }

    /** Añade un tile al final si aún cabe (antes del marcador). Devuelve su slot o -1. */
    public int anadirTile(int[] pixeles) {
        if (tiles.length >= escenario.presupuestoTiles()) {
            return -1;
        }
        int[][] nuevos = java.util.Arrays.copyOf(tiles, tiles.length + 1);
        nuevos[tiles.length] = pixeles.clone();
        boolean[] t = java.util.Arrays.copyOf(tocado, Math.max(tocado.length, nuevos.length));
        tiles = nuevos;
        tocado = t;
        return tiles.length - 1;
    }

    /**
     * Le da a la celda un tile propio (copia del que usaba) para poder pintarla sin tocar a las demás.
     * Devuelve el slot nuevo o -1 si no hay sitio.
     */
    public int separarTile(char plano, int celda) {
        int[] mapa = mapa(plano);
        int slot = slotDe(mapa[celda]);
        if (slot < 0) {
            return -1;
        }
        List<Integer> libres = slotsLibres();
        int destino = libres.isEmpty() ? anadirTile(tiles[slot]) : libres.get(0);
        if (destino < 0) {
            return -1;
        }
        tiles[destino] = tiles[slot].clone();
        mapa[celda] = (mapa[celda] & 0xF800) | (escenario.baseTiles() + destino);
        return destino;
    }

    /**
     * Junta los tiles repetidos (mirando los cuatro volteos) apuntando las celdas al de menor índice.
     * No renumera ni borra nada: los slots que se quedan sin usar pasan a estar libres. Devuelve cuántos
     * slots ha liberado.
     */
    public int compactar() {
        Map<String, Integer> primeros = new HashMap<>();
        int[] antes = usoTiles();
        for (int i = 0; i < tiles.length; i++) {
            if (!tocado[i] && antes[i] > 0) {
                primeros.putIfAbsent(clave(tiles[i]), i);
            }
        }
        for (int[] mapa : new int[][]{mapaA, mapaB}) {
            for (int c = 0; c < mapa.length; c++) {
                int slot = slotDe(mapa[c]);
                if (slot < 0 || tocado[slot]) {
                    continue;
                }
                int[] visto = orientar(tiles[slot], volteoH(mapa[c]), volteoV(mapa[c]));
                for (int flip = 0; flip < 4; flip++) {
                    boolean h = (flip & 1) != 0, v = (flip & 2) != 0;
                    Integer otro = primeros.get(clave(orientar(visto, h, v)));
                    if (otro != null && otro < slot) {
                        mapa[c] = (mapa[c] & 0xE000) | (v ? 0x1000 : 0) | (h ? 0x0800 : 0) | (escenario.baseTiles() + otro);
                        break;
                    }
                }
            }
        }
        int[] despues = usoTiles();
        int liberados = 0;
        for (int i = 0; i < tiles.length; i++) {
            if (antes[i] > 0 && despues[i] == 0) {
                liberados++;
            }
        }
        return liberados;
    }

    // ------------------------------------------------------------------ render

    public int[] paletaCompleta() {
        int[] p = new int[64];
        for (int i = 0; i < 64; i++) {
            p[i] = cramARgb(cram[i]);
        }
        return p;
    }

    public int[] paletaLinea(int linea) {
        int[] p = new int[16];
        for (int i = 0; i < 16; i++) {
            p[i] = cramARgb(cram[linea * 16 + i]);
        }
        return p;
    }

    /** El plano entero como PNG indexado de 64 colores: índice = línea*16 + color (lo que lee reinyectar.py). */
    public Bitmap renderIndexado(char plano) {
        int[] mapa = mapa(plano);
        Bitmap im = Bitmap.indexed(Escenario.ANCHO * TILE, Escenario.ALTO * TILE, paletaCompleta());
        for (int c = 0; c < Escenario.CELDAS; c++) {
            int x0 = (c % Escenario.ANCHO) * TILE, y0 = (c / Escenario.ANCHO) * TILE;
            int linea = linea(mapa[c]);
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    im.setIndex(x0 + x, y0 + y, linea * 16 + pixel(mapa, c, x, y));
                }
            }
        }
        return im;
    }

    /** ARGB de un plano; el color 0 de cada celda sale transparente (alfa 0) para poder componer. */
    public int[] renderArgb(char plano) {
        int[] mapa = mapa(plano);
        int[] pal = paletaCompleta();
        int ancho = Escenario.ANCHO * TILE;
        int[] out = new int[ancho * Escenario.ALTO * TILE];
        for (int c = 0; c < Escenario.CELDAS; c++) {
            int x0 = (c % Escenario.ANCHO) * TILE, y0 = (c / Escenario.ANCHO) * TILE;
            int linea = linea(mapa[c]);
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    int v = pixel(mapa, c, x, y);
                    out[(y0 + y) * ancho + x0 + x] = v == 0 ? 0 : pal[linea * 16 + v];
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ utilidades

    public static int cramARgb(int w) {
        int r = (w >> 1) & 7, g = (w >> 5) & 7, b = (w >> 9) & 7;
        return 0xFF000000 | (r * 255 / 7) << 16 | (g * 255 / 7) << 8 | (b * 255 / 7);
    }

    public static int rgbACram(int argb) {
        int r = nivel((argb >> 16) & 0xFF), g = nivel((argb >> 8) & 0xFF), b = nivel(argb & 0xFF);
        return (b << 9) | (g << 5) | (r << 1);
    }

    private static int nivel(int v) {
        int mejor = 0;
        for (int n = 1; n < 8; n++) {
            if (Math.abs(n * 255 / 7 - v) < Math.abs(mejor * 255 / 7 - v)) {
                mejor = n;
            }
        }
        return mejor;
    }

    public static int[] orientar(int[] px, boolean h, boolean v) {
        if (!h && !v) {
            return px.clone();
        }
        int[] out = new int[PIXELES_TILE];
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                out[y * TILE + x] = px[(v ? TILE - 1 - y : y) * TILE + (h ? TILE - 1 - x : x)];
            }
        }
        return out;
    }

    public static String clave(int[] px) {
        StringBuilder sb = new StringBuilder(PIXELES_TILE);
        for (int p : px) {
            sb.append(Character.forDigit(p & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] empaquetar(int[] px) {
        byte[] out = new byte[32];
        for (int i = 0; i < PIXELES_TILE; i += 2) {
            out[i / 2] = (byte) ((px[i] << 4) | (px[i + 1] & 0xF));
        }
        return out;
    }

    public static int[] tileDe(byte[] datos, int at) {
        int[] px = new int[PIXELES_TILE];
        for (int i = 0; i < 32; i++) {
            int b = at + i < datos.length && at + i >= 0 ? datos[at + i] & 0xFF : 0;
            px[i * 2] = b >> 4;
            px[i * 2 + 1] = b & 0xF;
        }
        return px;
    }

    static int[] tileDeVram(byte[] vram, int indice) {
        return tileDe(vram, indice * 32);
    }

    private static int[][] tilesDe(byte[] bloque) {
        int[][] t = new int[bloque.length / 32][];
        for (int i = 0; i < t.length; i++) {
            t[i] = tileDe(bloque, i * 32);
        }
        return t;
    }

    static byte[] desempaquetar(byte[] rom, int direccion) throws IOException {
        try {
            return RncService.unpack(rom, direccion);
        } catch (RncException e) {
            throw new IOException("No se pudo descomprimir el bloque RNC de 0x" + Integer.toHexString(direccion) + ": " + e.getMessage(), e);
        }
    }

    static int palabra(byte[] d, int at) {
        return ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF);
    }

    private static int con10Bits(int v) {
        v &= 0x3FF;
        return (v & 0x200) != 0 ? v - 0x400 : v;
    }
}
