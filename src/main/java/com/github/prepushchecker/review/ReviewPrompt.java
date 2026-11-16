package com.github.prepushchecker.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds review and fix prompts and reads the JSON answers. Pure functions. */
public final class ReviewPrompt {

    /** One file to review: the path shown to the AI and its numbered diff. */
    public record FileDiff(@NotNull String displayPath, @NotNull String numberedDiff) {}

    /** A fix proposed by the AI: replace lines {@code startLine..endLine} (1-based, inclusive). */
    public record Fix(int startLine, int endLine, @NotNull String replacement) {}

    static final int MAX_FILE_CHARS = 40_000;
    static final int MAX_BATCH_CHARS = 90_000;
    static final int FIX_FULL_FILE_LINES = 2500;
    static final int FIX_WINDOW_LINES = 300;

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private ReviewPrompt() {}

    public static @NotNull String reviewSystemPrompt(@NotNull String rules) {
        return """
            You are a strict code reviewer for a team. Review ONLY the changes in the diffs below \
            against the team rules. Lines starting with "+" were added, "-" removed, the rest is \
            unchanged context; the number in front of a line is its line number in the new file.

            Team rules:
            ----
            %s
            ----

            Answer with one JSON object and nothing else:
            {"findings":[{"file":"<path exactly as given>","line":<line number in the new file>,\
            "rule":"<short name of the broken rule>","message":"<what is wrong, one or two sentences>",\
            "suggestion":"<how to fix it>"}]}
            Report a finding only when a change breaks one of the rules above. Do not report style \
            preferences or rules that are not listed. When nothing breaks a rule, answer {"findings":[]}.
            """.formatted(rules.strip());
    }

    public static @NotNull String reviewUserPrompt(@NotNull List<FileDiff> files) {
        StringBuilder sb = new StringBuilder();
        for (FileDiff f : files) {
            sb.append("### File: ").append(f.displayPath()).append("\n```diff\n")
                .append(f.numberedDiff().strip()).append("\n```\n\n");
        }
        return sb.toString();
    }

