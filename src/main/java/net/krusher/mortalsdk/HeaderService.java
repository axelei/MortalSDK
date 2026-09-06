package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * La cabecera de Mega Drive.
 * <p>
 * Lleva dos nombres del juego, el nacional en {@code 0x120} y el internacional en {@code 0x150}, de 48 bytes
 * cada uno y rellenos de espacios. Son los que enseñan los emuladores y las listas de ROMs, así que conviene
 * que digan lo que toca aunque el fichero de textos traiga otra cosa de antes.
 */
public class HeaderService {

    /** El nombre nacional. */
    static final int DOMESTIC_NAME = 0x120;

    /** El internacional, el que se usa fuera de Japón. */
    static final int OVERSEAS_NAME = 0x150;

    /** Lo que ocupa cada uno. */
    static final int NAME_SIZE = 48;

    private HeaderService() {
    }

    /**
     * Pone el nombre de la propiedad {@code romName} en los dos campos, tal cual y sin preguntar. Va el
     * último de la inyección a propósito: así manda sobre lo que hayan escrito ahí los textos, que pueden
     * venir de un fichero de antes con los campos de la cabecera partidos de otra manera.
     */
    public static void writeName(byte[] fileData) {
        String name = App.config.romName();
        if (StringUtils.isBlank(name)) {
            return;
        }
        byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length > NAME_SIZE) {
            Log.pnl("Alerta: el nombre \"{0}\" tiene {1} caracteres y en la cabecera sólo caben {2}. Se cortará.",
                    name, bytes.length, NAME_SIZE);
        }
        byte[] field = new byte[NAME_SIZE];
        Arrays.fill(field, Texticle.ASCII_SPACE);
        System.arraycopy(bytes, 0, field, 0, Math.min(bytes.length, NAME_SIZE));
        for (int at : new int[]{DOMESTIC_NAME, OVERSEAS_NAME}) {
            if (at + NAME_SIZE > fileData.length) {
                throw new IllegalStateException("La ROM no llega ni a la cabecera.");
            }
            System.arraycopy(field, 0, fileData, at, NAME_SIZE);
        }
        Log.pnl("Nombre de la ROM: \"{0}\"", new String(field, StandardCharsets.ISO_8859_1).trim());
    }

}
