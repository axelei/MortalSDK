package net.krusher.mortalsdk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Lectura y escritura de PNG, en lugar de javax.imageio.
 * <p>
 * ImageIO no se puede usar desde una imagen nativa sin AWT, que necesita metadatos de JNI recogidos con un
 * agente y reparte tres DLL junto al ejecutable; java.util.zip, que es todo lo que un PNG necesita de
 * verdad, funciona tal cual. Hacerlo aquí es lo que mantiene el programa en un único fichero.
 * <p>
 * Al leer se acepta lo que pueda devolver un editor: profundidades de 1, 2, 4, 8 y 16 bits, los cinco tipos
 * de color, entrelazado o no, y todos los filtros. Al escribir siempre sale color indexado de 4 bits (tipo
 * 3), porque los tiles de Mega Drive son de 16 colores; así, además, un PNG editado se queda con la paleta
 * de la consola cuando se vuelve a abrir.
 * <p>
 * De un PNG indexado se conservan los índices de paleta, que es lo que de verdad guarda la ROM: así la ida y
 * vuelta es exacta aunque la paleta tenga colores repetidos.
 * <p>
 * Adaptado del que uso en CholeilSDK.
 */
public final class Png {

    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Profundidad al escribir: 16 colores caben en medio byte. */
    static final int WRITE_BIT_DEPTH = 4;

    /** Tipo de color 3 del PNG: indexado por paleta. */
    static final int COLOR_TYPE_INDEXED = 3;

    private Png() {}

    public static Bitmap read(File file) throws IOException {
        return decode(Files.readAllBytes(file.toPath()), file.getName());
    }

    public static void write(Bitmap image, File file) throws IOException {
        Files.write(file.toPath(), encode(image));
    }

    // ---------------------------------------------------------------- lectura

    static Bitmap decode(byte[] png, String what) throws IOException {
        if (png.length < SIGNATURE.length) {
            throw new IOException(what + ": demasiado corto para ser un PNG");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                throw new IOException(what + ": no es un PNG");
            }
        }

        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = 0;
        boolean interlaced = false;
        byte[] plte = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean sawHeader = false;

        int at = SIGNATURE.length;
        while (at + 8 <= png.length) {
            int length = readU32(png, at);
            String type = new String(png, at + 4, 4, StandardCharsets.US_ASCII);
            int data = at + 8;
            if (length < 0 || data + length + 4 > png.length) {
                throw new IOException(what + ": el trozo " + type + " está cortado");
            }
            switch (type) {
                case "IHDR":
                    width = readU32(png, data);
                    height = readU32(png, data + 4);
                    bitDepth = png[data + 8] & 0xFF;
                    colorType = png[data + 9] & 0xFF;
                    if ((png[data + 10] & 0xFF) != 0) {
                        throw new IOException(what + ": método de compresión desconocido");
                    }
                    if ((png[data + 11] & 0xFF) != 0) {
                        throw new IOException(what + ": método de filtrado desconocido");
                    }
                    interlaced = (png[data + 12] & 0xFF) != 0;
                    sawHeader = true;
                    break;
                case "PLTE":
                    plte = new byte[length];
                    System.arraycopy(png, data, plte, 0, length);
                    break;
                case "IDAT":
                    idat.write(png, data, length);
                    break;
                default:
                    break; // auxiliar, o IEND
            }
            if (type.equals("IEND")) {
                break;
            }
            at = data + length + 4; // saltando también el CRC del trozo
        }

        if (!sawHeader) {
            throw new IOException(what + ": no tiene trozo IHDR");
        }
        if (width <= 0 || height <= 0) {
            throw new IOException(what + ": imagen de tamaño cero");
        }
        if (colorType == COLOR_TYPE_INDEXED && plte == null) {
            throw new IOException(what + ": PNG indexado sin paleta");
        }

