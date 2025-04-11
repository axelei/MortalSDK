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
                if (length > App.config.minChars()) {
                    Integer pointerAddress = findValueAddress(i - length, fileData);
                    texts.add(new Texticle(i - length, length, buffer.toString(), pointerAddress));
                }
                length = 0;
                buffer = new StringBuilder();
                inText = false;
            }
        }
        return texts;
    }

    public static Integer findValueAddress(Integer value, byte[] fileData) {
        if (value == null) {
            return null;
        }
        // convert value to 3 byte array
        byte[] valueBytes = new byte[3];
        valueBytes[0] = (byte) ((value >> 16) & 0xFF);
        valueBytes[1] = (byte) ((value >> 8) & 0xFF);
        valueBytes[2] = (byte) (value & 0xFF);
        // search for the value in the file data
        for (int i = 0; i < fileData.length - 2; i++) {
            if (fileData[i] == valueBytes[0] && fileData[i + 1] == valueBytes[1] && fileData[i + 2] == valueBytes[2]) {
                return i;
            }
        }
        return null;
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
            String[] parts = line.split("#");
            int address = Integer.parseInt(parts[0]);
            int size = Integer.parseInt(parts[1]);
            String text = parts[2];
            Integer pointerAddress = null;
            if (parts.length > 3) {
                pointerAddress = Integer.parseInt(parts[3]);
            }
            texticles.add(new Texticle(address, size, text, pointerAddress));
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
            writeTexticle(texticle.address(), textData, fileData, texticle.size(), texticle.pointerAddress());
        }
    }

    private static void writeTexticle(int address, byte[] textData, byte[] fileData, int room, Integer pointerAddress) {
        if (textData.length == room) {
            System.arraycopy(textData, 0, fileData, address, textData.length);
        }
        if (textData.length < room) {
            System.arraycopy(textData, 0, fileData, address, textData.length);
            String oldText = new String(textData, StandardCharsets.ISO_8859_1);
            Log.pnl("Alerta: El texto leído \"{0}\" tiene {1} caracteres, pero el texto original tenía {2} caracteres. Se rellenará con ceros.", oldText, textData.length, room);
            byte[] padding = new byte[room - textData.length];
            System.arraycopy(padding, 0, fileData, address + textData.length, padding.length);
        }
        if (textData.length > room) {
            String oldText = new String(textData, StandardCharsets.ISO_8859_1);
            Log.p("Alerta: El texto leído \"{0}\" tiene {1} caracteres, pero el texto original tenía {2} caracteres. ", oldText, textData.length, room);
            if (Objects.isNull(pointerAddress)) {
                writeCutText(textData, fileData, address);
                return;
            }
            Integer newAddress = getNewAddress(textData.length);
            if (Objects.isNull(newAddress)) {
                writeCutText(textData, fileData, address);
                return;
            }

            byte[] padding = new byte[room];
            Arrays.fill(padding, Texticle.ASCII_SPACE);
            System.arraycopy(padding, 0, fileData, address, room);

            Log.pnl("Moviendo el texto a la dirección {0} ({1})", Integer.toHexString(newAddress), newAddress);

            System.arraycopy(textData, 0, fileData, newAddress, textData.length);
            writeThreeBytes(fileData, pointerAddress, newAddress);
        }
    }
    private static void writeCutText(byte[] textData, byte[] fileData, int address) {
        Log.pnl("Se cortará el texto.");
        System.arraycopy(textData, 0, fileData, address, textData.length);
    }

    private static Integer getNewAddress(int size) {
        Optional<Range> range = App.config.spaceRanges().stream().findFirst();
        // No more ranges
        if (range.isEmpty()) {
            return null;
        }
        // No more space, remove and try next
        if (range.get().getFrom() > range.get().getTo()) {
            App.config.spaceRanges().remove(range.get());
            return getNewAddress(size);
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