    /** Splits files into batches that each fit {@link #MAX_BATCH_CHARS}. */
    public static @NotNull List<List<FileDiff>> batches(@NotNull List<FileDiff> files) {
        List<List<FileDiff>> out = new ArrayList<>();
        List<FileDiff> current = new ArrayList<>();
        int size = 0;
        for (FileDiff f : files) {
            int len = f.numberedDiff().length() + f.displayPath().length() + 32;
            if (!current.isEmpty() && size + len > MAX_BATCH_CHARS) {
                out.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(f);
            size += len;
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    /** Prefixes each kept and added line of a unified diff with its new-file line number. */
    public static @NotNull String numberDiff(@NotNull String unifiedDiff) {
        StringBuilder out = new StringBuilder();
        int newLine = -1;
        boolean inHunk = false;
        for (String line : unifiedDiff.replace("\r\n", "\n").split("\n", -1)) {
            Matcher m = HUNK.matcher(line);
            if (m.matches()) {
                newLine = Integer.parseInt(m.group(1));
                inHunk = true;
                out.append(line).append('\n');
                continue;
            }
            if (!inHunk) continue; // diff --git / index / --- / +++ headers
            if (line.startsWith("\\")) continue; // "\ No newline at end of file"
            if (line.startsWith("+")) {
                out.append(String.format("%5d + ", newLine++)).append(line.substring(1)).append('\n');
            } else if (line.startsWith("-")) {
                out.append("      - ").append(line.substring(1)).append('\n');
            } else if (line.startsWith(" ")) {
                out.append(String.format("%5d   ", newLine++)).append(line.substring(1)).append('\n');
            } else if (line.isEmpty()) {
                continue;
            } else {
                inHunk = false;
            }
        }
        return cap(out.toString(), MAX_FILE_CHARS);
    }

    /** A new (untracked) file shown as all-added lines. */
    public static @NotNull String numberNewFile(@NotNull String content) {
        StringBuilder out = new StringBuilder("@@ new file @@\n");
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        int count = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        for (int i = 0; i < count; i++) {
            out.append(String.format("%5d + ", i + 1)).append(lines[i]).append('\n');
        }
        return cap(out.toString(), MAX_FILE_CHARS);
    }

    /** Reads findings, keeping only those for {@code allowedPaths} (as shown to the AI). */
    public static @NotNull List<ReviewFinding> parseFindings(@Nullable Map<?, ?> answer,
                                                             @NotNull Map<String, String> displayToAbsolute) {
        List<ReviewFinding> out = new ArrayList<>();
        if (answer == null || !(answer.get("findings") instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> f)) continue;
            String file = str(f.get("file"));
            String abs = resolvePath(file, displayToAbsolute);
            if (abs == null) continue;
            String message = str(f.get("message"));
            if (message.isBlank()) continue;
            out.add(new ReviewFinding(abs, toInt(f.get("line")), str(f.get("rule")), message,
                str(f.get("suggestion"))));
        }
        return out;
    }

    public static @NotNull String fixSystemPrompt(@NotNull String rules) {
        return """
            You fix one code review finding in one file. Change as little as possible, keep the \
            file's indentation and style, and do not touch unrelated code.

            Team rules:
            ----
            %s
            ----

            The file is shown with a line number in front of every line. Answer with one JSON object \
            and nothing else:
            {"startLine":<first line to replace>,"endLine":<last line to replace, inclusive>,\
            "replacement":"<new text for those lines, without line numbers>"}
            To insert without removing, set endLine to startLine - 1 (the text goes before startLine).
            """.formatted(rules.strip());
    }

    public static @NotNull String fixUserPrompt(@NotNull String displayPath, @NotNull ReviewFinding finding,
                                                @NotNull String fileContent) {
        String[] lines = fileContent.replace("\r\n", "\n").split("\n", -1);
        int total = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        int from = 1;
        int to = total;
        if (total > FIX_FULL_FILE_LINES && finding.line > 0) {
            from = Math.max(1, finding.line - FIX_WINDOW_LINES);
            to = Math.min(total, finding.line + FIX_WINDOW_LINES);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Finding");
        if (!finding.rule.isBlank()) sb.append(" (").append(finding.rule).append(')');
        sb.append(" at line ").append(finding.line).append(": ").append(finding.message).append('\n');
        if (!finding.suggestion.isBlank()) sb.append("Suggested fix: ").append(finding.suggestion).append('\n');
        sb.append("\n### File: ").append(displayPath);
        if (from > 1 || to < total) sb.append(" (lines ").append(from).append('-').append(to).append(" of ").append(total).append(')');
        sb.append("\n```\n");
        for (int i = from; i <= to; i++) {
            sb.append(String.format("%5d  ", i)).append(lines[i - 1]).append('\n');
        }
        sb.append("```\n");
        return sb.toString();
    }

    public static @Nullable Fix parseFix(@Nullable Map<?, ?> answer, int lineCount) {
        if (answer == null || !(answer.get("replacement") instanceof String replacement)) return null;
        int start = toInt(answer.get("startLine"));
        int end = toInt(answer.get("endLine"));
        if (start < 1 || start > lineCount + 1 || end < start - 1 || end > lineCount) return null;
        return new Fix(start, end, replacement);
    }

    /** Applies {@code fix} to {@code content}; line endings follow the file. */
    public static @NotNull String apply(@NotNull String content, @NotNull Fix fix) {
        String sep = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(content.replace("\r\n", "\n").split("\n", -1)));
        boolean trailingNewline = !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty();
        if (trailingNewline) lines.remove(lines.size() - 1);
        List<String> replacement = new ArrayList<>(List.of(fix.replacement().replace("\r\n", "\n").split("\n", -1)));
        if (!replacement.isEmpty() && replacement.get(replacement.size() - 1).isEmpty()) {
            replacement.remove(replacement.size() - 1);
        }
        int from = fix.startLine() - 1;
        int to = Math.min(lines.size(), fix.endLine());
        List<String> out = new ArrayList<>(lines.subList(0, from));
        out.addAll(replacement);
        if (to > from) out.addAll(lines.subList(to, lines.size()));
        else out.addAll(lines.subList(from, lines.size()));
        String joined = String.join(sep, out);
        return trailingNewline ? joined + sep : joined;
    }

    private static @Nullable String resolvePath(String file, Map<String, String> displayToAbsolute) {
        if (file.isBlank()) return null;
        String f = file.trim().replace('\\', '/');
        while (f.startsWith("./")) f = f.substring(2);
        String direct = displayToAbsolute.get(f);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : displayToAbsolute.entrySet()) {
            if (e.getValue().equals(f) || e.getKey().endsWith("/" + f) || f.endsWith("/" + e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o instanceof String s ? s.trim() : o == null ? "" : String.valueOf(o).trim();
    }

    private static int toInt(Object o) {
        if (o instanceof BigDecimal b) return b.intValue();
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n… (cut: file too large)\n";
    }
}
