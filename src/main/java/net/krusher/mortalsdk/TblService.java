package net.krusher.mortalsdk;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TblService {

    private TblService() {}

    public static BiMap<String, String> readTbl(String fileName) throws IOException {
        Path file = Paths.get(fileName + ".tbl");
        if (!file.toFile().exists()) {
            return null;
        }
        List<String> lines = Files.readAllLines(file);
        BiMap<String, String> tbl = HashBiMap.create();
        for (String line : lines) {
            if (StringUtils.isBlank(line) || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("=");
            if (parts.length != 2) {
                continue;
            }
            tbl.put(parts[0].toUpperCase(), parts[1]);
        }
        return tbl;
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public static String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b).toUpperCase());
        }
        return sb.toString();
    }
}
