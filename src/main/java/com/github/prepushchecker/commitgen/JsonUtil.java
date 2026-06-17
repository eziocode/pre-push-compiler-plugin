package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Object parsed = parse(json);
        return parsed == null ? null : findFirstString(parsed, key);
    }

    @Nullable
    public static String extractStringAtPath(@NotNull String json, @NotNull Object... path) {
        Object value = parse(json);
        for (Object segment : path) {
            if (value instanceof Map<?, ?> map && segment instanceof String key) {
                value = map.get(key);
            } else if (value instanceof List<?> list && segment instanceof Integer index) {
                if (index < 0 || index >= list.size()) return null;
                value = list.get(index);
            } else {
                return null;
            }
        }
        return value instanceof String string ? string : null;
    }

    @Nullable
    private static Object parse(@NotNull String json) {
        try {
            Parser parser = new Parser(json);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            return parser.isEnd() ? value : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static String findFirstString(@NotNull Object value, @NotNull String key) {
        if (value instanceof Map<?, ?> map) {
            Object direct = map.get(key);
            if (direct instanceof String string) return string;
            for (Object child : map.values()) {
                String found = child == null ? null : findFirstString(child, key);
                if (found != null) return found;
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                String found = child == null ? null : findFirstString(child, key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = json;
        }

        private boolean isEnd() {
            return index >= json.length();
        }

        private void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(json.charAt(index))) index++;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isEnd()) throw new IllegalArgumentException("Unexpected end of JSON.");
            char c = json.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return map;
            while (true) {
                skipWhitespace();
                if (isEnd() || json.charAt(index) != '"') {
                    throw new IllegalArgumentException("Expected object key.");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) return map;
                expect(',');
            }
        }

        private List<Object> parseArray() {
            index++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return list;
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (consume(']')) return list;
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!isEnd()) {
                char c = json.charAt(index++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (isEnd()) throw new IllegalArgumentException("Unterminated escape.");
                    char escaped = json.charAt(index++);
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(parseUnicodeEscape());
                        default -> throw new IllegalArgumentException("Unsupported escape: " + escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string.");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > json.length()) {
                throw new IllegalArgumentException("Incomplete unicode escape.");
            }
            String hex = json.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid unicode escape.", e);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!json.startsWith(literal, index)) {
                throw new IllegalArgumentException("Invalid literal.");
            }
            index += literal.length();
            return value;
        }

        private String parseNumber() {
            int start = index;
            while (!isEnd()) {
                char c = json.charAt(index);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("Expected JSON value.");
            }
            return json.substring(start, index);
        }

        private boolean consume(char expected) {
            if (!isEnd() && json.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (isEnd() || json.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "'.");
            }
            index++;
        }
    }
}
