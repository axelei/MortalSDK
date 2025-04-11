package net.krusher.mortalsdk;

public class Checksum {

    private static final int CHECKSUM_OFFSET = 398; // 256 + 142

    private Checksum () {}

    public static void fixChecksum(byte[] romBytes) {

        int previousChecksum = readWord(romBytes, CHECKSUM_OFFSET);
        Log.pf("Checksum existente: 0x%04x%n", previousChecksum);

        int checksum = calculateChecksum(romBytes);
        Log.pf("Checksum válido: 0x%04x%n", checksum);

        if (previousChecksum != checksum) {
            Log.pnl("El checksum ha cambiado, arreglando...");
            writeWord(romBytes, CHECKSUM_OFFSET, checksum);
        } else {
            Log.pnl("¡El checksum no ha cambiado!");
        }
    }

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void writeWord(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static int calculateChecksum(byte[] rom) {
        int checksum = 0;
        for (int i = 512; i + 1 < rom.length; i += 2) {
            int word = ((rom[i] & 0xFF) << 8) | (rom[i + 1] & 0xFF);
            checksum = (checksum + word) & 0xFFFF;
        }
        return checksum;
    }


}
