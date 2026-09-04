package net.krusher.mortalsdk;

/**
 * Una entrada de la tabla de samples PCM de la ROM. Ocupa ocho bytes:
 * <pre>
 * +0  1 byte   identificador (coincide con el índice)
 * +1  3 bytes  dirección absoluta del PCM en la ROM
 * +4  2 bytes  longitud en bytes
 * +6  2 bytes  velocidad de reproducción (incremento del acumulador del reproductor Z80)
 * </pre>
 * El PCM es de 8 bits con signo.
 *
 * @param entryAddress dirección de la entrada dentro de la ROM, para poder reescribirla
 */
public record Sample(int id, int entryAddress, int offset, int length, int rate) {

    public boolean isEmpty() {
        return length == 0;
    }

    public boolean fitsInRom(int romSize) {
        return length > 0 && offset >= 0 && offset + length <= romSize;
    }

    public String fileName() {
        return String.format("sample_%02x_%06x.wav", id, offset);
    }

}
