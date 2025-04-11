package net.krusher.mortalsdk;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.temporal.ValueRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        for (ValueRange range : App.config.textRanges()) {
            if (range.isValidIntValue(i)) {
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

    public static List<Texticle> findTexticles(String file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file + ".txt"));
        List<Texticle> texticles = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("#");
            int address = Integer.parseInt(parts[0]);
            int size = Integer.parseInt(parts[1]);
            String text = parts[2];
            if (text.length() > size) {
                text = text.substring(0, size);
            }
            Integer pointerAddress = null;
            if (parts.length > 3) {
                pointerAddress = Integer.parseInt(parts[3]);
            }
            texticles.add(new Texticle(address, size, text, pointerAddress));
        }
        return texticles;
    }

    public static void insertTexticles(String file, byte[] fileData) throws IOException {
        List<Texticle> texticles = TexticleService.findTexticles(file);
        for (Texticle texticle : texticles) {
            byte[] textData;
            if (Objects.isNull(App.tbl)) {
                textData = texticle.toAsciiBytes();
            } else {
                List<Byte> textDataList = new ArrayList<>();
                for (int i = 0; i < texticle.text().length(); i++) {

                    for (int a = MAX_TBL_KEY_LENGTH; a > 0; a--) {
                        if (texticle.size() < i + a) {
                            continue;
                        }
                        StringBuilder chars = new StringBuilder();
                        for (int b = 0; b < a; b++) {
                            chars.append(texticle.text().charAt(i + b));
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
            System.arraycopy(textData, 0, fileData, texticle.address(), texticle.size());
        }
    }

}
