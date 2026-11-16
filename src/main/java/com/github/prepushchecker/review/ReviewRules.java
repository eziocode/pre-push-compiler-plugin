package com.github.prepushchecker.review;

import com.github.prepushchecker.AntPathPattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A repository's review rule file ({@code .prepush-review.md} by default).
 *
 * <pre>
 * ---
 * mandatory: true
 * paths: ["**&#47;testcases/**&#47;*.java"]
 * exclude: ["**&#47;generated/**"]
 * ---
 * Free-text rules the AI reviews the changes against.
 * </pre>
 *
 * <p>Front matter is a small YAML subset: {@code key: value}, inline lists
 * ({@code [a, "b"]}) and block lists ({@code - a}). Unknown keys are ignored. Globs are
 * Ant-style and relative to the repository root. No {@code paths} means every file.</p>
 */
public final class ReviewRules {

    public static final String DEFAULT_FILE_NAME = ".prepush-review.md";
    static final int MAX_RULES_CHARS = 20_000;

    private final @NotNull String body;
    private final @Nullable Boolean mandatory;
    private final @NotNull List<String> includeGlobs;
    private final @NotNull List<String> excludeGlobs;
    private final @NotNull List<AntPathPattern> include;
    private final @NotNull List<AntPathPattern> exclude;

    private ReviewRules(@NotNull String body, @Nullable Boolean mandatory,
                        @NotNull List<String> includeGlobs, @NotNull List<String> excludeGlobs) {
        this.body = body;
        this.mandatory = mandatory;
        this.includeGlobs = List.copyOf(includeGlobs);
        this.excludeGlobs = List.copyOf(excludeGlobs);
        this.include = includeGlobs.stream().map(AntPathPattern::compile).toList();
        this.exclude = excludeGlobs.stream().map(AntPathPattern::compile).toList();
    }

    /** Reads the rule file for {@code repoRoot}, or {@code null} when it does not exist or is empty. */
    public static @Nullable ReviewRules load(@NotNull Path repoRoot, @Nullable String configuredPath) {
        Path file = ruleFile(repoRoot, configuredPath);
        if (!Files.isRegularFile(file)) return null;
        try {
            ReviewRules rules = parse(Files.readString(file, StandardCharsets.UTF_8));
            return rules.body.isBlank() ? null : rules;
        } catch (IOException e) {
            return null;
        }
    }

    public static @NotNull Path ruleFile(@NotNull Path repoRoot, @Nullable String configuredPath) {
        String name = configuredPath == null || configuredPath.isBlank() ? DEFAULT_FILE_NAME : configuredPath.trim();
        Path p = Path.of(name);
        return p.isAbsolute() ? p : repoRoot.resolve(name);
    }

