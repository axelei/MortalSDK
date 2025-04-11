package net.krusher.mortalsdk;

import java.text.MessageFormat;

/**
 * Simple logger class for console output.
 */
public class Log {

    private Log() {}

    public static void pnl(String message, Object... args) {

        System.out.println(MessageFormat.format(message, args));
    }

    public static void p(String message, Object... args) {
        System.out.print(MessageFormat.format(message, args));
    }

    public static void pf(String message, Object... args) {
        System.out.printf(message, args);
    }

    public static void pnl() {
        System.out.println();
    }

}