        byte[] raw = inflate(idat.toByteArray(), what);
        int channels = channelsFor(colorType, what);
        int[] argb = new int[width * height];
        byte[] indices = colorType == COLOR_TYPE_INDEXED ? new byte[width * height] : null;
        if (interlaced) {
            decodeInterlaced(raw, argb, indices, width, height, bitDepth, channels, colorType, plte, what);
        } else {
            decodePass(raw, 0, argb, indices, 0, 0, 1, 1, width, height, width,
                    bitDepth, channels, colorType, plte, what);
        }
        if (indices == null) {
            return Bitmap.trueColor(width, height, argb);
        }
        int[] palette = new int[plte.length / 3];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = 0xFF000000 | ((plte[i * 3] & 0xFF) << 16)
                    | ((plte[i * 3 + 1] & 0xFF) << 8) | (plte[i * 3 + 2] & 0xFF);
        }
        return Bitmap.decodedIndexed(width, height, argb, palette, indices);
    }

    /** Adam7: siete pasadas, cada una una imagen más pequeña repartida por la grande. */
    private static void decodeInterlaced(byte[] raw, int[] argb, byte[] indices, int width, int height,
                                         int bitDepth, int channels, int colorType, byte[] plte, String what)
            throws IOException {
        int[] xStart = {0, 4, 0, 2, 0, 1, 0};
        int[] yStart = {0, 0, 4, 0, 2, 0, 1};
        int[] xStep = {8, 8, 4, 4, 2, 2, 1};
        int[] yStep = {8, 8, 8, 4, 4, 2, 2};

        int offset = 0;
        for (int pass = 0; pass < 7; pass++) {
            int passWidth = (width - xStart[pass] + xStep[pass] - 1) / xStep[pass];
            int passHeight = (height - yStart[pass] + yStep[pass] - 1) / yStep[pass];
            if (passWidth <= 0 || passHeight <= 0) {
                continue;
            }
            offset += decodePass(raw, offset, argb, indices, xStart[pass], yStart[pass], xStep[pass], yStep[pass],
                    passWidth, passHeight, width, bitDepth, channels, colorType, plte, what);
        }
    }

    /**
     * Deshace el filtrado de una imagen (o de una pasada de Adam7) y la vuelca en {@code argb}, devolviendo
     * cuántos bytes de {@code raw} ha consumido.
     */
    private static int decodePass(byte[] raw, int offset, int[] argb, byte[] indices,
                                  int xStart, int yStart, int xStep, int yStep,
                                  int passWidth, int passHeight, int imageWidth,
                                  int bitDepth, int channels, int colorType, byte[] plte, String what)
            throws IOException {
        int bitsPerPixel = bitDepth * channels;
        int stride = (passWidth * bitsPerPixel + 7) / 8;
        int step = Math.max(1, bitsPerPixel / 8); // los filtros trabajan sobre bytes enteros
        int needed = (stride + 1) * passHeight;
        if (offset + needed > raw.length) {
            throw new IOException(what + ": los datos de la imagen se acaban antes de tiempo");
        }

        byte[] previous = new byte[stride];
        byte[] current = new byte[stride];
        int at = offset;
        for (int row = 0; row < passHeight; row++) {
            int filter = raw[at++] & 0xFF;
            System.arraycopy(raw, at, current, 0, stride);
            at += stride;
            unfilter(filter, current, previous, step, what);

            for (int col = 0; col < passWidth; col++) {
                int x = xStart + col * xStep;
                int y = yStart + row * yStep;
                argb[y * imageWidth + x] = pixelAt(current, col, bitDepth, channels, colorType, plte);
                if (indices != null) {
                    indices[y * imageWidth + x] = (byte) sample(current, col, bitDepth);
                }
            }
            byte[] swap = previous;
            previous = current;
            current = swap;
        }
        return needed;
    }

    private static void unfilter(int filter, byte[] line, byte[] prior, int step, String what) throws IOException {
        switch (filter) {
            case 0:
                break;
            case 1:
                for (int i = step; i < line.length; i++) {
                    line[i] += line[i - step];
                }
                break;
            case 2:
                for (int i = 0; i < line.length; i++) {
                    line[i] += prior[i];
                }
                break;
            case 3:
                for (int i = 0; i < line.length; i++) {
                    int left = i >= step ? (line[i - step] & 0xFF) : 0;
                    line[i] += (byte) ((left + (prior[i] & 0xFF)) / 2);
                }
                break;
            case 4:
                for (int i = 0; i < line.length; i++) {
                    int a = i >= step ? (line[i - step] & 0xFF) : 0;
                    int b = prior[i] & 0xFF;
                    int c = i >= step ? (prior[i - step] & 0xFF) : 0;
                    line[i] += (byte) paeth(a, b, c);
                }
                break;
            default:
                throw new IOException(what + ": filtro de fila desconocido: " + filter);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    /** Un píxel de una fila ya sin filtrar, en ARGB. */
    private static int pixelAt(byte[] line, int col, int bitDepth, int channels, int colorType, byte[] plte) {
        int base = col * channels;
        switch (colorType) {
            case 0: {
                int gray = scaleTo8(sample(line, base, bitDepth), bitDepth);
                return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
            case 2: {
                int r = scaleTo8(sample(line, base, bitDepth), bitDepth);
                int g = scaleTo8(sample(line, base + 1, bitDepth), bitDepth);
                int b = scaleTo8(sample(line, base + 2, bitDepth), bitDepth);
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            case COLOR_TYPE_INDEXED: {
                int index = sample(line, base, bitDepth);
                int at = index * 3;
                if (at + 2 >= plte.length) {
                    return 0xFF000000;
                }
                return 0xFF000000 | ((plte[at] & 0xFF) << 16) | ((plte[at + 1] & 0xFF) << 8) | (plte[at + 2] & 0xFF);
            }
            case 4: {
                int gray = scaleTo8(sample(line, base, bitDepth), bitDepth);
                int alpha = scaleTo8(sample(line, base + 1, bitDepth), bitDepth);
                return (alpha << 24) | (gray << 16) | (gray << 8) | gray;
            }
            default: {
                int r = scaleTo8(sample(line, base, bitDepth), bitDepth);
                int g = scaleTo8(sample(line, base + 1, bitDepth), bitDepth);
                int b = scaleTo8(sample(line, base + 2, bitDepth), bitDepth);
                int alpha = scaleTo8(sample(line, base + 3, bitDepth), bitDepth);
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** La muestra número {@code index} de una fila, sea cual sea la profundidad. */
    private static int sample(byte[] line, int index, int bitDepth) {
        switch (bitDepth) {
            case 8:
                return line[index] & 0xFF;
            case 16:
                return ((line[index * 2] & 0xFF) << 8) | (line[index * 2 + 1] & 0xFF);
            default: {
                int perByte = 8 / bitDepth;
                int b = line[index / perByte] & 0xFF;
                int shift = 8 - bitDepth * (index % perByte + 1);
                return (b >> shift) & ((1 << bitDepth) - 1);
            }
        }
    }

    /** Reparte una muestra de cualquier profundidad por todo el rango 0..255. */
    private static int scaleTo8(int value, int bitDepth) {
        switch (bitDepth) {
            case 8: return value;
            case 16: return value >> 8;
            case 4: return value * 17;
            case 2: return value * 85;
            default: return value * 255;
        }
    }

    private static int channelsFor(int colorType, String what) throws IOException {
        switch (colorType) {
            case 0: case COLOR_TYPE_INDEXED: return 1;
            case 4: return 2;
            case 2: return 3;
            case 6: return 4;
            default: throw new IOException(what + ": tipo de color desconocido: " + colorType);
        }
    }

    private static byte[] inflate(byte[] data, String what) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length * 4));
        byte[] buffer = new byte[16384];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException(what + ": datos de imagen corruptos: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- escritura

    static byte[] encode(Bitmap image) throws IOException {
        int[] palette = image.palette();
        byte[] indices = image.indices();
        if (palette == null || indices == null) {
            throw new IllegalStateException("sólo se escriben imágenes indexadas, y ésta no trae paleta");
        }
        if (palette.length > (1 << WRITE_BIT_DEPTH)) {
            throw new IllegalStateException("la paleta tiene " + palette.length + " colores, más de los "
                    + (1 << WRITE_BIT_DEPTH) + " que puede indexar un PNG de " + WRITE_BIT_DEPTH + " bits");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE, 0, SIGNATURE.length);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeU32(ihdr, width);
        writeU32(ihdr, height);
        ihdr.write(WRITE_BIT_DEPTH);
        ihdr.write(COLOR_TYPE_INDEXED);
        ihdr.write(0); // deflate
        ihdr.write(0); // filtrado adaptativo
        ihdr.write(0); // sin entrelazar
        writeChunk(out, "IHDR", ihdr.toByteArray());

        ByteArrayOutputStream plte = new ByteArrayOutputStream();
        for (int color : palette) {
            plte.write((color >> 16) & 0xFF);
            plte.write((color >> 8) & 0xFF);
            plte.write(color & 0xFF);
        }
        writeChunk(out, "PLTE", plte.toByteArray());

        writeChunk(out, "IDAT", deflate(scanlines(indices, width, height)));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** Filas de medios bytes, cada una detrás de su byte de filtro (0 = sin filtrar). */
    private static byte[] scanlines(byte[] indices, int width, int height) {
        int stride = (width * WRITE_BIT_DEPTH + 7) / 8;
        byte[] raw = new byte[(stride + 1) * height];
        int at = 0;
        for (int y = 0; y < height; y++) {
            raw[at++] = 0;
            for (int x = 0; x < width; x++) {
                int index = indices[y * width + x] & 0xF;
                int shift = (x & 1) == 0 ? 4 : 0;
                raw[at + x / 2] |= (byte) (index << shift);
            }
            at += stride;
        }
        return raw;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] buffer = new byte[16384];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeU32(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes, 0, typeBytes.length);
        out.write(data, 0, data.length);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeU32(out, (int) crc.getValue());
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readU32(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

}
