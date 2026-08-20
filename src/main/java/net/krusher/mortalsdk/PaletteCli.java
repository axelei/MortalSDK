package net.krusher.mortalsdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class PaletteCli {

    private PaletteCli() {}

    static void run(String[] args) throws IOException {
        if (args.length == 4 && args[1].equals("scan")) {
            scan(args);
            return;
        }
        if (args.length == 6 && args[1].equals("render")) {
            render(args);
            return;
        }
        if (args.length == 5 && args[1].equals("report")) {
            report(args);
            return;
        }
        throw new IOException("Uso: palette scan ROM DIRECTORIO | palette render ROM OFFSET TILES.BIN SALIDA.PNG"
                + " | palette report ROM EXTRACTED_DIR SALIDA.HTML");
    }

    private static void scan(String[] args) throws IOException {
        byte[] rom = Files.readAllBytes(new File(args[2]).toPath());
        File output = new File(args[3]);
        var palettes = PaletteService.findReferencedPalettes(rom);
        PaletteService.exportCandidates(rom, palettes, output);
        Log.pnl(palettes.size() + " paletas referenciadas exportadas en: " + output.getAbsolutePath());
    }

    private static void render(String[] args) throws IOException {
        byte[] rom = Files.readAllBytes(new File(args[2]).toPath());
        int offset;
        try {
            offset = Integer.decode(args[3]);
        } catch (NumberFormatException error) {
            throw new IOException("Offset de paleta no valido: " + args[3], error);
        }
        byte[] tiles = Files.readAllBytes(new File(args[4]).toPath());
        PaletteService.renderTiles(rom, offset, tiles, new File(args[5]));
        Log.pnl("Preview coloreada: " + new File(args[5]).getAbsolutePath());
    }

    private static void report(String[] args) throws IOException {
        byte[] rom = Files.readAllBytes(new File(args[2]).toPath());
        File extracted = new File(args[3]);
        File output = new File(args[4]);
        PaletteService.exportHtmlReport(rom, PaletteService.findReferencedPalettes(rom), extracted, output);
        Log.pnl("Informe HTML de paletas: " + output.getAbsolutePath());
    }
}
