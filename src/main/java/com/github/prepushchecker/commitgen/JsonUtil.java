package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal JSON helpers for building request bodies and extracting string values
 * from provider API responses — no third-party dependency required.
 */
public final class JsonUtil {

    private JsonUtil() {}

    /** Escapes a Java string for embedding as a JSON string value. */
    public static @NotNull String escape(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Returns {@code "value"} with the content properly escaped. */
    public static @NotNull String quoted(@NotNull String s) {
        return "\"" + escape(s) + "\"";
    }

    /**
     * Extracts the string value of the first {@code "key":"..."} occurrence.
     * Handles basic JSON escape sequences. Returns {@code null} if not found.
     */
    @Nullable
    public static String extractString(@NotNull String json, @NotNull String key) {
        String searchKey = "\"" + key + "\"";
        int ki = json.indexOf(searchKey);
        if (ki < 0) return null;
        int ci = json.indexOf(':', ki + searchKey.length());
        if (ci < 0) return null;

        int qi = ci + 1;
        while (qi < json.length() && json.charAt(qi) != '"') qi++;
        if (qi >= json.length()) return null;
        qi++; // skip opening quote

        StringBuilder sb = new StringBuilder();
        while (qi < json.length()) {
            char c = json.charAt(qi);
            if (c == '"') break;
            if (c == '\\' && qi + 1 < json.length()) {
                char next = json.charAt(qi + 1);
                switch (next) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'u'  -> {
                        if (qi + 5 < json.length()) {
                            String hex = json.substring(qi + 2, qi + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                qi += 4;
                            } catch (NumberFormatException e) {
                                sb.append('\\');
                                sb.append(next);
                            }
                        }
                    }
                    default   -> { sb.append('\\'); sb.append(next); }
                }
                qi += 2;
            } else {
                sb.append(c);
                qi++;
            }
        }
        return sb.toString();
    }
}
