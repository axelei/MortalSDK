package net.krusher.mortalsdk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Escribe los cambios como un parche IPS, que es lo que se reparte: la ROM completa es casi toda datos del
 * juego original, y el parche sólo lleva los bytes que ha puesto este programa.
 * <p>
 * El formato (zerosoft.zophar.net/ips.php) va todo en big endian:
 * <pre>
 *   "PATCH"      cinco bytes de cabecera, sin cero al final
 *   registro...  posición (3 bytes), tamaño (2 bytes) y esos bytes de datos.
 *                Un tamaño de cero marca un registro RLE, y entonces vienen la
 *                longitud de la tirada (2 bytes) y el byte que se repite (1)
 *   "EOF"        tres bytes que cierran el fichero
 * </pre>
 * Los límites del formato se comprueban en vez de darlos por hechos: la posición son 24 bits (16 MB) y el
 * tamaño de un registro 16 bits (64 KB), así que una tirada de cambios más larga que 0xFFFF hay que
 * repartirla entre varios registros.
 * <p>
 * La trampa que no cuenta la especificación: un registro cuya posición sea 0x454F46 se escribe con los bytes
 * "EOF", y ahí para cualquier programa que aplique el parche. Ese registro se empieza un byte antes, con lo
 * que se reescribe un byte que no había cambiado con su propio valor, que no se nota.
 */
public class IpsService {

    static final byte[] MAGIC = {'P', 'A', 'T', 'C', 'H'};
    static final byte[] EOF_MARKER = {'E', 'O', 'F'};

    /** La posición que se escribiría igual que la marca de final. */
    static final int EOF_OFFSET = 0x454F46;

    static final int MAX_OFFSET = 0xFFFFFF;
    static final int MAX_RECORD = 0xFFFF;

    /**
     * Cuántos bytes iguales merece la pena tragarse para no cortar un registro. Un registro cuesta cinco
     * bytes de cabecera, así que salvar un hueco más corto que eso siempre sale más barato que empezar otro.
     */
    static final int MAX_GAP = 5;

    /** A partir de aquí una tirada sale más barata en RLE (ocho bytes) que a pelo (cinco más la longitud). */
    static final int MIN_RLE_RUN = 9;

    private IpsService() {
    }

    /** Escribe el parche junto a la ROM, con la extensión .ips. */
    public static void write(byte[] originalData, byte[] fileData, String file) throws IOException {
        byte[] ips = build(originalData, fileData);
        File outputFile = new File(file + ".ips");
        Files.write(outputFile.toPath(), ips);
        Log.pnl("Parche escrito en: {0} ({1} bytes, {2} cambiados de {3})",
                outputFile.getAbsolutePath(), ips.length, changedBytes(originalData, fileData), fileData.length);
        if (isEmpty(ips)) {
            Log.pnl("Alerta: el parche no lleva ningún registro, o sea que la ROM ha quedado igual que la "
                    + "original. Además hay programas que rechazan un parche vacío por incompleto.");
        }
    }

    /** El parche entero, con su cabecera y su marca de final. */
    static byte[] build(byte[] base, byte[] patched) {
        if (patched.length < base.length) {
            throw new IllegalStateException("La ROM parcheada es más pequeña que la original ("
                    + patched.length + " < " + base.length + "); un IPS puede añadir bytes, pero no quitarlos.");
        }
        if (patched.length > MAX_OFFSET + 1) {
            throw new IllegalStateException("La ROM parcheada ocupa " + patched.length
                    + " bytes; las posiciones de un IPS son de 24 bits y no llegan más allá de 0x"
                    + Integer.toHexString(MAX_OFFSET) + ".");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC, 0, MAGIC.length);

        int at = 0;
        while (at < patched.length) {
            if (!differs(base, patched, at)) {
                at++;
                continue;
            }
            // se alarga el bloque por los bytes cambiados, tragándose los huecos de bytes iguales que son
            // demasiado cortos para que salga a cuenta abrir otro registro
            int end = at;
            int gap = 0;
            for (int i = at; i < patched.length; i++) {
                if (differs(base, patched, i)) {
                    end = i + 1;
                    gap = 0;
                } else if (++gap > MAX_GAP) {
                    break;
                }
            }
            at = writeBlock(out, patched, at, end);
        }

        out.write(EOF_MARKER, 0, EOF_MARKER.length);
        return out.toByteArray();
    }

    /**
     * Suelta el trozo [start, end) en uno o más registros y devuelve por dónde se ha quedado. Las tiradas
     * largas de un mismo byte van en RLE, y lo demás a pelo, partido para que quepa en el tamaño de 16 bits.
     */
    private static int writeBlock(ByteArrayOutputStream out, byte[] patched, int start, int end) {
        int at = start;
        while (at < end) {
            int offset = at;
            // un registro no puede empezar en la posición que se escribe como "EOF": se retrocede un byte,
            // con lo que se reescribe uno que no había cambiado con su propio valor
            if (offset == EOF_OFFSET) {
                offset--;
            }

            int run = runLengthAt(patched, offset, end);
            if (run >= MIN_RLE_RUN) {
                writeRle(out, offset, run, patched[offset]);
                at = offset + run;
                continue;
            }

            // datos a pelo, hasta la siguiente tirada larga (que sale más barata en RLE) y sin pasarse del
            // tamaño máximo. Tiene que llegar al menos un byte más allá de "at", o un registro que ha
            // retrocedido por lo del EOF dejaría el recorrido donde estaba
            int limit = Math.min(offset + MAX_RECORD, end);
            int stop = Math.min(Math.max(offset, at) + 1, limit);
            while (stop < limit && runLengthAt(patched, stop, end) < MIN_RLE_RUN) {
                stop++;
            }
            writeData(out, patched, offset, stop - offset);
            at = stop;
        }
        return at;
    }

    /** Cuántas veces se repite el byte que hay en esa posición, sin pasarse de "end". */
    private static int runLengthAt(byte[] data, int offset, int end) {
        int run = 1;
        while (offset + run < end && data[offset + run] == data[offset] && run < MAX_RECORD) {
            run++;
        }
        return run;
    }

    private static void writeData(ByteArrayOutputStream out, byte[] data, int offset, int length) {
        writeOffset(out, offset);
        writeU16(out, length);
        out.write(data, offset, length);
    }

    private static void writeRle(ByteArrayOutputStream out, int offset, int length, byte value) {
        writeOffset(out, offset);
        writeU16(out, 0);      // así se marca que el registro es RLE
        writeU16(out, length);
        out.write(value);
    }

    private static void writeOffset(ByteArrayOutputStream out, int offset) {
        if (offset == EOF_OFFSET) {
            throw new IllegalStateException("Un registro en 0x454f46 se escribiría igual que la marca de final.");
        }
        out.write((offset >> 16) & 0xFF);
        out.write((offset >> 8) & 0xFF);
        out.write(offset & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** Un byte más allá del final de la ROM original cuenta como cambiado: el parche lo añade. */
    private static boolean differs(byte[] base, byte[] patched, int at) {
        return at >= base.length || base[at] != patched[at];
    }

    /** Un parche que no cambia nada: cabecera y marca de final, sin registros. */
    static boolean isEmpty(byte[] ips) {
        return ips.length == MAGIC.length + EOF_MARKER.length;
    }

    static int changedBytes(byte[] base, byte[] patched) {
        int changed = 0;
        for (int i = 0; i < patched.length; i++) {
            if (differs(base, patched, i)) {
                changed++;
            }
        }
        return changed;
    }

}
