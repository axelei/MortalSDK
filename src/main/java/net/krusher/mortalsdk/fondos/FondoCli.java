package net.krusher.mortalsdk.fondos;

import net.krusher.mortalsdk.Log;

import java.io.File;

/**
 * El editor de fondos, que va por su cuenta.
 * <p>
 * Extraerlos y meterlos en la ROM no está aquí: eso lo hacen {@code x} e {@code i} como con todo lo demás, con
 * las propiedades {@code fondos} y {@code fondosSpace}. Aquí sólo se abre la ventana para pintar.
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

    public static void ayuda() {
        Log.pnl("          fondos \"carpeta-fondos\" \"rom.bin\"                editor de fondos de combate");
        Log.pnl("            (extraerlos y meterlos en la ROM va en x e i, con las propiedades fondos y fondosSpace)");
    }
}
