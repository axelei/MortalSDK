package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @param palettes qué paleta de la ROM se usa para dibujar cada bloque de gráficos, por dirección
 */
public record Config(int minChars,
                     Set<Range> textRanges,
                     Set<Range> bins,
                     Set<Range> spaceRanges,
                     Map<Integer, Integer> palettes) {

    private static final int DEFAULT_MIN_CHARS = 5;

    public Config() {
        this(DEFAULT_MIN_CHARS, Set.of(), Set.of(), Set.of(), Map.of());
    }

    public static Config getInstance(String fileName) throws IOException {
        Properties properties = new Properties();
        File configFile = new File(fileName);
        InputStream stream = new FileInputStream(configFile);
        properties.load(stream);

        int minChars = Integer.parseInt(properties.getProperty("minChars", String.valueOf(DEFAULT_MIN_CHARS)));
        String textRangesStr = properties.getProperty("textRanges");
        Set<Range> textRanges = parseRanges(textRangesStr);
        String binsStr = properties.getProperty("bins");
        Set<Range> bins = parseRanges(binsStr);
        String spaceRangesStr = properties.getProperty("spaceRanges");
        Set<Range> spaceRanges = parseRanges(spaceRangesStr);
        Map<Integer, Integer> palettes = parsePalettes(properties.getProperty("palettes"));
        return new Config(minChars, textRanges, bins, spaceRanges, palettes);
    }

    /**
     * Lee la propiedad "palettes", con el formato {@code bloque,paleta#bloque,paleta}. Las direcciones van en
     * hexadecimal, igual que en los nombres de los ficheros extraídos, y admiten el prefijo 0x.
     */
    private static Map<Integer, Integer> parsePalettes(String string) {
        if (StringUtils.isBlank(string)) {
            return Map.of();
        }
        Map<Integer, Integer> result = new HashMap<>();
        for (String pair : string.split("#")) {
            int comma = pair.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("Paleta mal escrita en la configuración: " + pair);
            }
            result.put(parseHex(pair.substring(0, comma)), parseHex(pair.substring(comma + 1)));
        }
        return result;
    }

    private static int parseHex(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            trimmed = trimmed.substring(2);
        }
        return Integer.parseInt(trimmed, 16);
    }

    private static Set<Range> parseRanges(String string) {
        if (StringUtils.isBlank(string)) {
            return Set.of();
        }
        Set<Range> result = new HashSet<>();
        String[] ranges = string.split("#");
        for (String range : ranges) {
            result.add(Range.of(
                    Integer.parseInt(range.substring(0, range.indexOf(','))),
                    Integer.parseInt(range.substring(range.indexOf(',') + 1))
            ));
        }
        return result;
    }

}
