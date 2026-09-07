package net.krusher.mortalsdk;

import java.util.Map;

/**
 * Parches sueltos sobre el código de la ROM.
 */
public class CodeService {

    /** El rts del 68000. */
    static final int RTS = 0x4E75;

    private CodeService() {
    }

    /**
     * Pone un {@code rts} al principio de cada rutina de la propiedad {@code skipRoutines}, con lo que deja
     * de hacer nada y quien la llame sigue como si tal cosa.
     * <p>
     * Sirve para quitar de en medio pantallas que sobran. En esta ROM, el logo de Sega lo dibuja la rutina de
     * {@code 0x19A70}, a la que sólo se llama desde el arranque en {@code 0x1273E}; se reserva ella misma la
     * pila que usa y la devuelve antes de su rts, así que saltársela entera no deja nada a medias.
     */
    public static void skipRoutines(byte[] fileData) {
        for (int address : App.config.skipRoutines()) {
            if (address % 2 != 0) {
                throw new IllegalArgumentException("La rutina " + Integer.toHexString(address)
                        + " está en una dirección impar, y las instrucciones del 68000 van en pares.");
            }
            if (address < 0 || address + 2 > fileData.length) {
                throw new IllegalArgumentException("La rutina " + Integer.toHexString(address)
                        + " cae fuera de la ROM.");
            }
            TexticleService.writeWord(fileData, address, RTS);
            Log.pnl("Rutina {0} anulada con un rts.", Integer.toHexString(address));
        }
    }

    /**
     * Escribe tal cual los bytes de la propiedad {@code codePatches}, para los cambios finos que no tienen
     * una propiedad propia. Se avisa de lo que había antes: si no es lo que se esperaba, es que la ROM base
     * ha cambiado y el parche ya no vale.
     */
    public static void applyPatches(byte[] fileData) {
        for (Map.Entry<Integer, byte[]> patch : App.config.codePatches().entrySet()) {
            int address = patch.getKey();
            byte[] data = patch.getValue();
            if (address < 0 || address + data.length > fileData.length) {
                throw new IllegalArgumentException("El parche de " + Integer.toHexString(address)
                        + " se sale de la ROM.");
            }
            StringBuilder before = new StringBuilder();
            for (int i = 0; i < data.length; i++) {
                before.append(String.format("%02x", fileData[address + i]));
            }
            System.arraycopy(data, 0, fileData, address, data.length);
            StringBuilder after = new StringBuilder();
            for (byte b : data) {
                after.append(String.format("%02x", b));
            }
            Log.pnl("Parche en {0}: {1} -> {2}.", Integer.toHexString(address), before, after);
        }
    }

}
