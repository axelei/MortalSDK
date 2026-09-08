package net.krusher.mortalsdk.fondos;

import java.util.List;

/**
 * Los siete escenarios de combate de Mortal Kombat Arcade Edition v2-7, tal y como los carga el hack.
 * <p>
 * El hack sustituye el cargador de fondos del juego: en {@code 0x504} salta según la variable de escenario
 * ({@code $FFAABC}, 0-6) a una rutina por escenario, que descomprime los tiles RNC a VRAM desde el índice
 * {@code $ACBE} ({@link #baseTiles}), descomprime mapas RNC a RAM y los copia por trozos a los planos A
 * ({@code 0xE000}) y B ({@code 0xC000}), los dos de 128x32 celdas. Las paletas del fondo son las líneas 2 y 3
 * de la CRAM y están en la tabla de {@code 0xBF4}. Los tiles a partir de {@code 0x4D1} son del marcador
 * (sprites), así que el fondo solo puede usar de {@code baseTiles} a {@code 0x4D1}.
 * <p>
 * Estos datos salen del desensamblado y de los volcados de VRAM de cada escenario; están documentados en
 * {@code fondos/LEEME.md} de KombateSDK.
 *
 * @param numero         índice del escenario (valor de {@code $AABC})
 * @param nombre         nombre de la carpeta en {@code fondos/}
 * @param baseTiles      índice de VRAM donde empiezan los tiles del fondo ({@code $ACBE})
 * @param bloqueTiles    dirección del bloque RNC de tiles en la ROM
 * @param rutinaOriginal rutina de carga del hack (entrada de la tabla {@code 0x510})
 * @param paletaLinea3   dirección de la paleta que va a la línea 3 de la CRAM
 * @param paletaLinea2   dirección de la paleta que va a la línea 2 de la CRAM
 * @param animaciones    tiles del fondo que el juego cambia en marcha, con sus fotogramas en la ROM
 */
public record Escenario(int numero, String nombre, int baseTiles, int bloqueTiles, int rutinaOriginal,
                        int paletaLinea3, int paletaLinea2, List<Animacion> animaciones) {

    /** Primer tile del marcador: el fondo no puede pasar de aquí. */
    public static final int LIMITE_TILES = 0x4D1;
    public static final int PLANO_A = 0xE000;
    public static final int PLANO_B = 0xC000;
    public static final int ANCHO = 128;
    public static final int ALTO = 32;
    public static final int CELDAS = ANCHO * ALTO;
    public static final int TABLA_RUTINAS = 0x510;

    /**
     * Una animación de fondo: tiles crudos en la ROM, un fotograma detrás de otro, que el juego copia
     * periódicamente encima de unos slots fijos de VRAM.
     *
     * @param nombre            nombre del fichero en {@code animaciones/}
     * @param rom               dirección del primer fotograma
     * @param salto             bytes entre un fotograma y el siguiente
     * @param fotogramas        cuántos fotogramas hay
     * @param slotsRelativos    índices (relativos a {@code baseTiles}) que ocupan los tiles de cada fotograma, en orden
     * @param linea             línea de paleta con la que se ven (la mayoritaria entre sus celdas)
     */
    public record Animacion(String nombre, int rom, int salto, int fotogramas, int[] slotsRelativos, int linea) {
        public int tilesPorFotograma() {
            return slotsRelativos.length;
        }
    }

    private static int[] rango(int desde, int hasta) {
        int[] out = new int[hasta - desde + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = desde + i;
        }
        return out;
    }

    public static final List<Escenario> TODOS = List.of(
            new Escenario(0, "patio", 0x1E1, 0x226DC0, 0x60C, 0x19F6D8, 0x19F6F8,
                    List.of(new Animacion("monjes", 0x19D166, 0x180, 4, rango(1, 12), 3))),
            new Escenario(1, "santuario_del_guerrero", 0x19B, 0x34D810, 0x3D14CE, 0x19D126, 0x19D146, List.of()),
            new Escenario(2, "puertas_del_palacio", 0x19B, 0x367CC0, 0x3D14DA, 0x19F9E2, 0x19FA02, List.of()),
            new Escenario(3, "el_pozo", 0x19B, 0x355000, 0x9AA, 0x19FAB2, 0x2028A0, List.of()),
            new Escenario(4, "sala_del_trono", 0x1E2, 0x36B760, 0x788, 0x199400, 0x199420,
                    List.of(new Animacion("trono_2x2", 0x34C882, 0x80, 3, rango(630, 633), 2))),
            new Escenario(5, "guarida_de_goro", 0x1E1, 0x2D2180, 0x568, 0x19597A, 0x19599A, List.of()),
            new Escenario(6, "fondo_del_pozo", 0x1A9, 0x355000, 0xB2A, 0x2028C0, 0x2028A0, List.of()));

    public static Escenario porNumero(int numero) {
        return TODOS.stream().filter(e -> e.numero == numero).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No hay escenario " + numero));
    }

    /** Cuántos tiles caben entre la base y el marcador. */
    public int presupuestoTiles() {
        return LIMITE_TILES - baseTiles;
    }

    public String carpeta() {
        return numero + "_" + nombre;
    }
}
