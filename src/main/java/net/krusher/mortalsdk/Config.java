package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ValueRange;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public record Config(String textChars,
                     int minChars,
                     Set<ValueRange> textRanges,
                     Set<ValueRange> sounds,
                     Set<ValueRange> bins,
                     String proPackExe) {

    private static final String DEFAULT_TEXT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!?.,0123456789:'\" ";
    private static final int DEFAULT_MIN_CHARS = 5;

    public Config() {
        this(DEFAULT_TEXT_CHARS,DEFAULT_MIN_CHARS, Set.of(), Set.of(), Set.of(), null);
    }

    public byte[] getTextCharsBytes() {
        return textChars.getBytes(StandardCharsets.ISO_8859_1);
    }

    public static Config getInstance(String fileName) throws IOException {
        Properties properties = new Properties();
        File configFile = new File(fileName);
        InputStream stream = new FileInputStream(configFile);
        properties.load(stream);

        String textChars = properties.getProperty("textChars", DEFAULT_TEXT_CHARS);
        if (!Boolean.FALSE.toString().equalsIgnoreCase(properties.getProperty("space", "true"))) {
            textChars = textChars.concat(" ");
        }
        int minChars = Integer.parseInt(properties.getProperty("minChars", String.valueOf(DEFAULT_MIN_CHARS)));
        String textRangesStr = properties.getProperty("textRanges");
        Set<ValueRange> textRanges = parseRanges(textRangesStr);
        String soundsStr = properties.getProperty("sounds");
        Set<ValueRange> sounds = parseRanges(soundsStr);
        String binsStr = properties.getProperty("bins");
        Set<ValueRange> bins = parseRanges(binsStr);
        String proPackExe = properties.getProperty("proPackExe", null);

        return new Config(textChars, minChars, textRanges, sounds, bins, proPackExe);
    }

    private static Set<ValueRange> parseRanges(String string) {
        if (StringUtils.isBlank(string)) {
            return Set.of();
        }
        Set<ValueRange> result = new HashSet<>();
        String[] ranges = string.split("#");
        for (String range : ranges) {
            result.add(ValueRange.of(
                    Integer.parseInt(range.substring(0, range.indexOf(','))),
                    Integer.parseInt(range.substring(range.indexOf(',') + 1))
            ));
        }
        return result;
    }

}
