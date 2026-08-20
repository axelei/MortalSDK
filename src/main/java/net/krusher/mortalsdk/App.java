package net.krusher.mortalsdk;

import com.google.common.collect.BiMap;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * MortalSDK by Krusher
 */
public class App {

    static Config config;
    static BiMap<String, String> tbl;

    public static void main( String[] args ) throws IOException, InterruptedException {

        Log.pnl("MortalSDK by Krusher - Programa bajo licencia GPL 3");

        if (args.length > 0 && args[0].equals("sample")) {
            SampleCli.run(args);
            return;
        }
        if (args.length > 0 && args[0].equals("palette")) {
            PaletteCli.run(args);
            return;
        }

        //check parameters
        if (args.length < 2) {
            displayHelp();
            System.exit(1);
        }

        //parse config if exists
        if (args.length > 2) {
            config = Config.getInstance(args[2]);
        } else {
            config = new Config();
        }

        if (args[0].equals("gui")) {
            Config guiConfig = config;
            SwingUtilities.invokeLater(() -> {
                try {
                    new SampleEditor(new File(args[1]), guiConfig).show();
                } catch (IOException e) {
                    SampleEditor.showError(null, e);
                }
            });
            return;
        }

        //parse tbl if exists
        tbl = TblService.readTbl(args[1]);

        //check mode
        if (args[0].equals("x")) {
            extract(args[1]);
        } else if (args[0].equals("i")) {
            inject(args[1]);
        } else {
            displayHelp();
            System.exit(1);
        }

        System.exit(0);
    }

    public static void extract(String file) throws IOException, InterruptedException {
        Log.pnl("Modo: Extraer");
        Log.pnl("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        if (StringUtils.isNotBlank(config.proPackExe())) {
            Log.pnl("Extrayendo bloques...");
            BlockService.extractCompressedBlocks(file);
            TileService.exportPreviews(new File("extracted"), 16);
        }
        Log.pnl("Extrayendo datos sin comprimir...");
        BlockService.extractUncompressedBlock(config.sounds(), "pcm", fileData);
        WavService.exportPcmFiles(new File("extracted"), config.pcmSampleRate());
        SampleTableService.exportSamples(fileData, new File("extracted/samples"), config);
        PaletteService.exportCandidates(fileData, PaletteService.findReferencedPalettes(fileData),
                new File("extracted/palettes"));
        BlockService.extractUncompressedBlock(config.music(), "music", fileData);
        BlockService.extractUncompressedBlock(config.bins(), "bin", fileData);
        Log.pnl("Extrayendo textos...");
        List<Texticle> texts = TexticleService.findTexticles(fileData);
        Log.pnl("Extracción terminada, escribiendo salida...");
        TexticleService.dumpTexticles(texts, file);
        Log.pnl("Salida escrita en: " + file + ".txt");
    }

    public static void inject(String file) throws IOException, InterruptedException {
        Log.pnl("Modo: Inyectar");
        Log.pnl("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        Log.pnl("Inyectando bloques...");
        File extractedDir = new File("extracted");
        File[] extractedFiles = extractedDir.listFiles();
        if (extractedFiles == null || extractedFiles.length == 0) {
            Log.pnl("No se encontraron archivos extraídos en la carpeta 'extracted'");
        } else {
            TileService.importPreviews(extractedDir);
            if (StringUtils.isNotBlank(config.proPackExe())) {
                Log.p("Inyectando bloques comprimidos:");
                BlockService.injectCompressedBlocks(extractedFiles, fileData);
            }
            Log.p("Inyectando bloques sin comprimir: ");
            BlockService.injectUncompressedBlocks(extractedFiles, fileData, "pcm");
            WavService.injectWavFiles(extractedFiles, fileData);
            SampleTableService.injectSamples(fileData, new File("extracted/samples"), config);
            PaletteService.injectPalettes(fileData, new File("extracted/palettes"));
            BlockService.injectUncompressedBlocks(extractedFiles, fileData, "music");
            BlockService.injectUncompressedBlocks(extractedFiles, fileData, "bin");
            Log.pnl();
        }
        Log.pnl("Inyectando textos...");
        TexticleService.insertTexticles(file, fileData);
        Log.pnl("Inyección terminada.");
        Log.pnl("Arreglando checksum...");
        Checksum.fixChecksum(fileData);
        Log.pnl("Escribiendo salida...");
        File outputFile = new File(file + ".patched.bin");
        Files.write(outputFile.toPath(), fileData);
        Log.pnl("Salida escrita en: " + outputFile.getAbsolutePath());
    }

    public static void displayHelp() {
        Log.pnl("Debe especificarse modo y archivo");
        Log.pnl("Ejemplos: x \"rom a extraer.bin\" [\"configuracion\"]");
        Log.pnl("          i \"rom a inyectar.bin\" [\"configuracion\"]");
        Log.pnl("          gui \"rom.bin\" \"configuracion\"");
        Log.pnl("          sample list \"rom.bin\" \"configuracion\"");
        Log.pnl("          sample extract \"rom.bin\" \"directorio\" \"configuracion\"");
        Log.pnl("          sample replace \"rom.bin\" \"salida.bin\" \"configuracion\" ID \"audio.wav\" [ID \"audio.wav\" ...]");
        Log.pnl("          palette scan \"rom.bin\" \"directorio\"");
        Log.pnl("          palette render \"rom.bin\" OFFSET \"tiles.bin\" \"salida.png\"");
        Log.pnl("          palette report \"rom.bin\" \"extracted\" \"paletas.html\"");
        Log.pnl("Modo: x = extraer, i = inyectar, gui = editor de muestras");
        Log.pnl("Configuracion: opcional solo en los modos x e i; obligatoria para gui y sample.");
        Log.pnl("Ejemplos de configuracion en el directorio \"configs\".");
    }
}
