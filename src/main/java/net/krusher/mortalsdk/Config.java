package net.krusher.mortalsdk;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.temporal.ValueRange;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public record Config(int minChars,
                     Set<ValueRange> textRanges,
                     Set<ValueRange> sounds,
                     Set<ValueRange> bins,
                     Set<ValueRange> spaceRanges,
                     String proPackExe) {

    private static final int DEFAULT_MIN_CHARS = 5;

    public Config() {
        this(DEFAULT_MIN_CHARS, Set.of(), Set.of(), Set.of(), Set.of(), null);
    }

    public static Config getInstance(String fileName) throws IOException {
        Properties properties = new Properties();
        File configFile = new File(fileName);
        InputStream stream = new FileInputStream(configFile);
        properties.load(stream);

        int minChars = Integer.parseInt(properties.getProperty("minChars", String.valueOf(DEFAULT_MIN_CHARS)));
        String textRangesStr = properties.getProperty("textRanges");
        Set<ValueRange> textRanges = parseRanges(textRangesStr);
        String soundsStr = properties.getProperty("sounds");
        Set<ValueRange> sounds = parseRanges(soundsStr);
        String binsStr = properties.getProperty("bins");
        Set<ValueRange> bins = parseRanges(binsStr);
        String spaceRangesStr = properties.getProperty("spaceRanges");
        Set<ValueRange> spaceRanges = parseRanges(spaceRangesStr);
        String proPackExe = properties.getProperty("proPackExe", null);

        return new Config(minChars, textRanges, sounds, bins, spaceRanges, proPackExe);
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
