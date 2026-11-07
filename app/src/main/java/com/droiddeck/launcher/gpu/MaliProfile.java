package com.droiddeck.launcher.gpu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MaliProfile {
    private static final Pattern NAME = Pattern.compile(
            "\\b((?:Mali|Immortalis)-G([0-9]{2,4})(?:\\s+MC[0-9]+)?)\\b",
            Pattern.CASE_INSENSITIVE);

    private MaliProfile() {}

    static String name(String text) {
        if (text == null) return "";
        Matcher match = NAME.matcher(text);
        return match.find() ? match.group(1) : "";
    }

    static int model(String text) {
        if (text == null) return 0;
        Matcher match = NAME.matcher(text);
        return match.find() ? Integer.parseInt(match.group(2)) : 0;
    }
}