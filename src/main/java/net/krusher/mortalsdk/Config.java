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
 * @param scenes   qué bloque de gráficos usa cada mapa de tiles, por dirección
 */
public record Config(int minChars,
                     Set<Range> textRanges,
                     Set<Range> bins,
                     Set<Range> spaceRanges,
                     Map<Integer, Integer> palettes,
                     Map<Integer, Integer> scenes) {

    private static final int DEFAULT_MIN_CHARS = 5;

    /** Valor de la parte derecha de un par cuando se escribe "-": ahí no hay nada. */
    public static final int NONE = -1;

    public Config() {
        this(DEFAULT_MIN_CHARS, Set.of(), Set.of(), Set.of(), Map.of(), Map.of());
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
        Map<Integer, Integer> palettes = parsePairs(properties.getProperty("palettes"), "palettes");
        Map<Integer, Integer> scenes = parsePairs(properties.getProperty("scenes"), "scenes");
        return new Config(minChars, textRanges, bins, spaceRanges, palettes, scenes);
    }

    /**
     * Lee una propiedad con el formato {@code a,b#a,b}. Las direcciones van en hexadecimal, igual que en los
     * nombres de los ficheros extraídos, y admiten el prefijo 0x.
     */
    private static Map<Integer, Integer> parsePairs(String string, String property) {
        if (StringUtils.isBlank(string)) {
            return Map.of();
        }
        Map<Integer, Integer> result = new HashMap<>();
        for (String pair : string.split("#")) {
            int comma = pair.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("La propiedad " + property + " tiene un par mal escrito: " + pair);
            }
            String value = pair.substring(comma + 1).trim();
            // un "-" a la derecha sirve para decir que ahí no hay nada, y así descartar un emparejado
            result.put(parseHex(pair.substring(0, comma)), value.equals("-") ? NONE : parseHex(value));
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
