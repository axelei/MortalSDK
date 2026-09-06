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

    public static void main( String[] args ) throws IOException {

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

    public static void extract(String file) throws IOException {
        Log.pnl("Modo: Extraer");
        Log.pnl("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        Log.pnl("Extrayendo bloques comprimidos...");
        BlockService.extractCompressedBlocks(fileData);
        Log.pnl("Extrayendo samples PCM...");
        SampleService.extract(fileData);
        Log.pnl("Extrayendo datos sin comprimir...");
        BlockService.extractUncompressedBlock(config.bins(), "bin", fileData);
        Log.pnl("Extrayendo textos...");
        List<Texticle> texts = TexticleService.findTexticles(fileData);
        Log.pnl("Extracción terminada, escribiendo salida...");
        TexticleService.dumpTexticles(texts, file);
        Log.pnl("Salida escrita en: " + file + ".txt");
    }

    public static void inject(String file) throws IOException {
        Log.pnl("Modo: Inyectar");
        Log.pnl("Leyendo archivo: " + file);
        byte[] fileData = Files.readAllBytes(Paths.get(file));
        byte[] originalData = fileData.clone();
        // con la SRAM habilitada esas direcciones dejan de devolver la ROM, así que no se reparte nada ahí
        Range sram = HeaderService.sramWindow(fileData);
        TexticleService.setSramWindow(sram);
        if (sram != null) {
            Log.pnl("La ROM lleva SRAM: no se usará de {0} a {1} para colocar nada.",
                    Integer.toHexString(sram.getFrom()), Integer.toHexString(sram.getTo()));
        }
        Log.pnl("Inyectando bloques...");
        File extractedDir = new File("extracted");
        File[] extractedFiles = extractedDir.listFiles();
        if (extractedFiles == null || extractedFiles.length == 0) {
            Log.pnl("No se encontraron archivos extraídos en la carpeta 'extracted'");
        } else {
            Log.p("Inyectando bloques comprimidos:");
            BlockService.injectCompressedBlocks(extractedFiles, fileData, originalData);
            Log.p("Inyectando samples PCM:");
            SampleService.inject(extractedFiles, fileData, originalData);
            Log.pnl();
            Log.p("Inyectando bloques sin comprimir:");
            BlockService.injectUncompressedBlocks(extractedFiles, fileData, originalData, "bin", config.bins());
            Log.pnl();
        }
        Log.pnl("Inyectando textos...");
        TexticleService.insertTexticles(file, fileData, originalData);
        CodeService.skipRoutines(fileData);
        CodeService.applyPatches(fileData);
        Log.pnl("Inyección terminada.");
        // la intro va la última, sobre la ROM ya reescrita, y antes del checksum
        Log.pnl("Inyectando intro...");
        IntroService.inject(fileData, originalData);
        // el nombre va el último: así manda sobre lo que hayan escrito los textos en la cabecera
        HeaderService.writeName(fileData);
        Log.pnl("Arreglando checksum...");
        Checksum.fixChecksum(fileData);
        Log.pnl("Escribiendo salida...");
        File outputFile = new File(file + ".patched.bin");
        Files.write(outputFile.toPath(), fileData);
        Log.pnl("Salida escrita en: " + outputFile.getAbsolutePath());
        // lo que se reparte no es la ROM, que es casi toda del juego, sino el parche con lo que hemos puesto
        Log.pnl("Escribiendo el parche...");
        IpsService.write(ipsBase(originalData), fileData, file);
    }

    /**
     * La ROM contra la que se calcula el parche: la de {@code ipsBase} si la configuración la pide, y si no
     * la de entrada. Sirve para repartir un parche que se aplique sobre otra ROM, como la del juego original
     * en vez de la del hack del que se parte.
     */
    private static byte[] ipsBase(byte[] originalData) throws IOException {
        if (StringUtils.isBlank(config.ipsBase())) {
            return originalData;
        }
        File baseFile = new File(config.ipsBase());
        if (!baseFile.isFile()) {
            throw new IllegalStateException("La ROM de ipsBase no existe: " + baseFile.getAbsolutePath());
        }
        Log.pnl("El parche se calcula contra: " + baseFile.getAbsolutePath());
        return Files.readAllBytes(baseFile.toPath());
    }

    public static void displayHelp() {
        Log.pnl("Debe especificarse modo y archivo");
        Log.pnl("Ejemplos: x \"rom a extraer.bin\" [\"configuracion\"]");
        Log.pnl("          i \"rom a inyectar.bin\" [\"configuracion\"]");
        Log.pnl("Modo: x = extraer, i = inyectar");
        Log.pnl("Configuración: Opcional, se puede dejar en blanco y se usará una por defecto.");
        Log.pnl("Ejemplos de configuración en el directorio \"configs\".");
    }
}
