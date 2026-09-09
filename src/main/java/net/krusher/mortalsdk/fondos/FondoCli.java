package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.IpsService;
import net.krusher.mortalsdk.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los dos modos de los fondos de combate: abrir el editor, o meterlos en la ROM sin abrir ventana.
 * <p>
 * El segundo es el que sirve para encadenarlo detrás de la inyección normal, que es cuando ya está hecha la
 * traducción: primero {@code i}, y sobre la ROM que sale de ahí, los fondos.
 */
public final class FondoCli {

    private FondoCli() {}

    /** {@code fondos "carpeta" "rom.bin" [captura.png]} */
    public static void editor(String[] args) {
        File carpeta = new File(args.length > 1 ? args[1] : "fondos");
        File rom = new File(args.length > 2 ? args[2] : "Mortal Kombat Arcade Edition v2-7.bin");
        if (!carpeta.isDirectory() || !rom.isFile()) {
            ayuda();
            System.exit(1);
        }
        FondoEditor.abrir(carpeta, rom, args.length > 3 ? args[3] : null);
    }

    /** {@code fondos-rom "carpeta" "rom.bin" "salida.bin" [clave=valor ...]} */
    public static void generar(String[] args) throws IOException {
        if (args.length < 4) {
            ayuda();
            System.exit(1);
        }
        File carpeta = new File(args[1]);
        File entrada = new File(args[2]);
        File salida = new File(args[3]);
        if (!carpeta.isDirectory() || !entrada.isFile()) {
            ayuda();
            System.exit(1);
        }
        Map<String, String> opciones = opciones(args, 4);

        byte[] rom = Files.readAllBytes(entrada.toPath());
        byte[] original = rom.clone();
        FondoService.Espacio espacio = new FondoService.Espacio(
                opciones.getOrDefault("espacio", FondoService.ESPACIO_POR_DEFECTO));
        boolean forzar = opciones.containsKey("forzar");

        int hechos = 0;
        for (Escenario e : cuales(opciones.get("escenarios"))) {
            Fondo fondo = Fondo.cargar(carpeta, e, rom);
            if (!editado(carpeta, e) && !forzar) {
                Log.pnl("  {0}: sin editar, se deja como está.", e.nombre());
                continue;
            }
            FondoService.Resultado r = FondoService.inyectar(rom, fondo, espacio);
            Log.pf("  %s: %d tiles; rutina en 0x%06X, tiles en 0x%06X (%d B), mapas en 0x%06X (%d B) y 0x%06X (%d B)%n",
                    e.nombre(), fondo.tiles.length, r.rutina(), r.tiles(), r.bytesTiles(), r.mapaA(), r.bytesMapaA(),
                    r.mapaB(), r.bytesMapaB());
            if (r.tilesAnimacionCambiados() > 0) {
                Log.pnl("    tiles de animación reescritos: {0}", r.tilesAnimacionCambiados());
            }
            if (r.paletaCambiada()) {
                Log.pnl("    paleta del escenario reescrita.");
            }
            hechos++;
        }
        if (hechos == 0) {
            Log.pnl("No había ningún escenario editado. La ROM sale igual que entró.");
        }
        FondoService.terminar(rom);
        Files.write(salida.toPath(), rom);
        Log.pnl("ROM escrita: {0}", salida.getPath());

        String base = opciones.get("ips-base");
        if (base != null) {
            File baseFile = new File(base);
            if (!baseFile.isFile()) {
                throw new IOException("No existe la ROM base del IPS: " + base);
            }
            IpsService.write(Files.readAllBytes(baseFile.toPath()), rom, salida.getPath());
        } else if (opciones.containsKey("ips")) {
            IpsService.write(original, rom, salida.getPath());
        }
    }

    /**
     * Un escenario cuenta como editado cuando el editor ha guardado lo suyo. Los demás se dejan en paz para no
     * mover bloques ni gastar espacio sin motivo.
     */
    static boolean editado(File carpeta, Escenario e) {
        return new File(carpeta, e.carpeta() + "/fondo.properties").isFile();
    }

    static List<Escenario> cuales(String lista) {
        if (lista == null || lista.isBlank()) {
            return Escenario.TODOS;
        }
        List<Escenario> out = new ArrayList<>();
        for (String parte : lista.split(",")) {
            out.add(Escenario.porNumero(Integer.parseInt(parte.trim())));
        }
        return out;
    }

    static Map<String, String> opciones(String[] args, int desde) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = desde; i < args.length; i++) {
            int igual = args[i].indexOf('=');
            if (igual > 0) {
                out.put(args[i].substring(0, igual).toLowerCase(), args[i].substring(igual + 1));
            } else {
                out.put(args[i].toLowerCase(), "");
            }
        }
        return out;
    }

    public static void ayuda() {
        Log.pnl("          fondos \"carpeta-fondos\" \"rom.bin\"                        editor de fondos de combate");
        Log.pnl("          fondos-rom \"carpeta-fondos\" \"rom.bin\" \"salida.bin\" [opciones]   mete los fondos sin abrir el editor");
        Log.pnl("            opciones: escenarios=0,4  espacio=0x2D6C90-0x2D9FF0,...  forzar  ips  ips-base=\"rom limpia.md\"");
    }
}
