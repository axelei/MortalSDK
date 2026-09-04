package net.krusher.mortalsdk;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class TexticleService {

    private static final String DEFAULT_TEXT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!?.,0123456789:'\" ";
    private static final int MAX_TBL_KEY_LENGTH = 3;

    public static List<Texticle> findTexticles(byte[] fileData) {
        List<Texticle> texts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean inText = false;
        int length = 0;
        for (int i = 0; i < fileData.length; i++) {
            if (!inRange(i)) {
                continue;
            }
            int extractedLength = isChar(i, fileData);
            if (extractedLength > 0) {
                inText = true;
                if (Objects.isNull(App.tbl)) {
                    buffer.append((char) fileData[i]);
                } else {
                    byte[] datum = new byte[extractedLength];
                    System.arraycopy(fileData, i, datum, 0, extractedLength);
                    String hexString = TblService.byteArrayToHexString(datum);
                    if (App.tbl.containsKey(hexString)) {
                        buffer.append(App.tbl.get(hexString));
                    } else {
                        buffer.append((char) fileData[i]);
                    }
                }
                i += extractedLength - 1;
                length += extractedLength;
            } else if (inText) {
                if (length > App.config.minChars() && wanted(i - length)) {
                    texts.add(new Texticle(i - length, length, buffer.toString(),
                            findPointer(i - length, fileData)));
                }
                length = 0;
                buffer = new StringBuilder();
                inText = false;
            }
        }
        return texts;
    }

    /**
     * Busca desde dónde se apunta a una dirección.
     * <p>
     * Primero se buscan los {@code lea (d16,PC)} del 68000, que es como el código llega a los textos que
     * tiene cerca, y sólo si no hay ninguno se recurre al puntero absoluto de tres bytes. El orden importa:
     * un valor de tres bytes puede aparecer por casualidad en cualquier sitio, mientras que un lea que
     * apunta justo al texto no es casualidad.
     */
    public static Texticle.Pointer findPointer(Integer value, byte[] fileData) {
        if (Objects.isNull(value)) {
            return null;
        }
        Integer lea = findLeaAddress(value, fileData);
        if (Objects.nonNull(lea)) {
            return new Texticle.Pointer(lea, true);
        }
        Integer absolute = findPointerAddress(value, fileData);
        return Objects.isNull(absolute) ? null : new Texticle.Pointer(absolute, false);
    }

    /**
     * Busca un {@code lea (d16,PC),aN} que apunte a {@code value}. Son cuatro bytes: la instrucción y una
     * distancia con signo de 16 bits contada desde la propia distancia.
     */
    public static Integer findLeaAddress(int value, byte[] fileData) {
        for (int at = 0; at + 4 <= fileData.length; at += 2) {
            int opcode = ((fileData[at] & 0xFF) << 8) | (fileData[at + 1] & 0xFF);
            if (!isLeaPcRelative(opcode)) {
                continue;
            }
            int displacement = (short) (((fileData[at + 2] & 0xFF) << 8) | (fileData[at + 3] & 0xFF));
            if (at + 2 + displacement == value) {
                return at;
            }
        }
        return null;
    }

    /** lea (d16,PC),aN: 0x41FA para a0, 0x43FA para a1, y así hasta a6. */
    private static boolean isLeaPcRelative(int opcode) {
        return (opcode & 0xF1FF) == 0x41FA && ((opcode >> 9) & 7) <= 6;
    }

    public static Integer findPointerAddress(Integer value, byte[] fileData) {
        if (value == null) {
            return null;
        }
        // se convierte el valor a un array de tres bytes
        byte[] valueBytes = new byte[3];
        valueBytes[0] = (byte) ((value >> 16) & 0xFF);
        valueBytes[1] = (byte) ((value >> 8) & 0xFF);
        valueBytes[2] = (byte) (value & 0xFF);
        // se busca el valor en los datos del fichero
        for (int i = 0; i < fileData.length - 2; i++) {
            if (fileData[i] == valueBytes[0] && fileData[i + 1] == valueBytes[1] && fileData[i + 2] == valueBytes[2]) {
                return i;
            }
        }
        return null;
    }

    /**
     * Escribe en el puntero la nueva dirección del texto. Un lea guarda la distancia, no la dirección, y esa
     * distancia es de 16 bits con signo: si el destino queda a más de 32 KB no cabe y se devuelve false.
     */
    public static boolean writePointer(Texticle.Pointer pointer, int target, byte[] fileData) {
        if (!pointer.lea()) {
            writeThreeBytes(fileData, pointer.address(), target);
            return true;
        }
        int displacement = target - (pointer.address() + 2);
        if (displacement < Short.MIN_VALUE || displacement > Short.MAX_VALUE) {
            return false;
        }
        fileData[pointer.address() + 2] = (byte) (displacement >> 8);
        fileData[pointer.address() + 3] = (byte) displacement;
        return true;
    }

    /** Si la configuración trae una lista de textos, sólo se extraen ésos. */
    private static boolean wanted(int address) {
        return App.config.texts().isEmpty() || App.config.texts().contains(address);
    }

    public static boolean inRange(int i) {
        if (App.config.textRanges().isEmpty()) {
            return true;
        }
        for (Range range : App.config.textRanges()) {
            if (range.isInRange(i)) {
                return true;
            }
        }
        return false;
    }

    public static void dumpTexticles(List<Texticle> texticles, String file) throws IOException {
        File outputFile = new File(file + ".txt");
        FileWriter fileWriter = new FileWriter(outputFile);
        PrintWriter printWriter = new PrintWriter(fileWriter);

        for (Texticle texticle : texticles) {
            printWriter.println(texticle.format());
        }

        printWriter.close();
    }

    private static int isChar(int position, byte[] array) {
        if (Objects.isNull(App.tbl)) {
            byte fileDatum = array[position];
            for (byte theChar : DEFAULT_TEXT_CHARS.getBytes(StandardCharsets.ISO_8859_1)) {
                if (fileDatum == theChar) {
                    return 1;
                }
            }
        } else {
            for (int i = MAX_TBL_KEY_LENGTH; i > 0; i--) {
                if (position + i >= array.length) {
                    return 0;
                }
                byte[] datum = new byte[i];
                System.arraycopy(array, position, datum, 0, i);

                if (App.tbl.containsKey(TblService.byteArrayToHexString(datum))) {
                    return datum.length;
                }
            }
        }
        return 0;
    }

    public static List<Texticle> readTexticles(String file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file + ".txt"));
        List<Texticle> texticles = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("#");
            int address = Texticle.parseAddress(parts[0]);
            int size = Integer.parseInt(parts[1].trim());
            String text = parts[2];
            Texticle.Pointer pointer = parts.length > 3 ? Texticle.Pointer.parse(parts[3]) : null;
            texticles.add(new Texticle(address, size, text, pointer));
        }
        return texticles;
    }

    public static void insertTexticles(String file, byte[] fileData) throws IOException {
        List<Texticle> texticles = TexticleService.readTexticles(file);
        for (Texticle texticle : texticles) {
            byte[] textData;
            if (Objects.isNull(App.tbl)) {
                textData = texticle.toAsciiBytes();
            } else {
                List<Byte> textDataList = new ArrayList<>();
                for (int i = 0; i < texticle.text().length(); i++) {

                    for (int a = MAX_TBL_KEY_LENGTH; a > 0; a--) {
                        StringBuilder chars = new StringBuilder();
                        for (int b = 0; b < a; b++) {
                            if (i + b >= texticle.text().length()) {
                                chars.append((char) 0x00);
                            } else {
                                chars.append(texticle.text().charAt(i + b));
                            }
                        }
                        if (App.tbl.containsValue(chars.toString())) {
                            String hexValue = App.tbl.inverse().get(chars.toString());
                            byte[] result = TblService.hexStringToByteArray(hexValue);
                            for (byte b : result) {
                                textDataList.add(b);
                            }
                            i += a - 1;
                            break;
                        }
                    }
                }
                textData = new byte[textDataList.size()];
                for (int a = 0; a < textDataList.size(); a++) {
                    textData[a] = textDataList.get(a);
                }
            }
            writeTexticle(texticle.address(), textData, fileData, texticle.size(), texticle.pointer());
        }
    }

    private static void writeTexticle(int address, byte[] textData, byte[] fileData, int room,
                                      Texticle.Pointer pointer) {
        if (textData.length == room) {
            System.arraycopy(textData, 0, fileData, address, textData.length);
            return;
        }
        if (textData.length < room) {
            System.arraycopy(textData, 0, fileData, address, textData.length);
            String oldText = new String(textData, StandardCharsets.ISO_8859_1);
            Log.pnl("Alerta: El texto leído \"{0}\" tiene {1} caracteres, pero el texto original tenía {2} caracteres. Se rellenará con ceros.", oldText, textData.length, room);
            byte[] padding = new byte[room - textData.length];
            System.arraycopy(padding, 0, fileData, address + textData.length, padding.length);
            return;
        }

        String oldText = new String(textData, StandardCharsets.ISO_8859_1);
        Log.p("Alerta: El texto leído \"{0}\" tiene {1} caracteres, pero el texto original tenía {2} caracteres. ", oldText, textData.length, room);
        if (Objects.isNull(pointer)) {
            writeCutText(textData, fileData, address);
            return;
        }
        Integer newAddress = getNewAddress(textData.length);
        if (Objects.isNull(newAddress)) {
            writeCutText(textData, fileData, address);
            return;
        }
        if (!writePointer(pointer, newAddress, fileData)) {
            // un lea sólo alcanza 32 KB: si el hueco libre queda más lejos, no se puede mover
            Log.pnl("El puntero de {0} es un lea y {1} le queda demasiado lejos.",
                    Integer.toHexString(pointer.address()), Integer.toHexString(newAddress));
            writeCutText(textData, fileData, address);
            return;
        }

        byte[] padding = new byte[room];
        Arrays.fill(padding, Texticle.ASCII_SPACE);
        System.arraycopy(padding, 0, fileData, address, room);

        Log.pnl("Moviendo el texto a la dirección {0}", Integer.toHexString(newAddress));
        System.arraycopy(textData, 0, fileData, newAddress, textData.length);
    }

    private static void writeCutText(byte[] textData, byte[] fileData, int address) {
        Log.pnl("Se cortará el texto.");
        System.arraycopy(textData, 0, fileData, address, textData.length);
    }

    public static Integer getNewAddress(int size) {
        return getNewAddress(size, 0);
    }

    /**
     * Reserva espacio libre. Si bankSize no es cero, el bloque no cruzará ninguna frontera de ese tamaño,
     * porque el reproductor de samples direcciona la ROM por ventanas de banco.
     */
    public static Integer getNewAddress(int size, int bankSize) {
        Optional<Range> range = App.config.spaceRanges().stream().findFirst();
        // No quedan rangos
        if (range.isEmpty()) {
            return null;
        }
        // El bloque cruzaría un banco, se salta al principio del siguiente
        if (bankSize > 0 && range.get().getFrom() % bankSize + size > bankSize) {
            range.get().setFrom((range.get().getFrom() / bankSize + 1) * bankSize);
        }
        // No queda sitio en este rango, se descarta y se prueba con el siguiente
        if (range.get().getFrom() + size - 1 > range.get().getTo()) {
            App.config.spaceRanges().remove(range.get());
            return getNewAddress(size, bankSize);
        }

        int newAddress = range.get().getFrom();
        range.get().setFrom(range.get().getFrom() + size + 1);

        return newAddress;
    }

    public static void writeThreeBytes(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 16) & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) (value & 0xFF);
    }

}
