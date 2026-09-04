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
 * @param texts    si no está vacío, las únicas direcciones de texto que se extraen
 * @param intro    fichero con la ROM de la intro que se pone delante del juego, si se quiere
 * @param introSpace zonas de la ROM que puede usar la intro para repartir sus trozos
 * @param codeSpace  huecos de la ROM donde se pueden escribir trampolines de ocho bytes para desviar un
 *                   {@code lea (d16,PC)} cuyo texto se ha ido a más de 32 KB. Tienen que caer cerca del
 *                   propio lea, así que conviene dar varios repartidos por la zona de código.
 */
public record Config(int minChars,
                     Set<Range> textRanges,
                     Set<Range> bins,
                     Set<Range> spaceRanges,
                     Map<Integer, Integer> palettes,
                     Map<Integer, Integer> scenes,
                     Set<Integer> texts,
                     String intro,
                     Set<Range> introSpace,
                     Set<Range> codeSpace) {

    private static final int DEFAULT_MIN_CHARS = 5;

    /** Valor de la parte derecha de un par cuando se escribe "-": ahí no hay nada. */
    public static final int NONE = -1;

    public Config() {
        this(DEFAULT_MIN_CHARS, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of(), null, Set.of(),
                Set.of());
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
        Set<Integer> texts = parseAddresses(properties.getProperty("texts"));
        String intro = properties.getProperty("intro");
        Set<Range> introSpace = parseRanges(properties.getProperty("introSpace"));
        Set<Range> codeSpace = parseRanges(properties.getProperty("codeSpace"));
        return new Config(minChars, textRanges, bins, spaceRanges, palettes, scenes, texts, intro, introSpace,
                codeSpace);
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

    /** Lista de direcciones en hexadecimal separadas por "#". */
    private static Set<Integer> parseAddresses(String string) {
        if (StringUtils.isBlank(string)) {
            return Set.of();
        }
        Set<Integer> result = new HashSet<>();
        for (String address : string.split("#")) {
            if (!address.isBlank()) {
                result.add(parseHex(address));
            }
        }
        return result;
    }

    /** Un número de la configuración: decimal, o hexadecimal si lleva delante 0x. */
    private static int parseNumber(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("0x") || trimmed.startsWith("0X")
                ? Integer.parseInt(trimmed.substring(2), 16)
                : Integer.parseInt(trimmed);
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
                    parseNumber(range.substring(0, range.indexOf(','))),
                    parseNumber(range.substring(range.indexOf(',') + 1))
            ));
        }
        return result;
    }

}
