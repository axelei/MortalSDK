package net.krusher.mortalsdk;

import java.io.IOException;

/**
 * Error al comprimir o descomprimir un bloque RNC.
 */
public class RncException extends IOException {

    public RncException(String message) {
        super(message);
    }

}
