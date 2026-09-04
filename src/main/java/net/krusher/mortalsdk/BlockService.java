package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class BlockService {

    private BlockService() {}

    /**
     * Busca los bloques RNC de la ROM y los deja descomprimidos en la carpeta "extracted".
     */
    public static void extractCompressedBlocks(byte[] fileData) throws IOException {
        List<RncService.Block> blocks = RncService.search(fileData);
        Map<Integer, Integer> palettes = PaletteService.detect(fileData, addressesOf(blocks));
        Map<Integer, byte[]> data = new HashMap<>();
        for (RncService.Block block : blocks) {
            data.put(block.address(), block.data());
        }

        Set<Integer> inScene = new HashSet<>();
        List<SceneService.Scene> scenes = SceneService.find(blocks, fileData);
        for (SceneService.Scene scene : scenes) {
            int[] palette = PaletteService.forBlock(scene.graphicsAddress(), fileData, palettes);
            Bitmap image = SceneService.render(SceneService.mapOf(scene, data, fileData),
                    data.get(scene.graphicsAddress()), palette);
            Png.write(image, SceneService.fileOf(scene));
            inScene.add(scene.mapAddress());
            inScene.add(scene.graphicsAddress());
        }

        for (RncService.Block block : blocks) {
            if (inScene.contains(block.address())) {
                continue;
            }
            int[] palette = PaletteService.forBlock(block.address(), fileData, palettes);
            Png.write(TileService.toBitmap(block.data(), palette),
                    new File("extracted", "data_" + toHexStringPadded(block.address()) + ".png"));
        }
        Log.pnl("Bloques comprimidos extraídos: {0} ({1} pantallas completas, {2} con su paleta de la ROM)",
                blocks.size(), scenes.size(), palettes.size());
    }

    private static Set<Integer> addressesOf(List<RncService.Block> blocks) {
        Set<Integer> addresses = new HashSet<>();
        for (RncService.Block block : blocks) {
            addresses.add(block.address());
        }
        return addresses;
    }

    /**
     * Vuelve a comprimir los bloques de la carpeta "extracted" y los mete en la ROM. El hueco de cada uno es
     * el que ocupaba en la ROM original. Si el bloque recomprimido no cabe, se busca un puntero de tres bytes
     * a su dirección: sólo si existe se mueve al espacio libre y se corrige el puntero.
     */
    public static void injectCompressedBlocks(File[] extractedFiles, byte[] fileData, byte[] originalData) throws IOException {
        List<RncService.Block> blocks = RncService.search(originalData);
        Map<Integer, Integer> room = new HashMap<>();
        Map<Integer, Integer> sizes = new HashMap<>();
        Map<Integer, byte[]> original = new HashMap<>();
        for (RncService.Block block : blocks) {
            room.put(block.address(), block.packedSize());
            sizes.put(block.address(), block.data().length);
            original.put(block.address(), block.data());
        }
        Map<Integer, Integer> palettes = PaletteService.detect(originalData, addressesOf(blocks));

        // qué hay que meter en cada dirección, venga de una pantalla o de una hoja de tiles suelta
        Map<Integer, byte[]> pending = new LinkedHashMap<>();
        Map<Integer, String> origin = new LinkedHashMap<>();

        // las pantallas que comparten bloque de gráficos se rehacen juntas, o una pisaría a las otras
        Map<Integer, List<SceneService.Scene>> byGraphics = new LinkedHashMap<>();
        for (SceneService.Scene scene : SceneService.find(blocks, originalData)) {
            byGraphics.computeIfAbsent(scene.graphicsAddress(), k -> new ArrayList<>()).add(scene);
        }
        for (Map.Entry<Integer, List<SceneService.Scene>> group : byGraphics.entrySet()) {
            List<SceneService.Input> inputs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Set<Integer> rawMaps = new HashSet<>();
            for (SceneService.Scene scene : group.getValue()) {
                if (!scene.compressedMap()) {
                    rawMaps.add(scene.mapAddress());
                }
            }
            for (SceneService.Scene scene : group.getValue()) {
                File file = SceneService.fileOf(scene);
                if (!file.exists()) {
                    continue;
                }
                inputs.add(new SceneService.Input(scene.mapAddress(),
                        SceneService.mapOf(scene, original, originalData), Png.read(file)));
                names.add(file.getName());
            }
            if (inputs.size() != group.getValue().size()) {
                Log.pnl();
                Log.p("Faltan pantallas de las que comparten el bloque {0}, no se tocará.",
                        toHexStringPadded(group.getKey()));
                continue;
            }
            int[] palette = PaletteService.forBlock(group.getKey(), originalData, palettes);
            try {
                SceneService.Rebuilt rebuilt =
                        SceneService.rebuild(original.get(group.getKey()), inputs, palette);
                pending.put(group.getKey(), rebuilt.graphics());
                origin.put(group.getKey(), String.join(", ", names));
                for (Map.Entry<Integer, byte[]> map : rebuilt.maps().entrySet()) {
                    if (rawMaps.contains(map.getKey())) {
                        // mapa sin comprimir: se escribe tal cual, que ocupa lo mismo que ocupaba
                        System.arraycopy(map.getValue(), 0, fileData, map.getKey(), map.getValue().length);
                        continue;
                    }
                    pending.put(map.getKey(), map.getValue());
                    origin.put(map.getKey(), String.join(", ", names));
                }
            } catch (IOException e) {
                Log.pnl();
                Log.p("No se han podido rehacer las pantallas {0}: {1}", String.join(", ", names), e.getMessage());
            }
        }

        for (File extractedFile : extractedFiles) {
            String name = extractedFile.getName();
            if (!name.startsWith("data_") || !name.endsWith(".png")) {
                continue;
            }
            int address = parseAddress(name);
            if (pending.containsKey(address)) {
                continue;
            }
            Integer originalSize = sizes.get(address);
            if (Objects.isNull(originalSize)) {
                Log.pnl();
                Log.p("El bloque {0} no estaba comprimido en la ROM original, no se inyectará.", name);
                continue;
            }
            int[] palette = PaletteService.forBlock(address, originalData, palettes);
            Bitmap image = Png.read(extractedFile);
            if (image.getWidth() != TileService.COLUMNS * TileService.TILE_SIZE) {
                Log.pnl();
                Log.p("Alerta: {0} mide {1} de ancho y deberían ser {2}; el orden de los tiles depende del ancho.",
                        name, image.getWidth(), TileService.COLUMNS * TileService.TILE_SIZE);
            }
            pending.put(address, TileService.toTiles(image, palette, originalSize));
            origin.put(address, name);
        }

        for (Map.Entry<Integer, byte[]> entry : pending.entrySet()) {
            injectCompressedBlock(entry.getKey(), entry.getValue(), origin.get(entry.getKey()),
                    room.get(entry.getKey()), fileData, originalData);
        }
        Log.pnl();
    }

    private static void injectCompressedBlock(int address, byte[] blockData, String name, Integer originalSize,
                                              byte[] fileData, byte[] originalData) throws IOException {
        byte[] compressedData;
        try {
            compressedData = RncService.pack(blockData, RncService.METHOD_1);
        } catch (RncException e) {
            Log.pnl();
            Log.p("No se ha podido comprimir {0}: {1}", name, e.getMessage());
            return;
        }
        Log.p(" " + toHexStringPadded(address));

        if (originalSize >= compressedData.length) {
            System.arraycopy(compressedData, 0, fileData, address, compressedData.length);
            Arrays.fill(fileData, address + compressedData.length, address + originalSize, (byte) 0x00);
            return;
        }

        Log.p(" Bloque comprimido {0} mayor que su hueco. ", name);
        // se busca en la ROM original, para que lo ya inyectado no altere la búsqueda
        Integer pointer = TexticleService.findPointerAddress(address, originalData);
        Integer newAddress = TexticleService.getNewAddress(compressedData.length);
        if (Objects.nonNull(pointer) && Objects.nonNull(newAddress)
                && newAddress + compressedData.length <= fileData.length) {
            Log.pnl("Se inyectará en la dirección {0}.", toHexStringPadded(newAddress));
            System.arraycopy(compressedData, 0, fileData, newAddress, compressedData.length);
            TexticleService.writeThreeBytes(fileData, pointer, newAddress);
            Arrays.fill(fileData, address, address + originalSize, (byte) 0x00);
        } else {
            Log.pnl("No se inyectará.");
        }
    }

    /**
     * Inyecta los bloques sin comprimir de la carpeta "extracted".
     * <p>
     * No se inyectan los bloques borrados (los que ya no tienen fichero) ni los que no se han modificado
     * respecto a la ROM original. Estos bloques no se reubican: si uno no cabe en su hueco se avisa y se
     * deja como estaba, porque no tienen por qué ser direccionables por puntero.
     */
    public static void injectUncompressedBlocks(File[] extractedFiles, byte[] fileData, byte[] originalData,
                                                String extension, Set<Range> ranges) throws IOException {
        Map<Integer, Range> rangesByAddress = new HashMap<>();
        for (Range range : ranges) {
            rangesByAddress.put(range.getFrom(), range);
        }
        Set<Integer> found = new HashSet<>();
        for (File extractedFile : extractedFiles) {
            String name = extractedFile.getName();
            if (!name.startsWith(extension + "_")) {
                continue;
            }
            int address = parseAddress(name);
            found.add(address);
            byte[] blockData = Files.readAllBytes(extractedFile.toPath());
            Range range = rangesByAddress.get(address);
            int room = Objects.nonNull(range) ? range.size() : blockData.length;
            if (isUnmodified(blockData, originalData, address, room)) {
                continue;
            }
            if (blockData.length > room) {
                Log.pnl();
                Log.p("El bloque {0} ocupa {1} bytes, más que los {2} de su hueco, y no se puede reubicar. No se inyectará.",
                        name, blockData.length, room);
                continue;
            }
            Log.p(" " + name);
            System.arraycopy(blockData, 0, fileData, address, blockData.length);
            Arrays.fill(fileData, address + blockData.length, address + room, (byte) 0x00);
        }
        for (Range range : ranges) {
            if (!found.contains(range.getFrom())) {
                Log.pnl();
                Log.p("Bloque {0} borrado, no se inyectará.", toHexStringPadded(range.getFrom()));
            }
        }
    }

    private static boolean isUnmodified(byte[] blockData, byte[] originalData, int address, int room) {
        return blockData.length == room
                && Arrays.equals(blockData, 0, blockData.length, originalData, address, address + blockData.length);
    }

    private static int parseAddress(String fileName) {
        return Integer.parseInt(fileName.substring(fileName.lastIndexOf('_') + 1, fileName.lastIndexOf('.')), 16);
    }


    public static void extractUncompressedBlock(Set<Range> ranges, String extension, byte[] fileData) throws IOException {
        for (Range range : ranges) {
            int start = range.getFrom();
            int end = range.getTo();
            byte[] block = new byte[end - start + 1];
            System.arraycopy(fileData, start, block, 0, end - start + 1);
            String fileName = "extracted/" + extension + "_" + toHexStringPadded(start) + "." + extension;
            FileOutputStream fos = new FileOutputStream(fileName);
            fos.write(block);
            fos.close();
        }
    }

    public static String toHexStringPadded(int address) {
        return StringUtils.leftPad(Integer.toHexString(address), 6, '0');
    }

}