    public static @NotNull ReviewRules parse(@NotNull String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        text = text.replace("\r\n", "\n");
        String[] lines = text.split("\n", -1);
        int start = 0;
        while (start < lines.length && lines[start].isBlank()) start++;
        int end = -1;
        if (start < lines.length && lines[start].trim().equals("---")) {
            for (int i = start + 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) return new ReviewRules(cap(text.strip()), null, List.of(), List.of());

        Boolean mandatory = null;
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        String listKey = null;
        for (int i = start + 1; i < end; i++) {
            String line = stripComment(lines[i]);
            if (line.isBlank()) continue;
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") && listKey != null) {
                addTo(listKey, unquote(trimmed.substring(2).trim()), include, exclude);
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colon + 1).trim();
            listKey = null;
            switch (key) {
                case "mandatory", "required" -> mandatory = parseBoolean(value);
                case "paths", "include", "exclude" -> {
                    String target = key.equals("exclude") ? "exclude" : "include";
                    if (value.isEmpty()) {
                        listKey = target;
                    } else {
                        for (String item : inlineList(value)) addTo(target, item, include, exclude);
                    }
                }
                default -> { }
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) body.append(lines[i]).append('\n');
        return new ReviewRules(cap(body.toString().strip()), mandatory, include, exclude);
    }

    /** {@code true} when {@code repoRelativePath} is reviewed under these rules. */
    public boolean inScope(@NotNull String repoRelativePath) {
        String path = repoRelativePath.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        for (AntPathPattern p : exclude) if (p.matches(path)) return false;
        if (include.isEmpty()) return true;
        for (AntPathPattern p : include) if (p.matches(path)) return true;
        return false;
    }

    public @NotNull String body() { return body; }

    /** {@code true}/{@code false} when the file says, {@code null} when it does not. */
    public @Nullable Boolean mandatory() { return mandatory; }

    public @NotNull List<String> includeGlobs() { return includeGlobs; }

    public @NotNull List<String> excludeGlobs() { return excludeGlobs; }

    /**
     * POSIX extended regex equivalent of {@code globs} for the git hook ({@code grep -E}), or
     * an empty string for "no globs".
     */
    public static @NotNull String toExtendedRegex(@NotNull List<String> globs) {
        List<String> parts = new ArrayList<>();
        for (String glob : globs) {
            String g = glob.trim().replace('\\', '/');
            while (g.startsWith("./")) g = g.substring(2);
            if (g.isEmpty()) continue;
            if (g.endsWith("/")) g = g + "**";
            parts.add(globToEre(g));
        }
        if (parts.isEmpty()) return "";
        return "^(" + String.join("|", parts) + ")$";
    }

    static @NotNull String globToEre(@NotNull String glob) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*' && glob.startsWith("**/", i)) {
                out.append("(.*/)?");
                i += 3;
                continue;
            }
            if (c == '*' && glob.startsWith("**", i)) {
                out.append(".*");
                i += 2;
                continue;
            }
            switch (c) {
                case '*' -> out.append("[^/]*");
                case '?' -> out.append("[^/]");
                case '[' -> {
                    int close = glob.indexOf(']', i + 1);
                    if (close > i + 1) {
                        out.append(glob, i, close + 1);
                        i = close;
                    } else {
                        out.append("\\[");
                    }
                }
                case '.', '(', ')', '+', '{', '}', '^', '$', '|', '\\' -> out.append('\\').append(c);
                default -> out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    /** Starter rule file written by "Create rule file" in settings. */
    public static @NotNull String template() {
        return """
            ---
            # Uncomment to make review mandatory for everyone: nobody can push matching files
            # without an AI review with every finding resolved. Left out, each developer's
            # "Block pushes" setting decides.
            # mandatory: true
            # Only these files are reviewed (Ant globs, relative to the repository root).
            # Remove the line to review every changed file.
            paths: ["**/*.java"]
            exclude: ["**/generated/**"]
            ---
            Review the changed code against these rules. Report only real violations.

            1. A test case's steps and its report description (the list passed to
               addRowToReport, or description.add(...) calls) must match. Flag a change that
               makes them disagree:
               - a step added to or removed from the code with no matching description change, or
               - a description entry added or removed with no matching step in the code.
               Say which side changed and which side is missing. Suggest both ways to fix it:
               update the description, or add or remove the step in the test case.
            2. Do not leave commented-out code in place of a real change.
            """;
    }

    private static String cap(String s) {
        return s.length() <= MAX_RULES_CHARS ? s : s.substring(0, MAX_RULES_CHARS) + "\n…";
    }

    private static void addTo(String key, String item, List<String> include, List<String> exclude) {
        if (item.isBlank()) return;
        (key.equals("exclude") ? exclude : include).add(item);
    }

    private static List<String> inlineList(String value) {
        String v = value.trim();
        if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        out.removeIf(String::isBlank);
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String stripComment(String line) {
        String t = line.stripLeading();
        return t.startsWith("#") ? "" : line;
    }

    private static @Nullable Boolean parseBoolean(String value) {
        String v = unquote(value).trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "on" -> Boolean.TRUE;
            case "false", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }
}
