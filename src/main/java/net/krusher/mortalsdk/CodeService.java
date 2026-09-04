package net.krusher.mortalsdk;

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

}
