package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Ant-style path pattern: {@code **} matches zero or more directories, {@code *} matches
 * within one segment, {@code ?} matches one character. A trailing {@code /} means
 * {@code /**}, as in Ant. Paths and patterns use {@code /} separators.
 */
final class AntPathPattern {
    private final String source;
    private final String[] segments;

    private AntPathPattern(@NotNull String source, @NotNull String[] segments) {
        this.source = source;
        this.segments = segments;
    }

    static @NotNull AntPathPattern compile(@NotNull String pattern) {
        String normalized = pattern.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.endsWith("/")) normalized = normalized + "**";
        return new AntPathPattern(normalized, split(normalized));
    }

    boolean matches(@NotNull String path) {
        return matchSegments(segments, 0, split(path.replace('\\', '/')), 0);
    }

    @NotNull String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

    private static boolean matchSegments(String[] pattern, int p, String[] path, int s) {
        while (p < pattern.length) {
            String segment = pattern[p];
            if (segment.equals("**")) {
                while (p + 1 < pattern.length && pattern[p + 1].equals("**")) p++;
                if (p + 1 == pattern.length) return true;
                for (int i = s; i <= path.length; i++) {
                    if (matchSegments(pattern, p + 1, path, i)) return true;
                }
                return false;
            }
            if (s >= path.length || !matchSegment(segment, path[s])) return false;
            p++;
            s++;
        }
        return s == path.length;
    }

    private static boolean matchSegment(String pattern, String value) {
        int p = 0;
        int v = 0;
        int starP = -1;
        int starV = 0;
        while (v < value.length()) {
            if (p < pattern.length()
                    && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++;
                v++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                starP = p++;
                starV = v;
            } else if (starP >= 0) {
                p = starP + 1;
                v = ++starV;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    private static String[] split(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return parts.toArray(new String[0]);
    }
}
