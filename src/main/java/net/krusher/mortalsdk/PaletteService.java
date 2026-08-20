package net.krusher.mortalsdk;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

public final class PaletteService {

    private static final int COLORS = 16;
    private static final int PALETTE_BYTES = COLORS * 2;

    private PaletteService() {}

    public static List<PaletteCandidate> findReferencedPalettes(byte[] rom) {
        Map<Integer, List<Integer>> references = new LinkedHashMap<>();
        for (int position = 0; position <= rom.length - 4; position++) {
            int target = readLong(rom, position);
            if (target < 0 || target > rom.length - PALETTE_BYTES || (target & 1) != 0
                    || !looksLikePalette(rom, target)) {
                continue;
            }
            references.computeIfAbsent(target, ignored -> new ArrayList<>()).add(position);
        }
        return references.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PaletteCandidate(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    public static void exportCandidates(byte[] rom, List<PaletteCandidate> candidates,
                                        File outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory.toPath());
        StringBuilder manifest = new StringBuilder("offset,references\n");
        int rowHeight = 34;
        BufferedImage sheet = new BufferedImage(420, Math.max(rowHeight, candidates.size() * rowHeight),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        for (int row = 0; row < candidates.size(); row++) {
            PaletteCandidate candidate = candidates.get(row);
            byte[] raw = Arrays.copyOfRange(rom, candidate.offset(), candidate.offset() + PALETTE_BYTES);
            String baseName = String.format("palette_%06x", candidate.offset());
            Files.write(new File(outputDirectory, baseName + ".pal").toPath(), raw);
            BufferedImage preview = renderPalette(raw, 16);
            ImageIO.write(preview, "png", new File(outputDirectory, baseName + ".png"));

            int y = row * rowHeight;
            graphics.setColor(Color.WHITE);
            graphics.drawString(String.format("%06X", candidate.offset()), 4, y + 21);
            graphics.drawImage(preview, 64, y + 1, null);
            manifest.append(String.format("0x%06X,", candidate.offset()));
            manifest.append(candidate.references().stream()
                    .map(reference -> String.format("0x%06X", reference))
                    .reduce((left, right) -> left + " " + right).orElse(""));
            manifest.append('\n');
        }
        graphics.dispose();
        ImageIO.write(sheet, "png", new File(outputDirectory, "palette-sheet.png"));
        Files.writeString(new File(outputDirectory, "palette-references.csv").toPath(), manifest,
                StandardCharsets.UTF_8);
    }

    public static void injectPalettes(byte[] rom, File inputDirectory) throws IOException {
        File[] palettes = inputDirectory.listFiles((directory, name) ->
                name.startsWith("palette_") && name.endsWith(".pal"));
        if (palettes == null) {
            return;
        }
        for (File palette : palettes) {
            String address = palette.getName().substring("palette_".length(),
                    palette.getName().length() - ".pal".length());
            int offset;
            try {
                offset = Integer.parseInt(address, 16);
            } catch (NumberFormatException error) {
                throw new IOException("Nombre de paleta no valido: " + palette.getName(), error);
            }
            byte[] raw = Files.readAllBytes(palette.toPath());
            if (raw.length != PALETTE_BYTES || offset < 0 || offset > rom.length - PALETTE_BYTES) {
                throw new IOException("Paleta fuera de rango o con longitud incorrecta: " + palette.getName());
            }
            for (int color = 0; color < COLORS; color++) {
                if ((readWord(raw, color * 2) & 0xf111) != 0) {
                    throw new IOException("Color CRAM no valido en: " + palette.getName());
                }
            }
            System.arraycopy(raw, 0, rom, offset, raw.length);
        }
    }

    public static void exportHtmlReport(byte[] rom, List<PaletteCandidate> candidates,
                                        File extractedDirectory, File outputHtml) throws IOException {
        List<TileBlock> blocks = findTileBlocks(extractedDirectory);
        File parent = outputHtml.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        String assetDirectoryName = stripExtension(outputHtml.getName()) + "-assets";
        File assets = new File(parent, assetDirectoryName);
        Files.createDirectories(assets.toPath());

        StringBuilder cards = new StringBuilder();
        for (PaletteCandidate candidate : candidates) {
            byte[] raw = Arrays.copyOfRange(rom, candidate.offset(), candidate.offset() + PALETTE_BYTES);
            String id = String.format("%06x", candidate.offset());
            String paletteName = "palette_" + id + ".png";
            ImageIO.write(renderPalette(raw, 16), "png", new File(assets, paletteName));
            List<TileBlock> nearbyBlocks = blocks.stream()
                    .sorted(Comparator.comparingInt(item -> Math.abs(item.address() - candidate.offset())))
                    .limit(6).toList();
            StringBuilder previewHtml = new StringBuilder();
            for (TileBlock block : nearbyBlocks) {
                int distance = Math.abs(block.address() - candidate.offset());
                String previewName = "tiles_" + id + "_" + block.file().getName().replace(".bin", ".png");
                renderTiles(rom, candidate.offset(), Files.readAllBytes(block.file().toPath()),
                        new File(assets, previewName));
                previewHtml.append("<figure><div class=\"screen\"><img class=\"tiles\" src=\"")
                        .append(assetDirectoryName).append('/').append(previewName)
                        .append("\" alt=\"Preview de tiles\"></div><figcaption>")
                        .append(block.file().getName()).append(" · Δ0x")
                        .append(Integer.toHexString(distance).toUpperCase()).append("</figcaption></figure>");
            }
            if (nearbyBlocks.isEmpty()) {
                previewHtml.append("<div class=\"missing\">Sin bloques 4bpp extraídos</div>");
            }
            String refs = candidate.references().stream().map(value -> String.format("%06X", value))
                    .reduce((left, right) -> left + " " + right).orElse("-");
            cards.append("<article class=\"card\"><h2>0x").append(id.toUpperCase()).append("</h2>")
                    .append("<img class=\"palette\" src=\"").append(assetDirectoryName).append('/')
                    .append(paletteName).append("\" alt=\"Paleta\">")
                    .append("<div class=\"gallery\">").append(previewHtml).append("</div>")
                    .append("<dl><dt>Referencias</dt><dd>").append(refs).append("</dd></dl></article>\n");
        }

        String html = """
                <!doctype html><html lang="es"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Informe de paletas MortalSDK</title><style>
                :root{color-scheme:dark}body{margin:0;background:#111;color:#eee;font:14px system-ui,sans-serif}
                header{position:sticky;top:0;z-index:2;padding:18px 24px;background:#181818;border-bottom:1px solid #444}
                h1{margin:0 0 8px}header p{margin:4px 0;color:#bbb;max-width:1100px}
                main{display:grid;grid-template-columns:repeat(auto-fit,minmax(620px,1fr));gap:16px;padding:16px}
                .card{background:#202020;border:1px solid #444;border-radius:8px;padding:12px;min-width:0}
                h2{font:700 18px ui-monospace,monospace;margin:0 0 10px}.palette{width:256px;height:16px;image-rendering:pixelated;border:1px solid #666}
                .gallery{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:12px}figure{margin:0;min-width:0}
                .screen{height:220px;background:#080808;display:flex;align-items:center;justify-content:center;overflow:auto;border:1px solid #333}
                .tiles{max-width:100%;max-height:100%;image-rendering:pixelated}.missing{color:#888}
                figcaption{padding:4px 2px;color:#bbb;font:11px ui-monospace,monospace;overflow-wrap:anywhere}
                dl{display:grid;grid-template-columns:110px 1fr;gap:5px;margin:10px 0 0}dt{color:#aaa}dd{margin:0;font-family:ui-monospace,monospace;overflow-wrap:anywhere}
                @media(max-width:700px){main{grid-template-columns:1fr}.gallery{grid-template-columns:repeat(2,minmax(0,1fr))}}
                </style></head><body><header><h1>Paletas referenciadas</h1>
                <p>Cada cuadrante combina una paleta candidata con los seis bloques 4bpp extraídos cuyas direcciones son más cercanas. La proximidad es una heurística, no una asociación confirmada.</p>
                <p>Una pantalla real también necesita el tilemap, los atributos de cada tile y la selección de una de las cuatro líneas CRAM.</p></header><main>
                """ + cards + "</main></body></html>";
        Files.writeString(outputHtml.toPath(), html, StandardCharsets.UTF_8);
    }

    private static List<TileBlock> findTileBlocks(File extractedDirectory) {
        File[] files = extractedDirectory.listFiles((directory, name) ->
                name.startsWith("data_") && name.endsWith(".bin"));
        if (files == null) {
            return List.of();
        }
        List<TileBlock> result = new ArrayList<>();
        for (File file : files) {
            if (file.length() == 0 || file.length() % 32 != 0) {
                continue;
            }
            try {
                int address = Integer.parseInt(file.getName().substring(5, 11), 16);
                result.add(new TileBlock(address, file));
            } catch (RuntimeException ignored) {
                // Ignore files that do not follow the extractor naming convention.
            }
        }
        return result;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static BufferedImage renderPalette(byte[] raw, int scale) {
        if (raw.length != PALETTE_BYTES || scale < 1) {
            throw new IllegalArgumentException("Una paleta Mega Drive debe contener 32 bytes");
        }
        BufferedImage image = new BufferedImage(COLORS * scale, scale, BufferedImage.TYPE_INT_RGB);
        for (int color = 0; color < COLORS; color++) {
            int word = readWord(raw, color * 2);
            int red = expand3Bit(word >> 1 & 7);
            int green = expand3Bit(word >> 5 & 7);
            int blue = expand3Bit(word >> 9 & 7);
            int rgb = red << 16 | green << 8 | blue;
            for (int y = 0; y < scale; y++) {
                for (int x = 0; x < scale; x++) {
                    image.setRGB(color * scale + x, y, rgb);
                }
            }
        }
        return image;
    }

    public static void renderTiles(byte[] rom, int paletteOffset, byte[] tiles, File output) throws IOException {
        if (paletteOffset < 0 || paletteOffset > rom.length - PALETTE_BYTES) {
            throw new IOException("La paleta queda fuera de la ROM");
        }
        if (tiles.length == 0 || tiles.length % 32 != 0) {
            throw new IOException("Los tiles deben ocupar un multiplo de 32 bytes");
        }
        int[] colors = new int[COLORS];
        for (int color = 0; color < COLORS; color++) {
            int word = readWord(rom, paletteOffset + color * 2);
            colors[color] = expand3Bit(word >> 1 & 7) << 16
                    | expand3Bit(word >> 5 & 7) << 8 | expand3Bit(word >> 9 & 7);
        }
        int tileCount = tiles.length / 32;
        int columns = Math.min(16, tileCount);
        int rows = (tileCount + columns - 1) / columns;
        BufferedImage image = new BufferedImage(columns * 8, rows * 8, BufferedImage.TYPE_INT_RGB);
        for (int tile = 0; tile < tileCount; tile++) {
            int tileX = tile % columns * 8;
            int tileY = tile / columns * 8;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int packed = tiles[tile * 32 + y * 4 + x / 2] & 0xff;
                    int index = x % 2 == 0 ? packed >>> 4 : packed & 0x0f;
                    image.setRGB(tileX + x, tileY + y, colors[index]);
                }
            }
        }
        ImageIO.write(image, "png", output);
    }

    private static boolean looksLikePalette(byte[] rom, int offset) {
        HashSet<Integer> unique = new HashSet<>();
        for (int color = 0; color < COLORS; color++) {
            int word = readWord(rom, offset + color * 2);
            if ((word & 0xf111) != 0) {
                return false;
            }
            unique.add(word);
        }
        return unique.size() >= 6;
    }

    private static int expand3Bit(int value) {
        return value * 255 / 7;
    }

    private static int readWord(byte[] data, int offset) {
        return (data[offset] & 0xff) << 8 | data[offset + 1] & 0xff;
    }

    private static int readLong(byte[] data, int offset) {
        return data[offset] << 24 | (data[offset + 1] & 0xff) << 16
                | (data[offset + 2] & 0xff) << 8 | data[offset + 3] & 0xff;
    }

    public record PaletteCandidate(int offset, List<Integer> references) {}

    private record TileBlock(int address, File file) {}
}
