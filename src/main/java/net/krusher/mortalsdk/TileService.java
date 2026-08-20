package net.krusher.mortalsdk;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class TileService {

    private static final int TILE_BYTES = 32;
    private static final int TILE_SIZE = 8;

    private TileService() {}

    public static void exportPreviews(File extractedDirectory, int tilesPerRow) throws IOException {
        File[] blocks = extractedDirectory.listFiles((dir, name) -> name.startsWith("data_") && name.endsWith(".bin"));
        if (blocks == null) {
            return;
        }
        File previews = new File(extractedDirectory, "previews");
        Files.createDirectories(previews.toPath());
        for (File block : blocks) {
            if (block.length() == 0 || block.length() % TILE_BYTES != 0) {
                continue;
            }
            byte[] data = Files.readAllBytes(block.toPath());
            BufferedImage image = decode4Bpp(data, tilesPerRow);
            ImageIO.write(image, "png", new File(previews, block.getName().replace(".bin", ".png")));
        }
    }

    public static void importPreviews(File extractedDirectory) throws IOException {
        File previews = new File(extractedDirectory, "previews");
        File[] images = previews.listFiles((dir, name) -> name.startsWith("data_") && name.endsWith(".png"));
        if (images == null) {
            return;
        }
        for (File imageFile : images) {
            File block = new File(extractedDirectory, imageFile.getName().replace(".png", ".bin"));
            if (!block.isFile() || block.length() % TILE_BYTES != 0) {
                continue;
            }
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                throw new IOException("PNG no válido: " + imageFile.getName());
            }
            byte[] data = encode4Bpp(image, Math.toIntExact(block.length() / TILE_BYTES));
            Files.write(block.toPath(), data);
        }
    }

    static BufferedImage decode4Bpp(byte[] data, int tilesPerRow) {
        if (data.length % TILE_BYTES != 0 || tilesPerRow < 1) {
            throw new IllegalArgumentException("Los tiles 4bpp deben ocupar múltiplos de 32 bytes");
        }
        int tileCount = data.length / TILE_BYTES;
        int columns = Math.min(tilesPerRow, tileCount);
        int rows = (tileCount + columns - 1) / columns;
        BufferedImage image = new BufferedImage(columns * TILE_SIZE, rows * TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int tile = 0; tile < tileCount; tile++) {
            int tileX = tile % columns * TILE_SIZE;
            int tileY = tile / columns * TILE_SIZE;
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    int packed = data[tile * TILE_BYTES + y * 4 + x / 2] & 0xff;
                    int index = x % 2 == 0 ? packed >>> 4 : packed & 0x0f;
                    int level = index * 17;
                    image.setRGB(tileX + x, tileY + y, (level << 16) | (level << 8) | level);
                }
            }
        }
        return image;
    }

    static byte[] encode4Bpp(BufferedImage image, int tileCount) throws IOException {
        if (image.getWidth() % TILE_SIZE != 0 || image.getHeight() % TILE_SIZE != 0) {
            throw new IOException("El PNG debe medir múltiplos de 8 píxeles");
        }
        int columns = image.getWidth() / TILE_SIZE;
        if (columns * (image.getHeight() / TILE_SIZE) < tileCount) {
            throw new IOException("El PNG no contiene los " + tileCount + " tiles esperados");
        }
        byte[] data = new byte[tileCount * TILE_BYTES];
        for (int tile = 0; tile < tileCount; tile++) {
            int tileX = tile % columns * TILE_SIZE;
            int tileY = tile / columns * TILE_SIZE;
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x += 2) {
                    int high = paletteIndex(image.getRGB(tileX + x, tileY + y));
                    int low = paletteIndex(image.getRGB(tileX + x + 1, tileY + y));
                    data[tile * TILE_BYTES + y * 4 + x / 2] = (byte) ((high << 4) | low);
                }
            }
        }
        return data;
    }

    private static int paletteIndex(int rgb) throws IOException {
        int red = rgb >> 16 & 0xff;
        int green = rgb >> 8 & 0xff;
        int blue = rgb & 0xff;
        if (red != green || green != blue || red % 17 != 0) {
            throw new IOException("El PNG usa un color ajeno a la paleta índice de 16 grises");
        }
        return red / 17;
    }
}
