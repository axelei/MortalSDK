package net.krusher.mortalsdk;

import com.google.common.collect.BiMap;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * MortalSDK by Krusher
 */
public class App {

    static Config config;
    static BiMap<String, String> tbl;

    public static void main( String[] args ) throws IOException, InterruptedException {

        Log.pnl("MortalSDK by Krusher - Programa bajo licencia GPL 3");

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
        }
        Log.pnl("Extrayendo datos sin comprimir...");
        BlockService.extractUncompressedBlock(config.sounds(), "pcm", fileData);
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
            if (StringUtils.isNotBlank(config.proPackExe())) {
                Log.p("Inyectando bloques comprimidos:");
                BlockService.injectCompressedBlocks(extractedFiles, fileData);
            }
            Log.p("Inyectando bloques sin comprimir: ");
            BlockService.injectUncompressedBlocks(extractedFiles, fileData, "pcm");
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
        Log.pnl("Modo: x = extraer, i = inyectar");
        Log.pnl("Configuracion: Opcional, se puede dejar en blanco y se usará una por defecto.");
        Log.pnl("Ejemplos de configuracion en el directorio \"configs\".");
    }
}
