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

    /**
     * A fix proposed by the AI: replace lines {@code startLine..endLine} (1-based, inclusive), and
     * add {@code imports} (whole import lines) that the new code needs and the file lacks.
     */
    public record Fix(int startLine, int endLine, @NotNull String replacement, @NotNull List<String> imports) {
        public Fix(int startLine, int endLine, @NotNull String replacement) {
            this(startLine, endLine, replacement, List.of());
        }
    }

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

            How to review (the same input must always give the same findings):
            - Go through EVERY file and EVERY changed hunk. For each hunk, check it against EVERY \
            rule above, one rule at a time. Do not stop after the first finding and do not skip a \
            rule because another one already matched.
            - List every violation you find, each as its own finding. Do not merge, sample or \
            summarise them.
            - Report a finding only when a change breaks one of the rules above. Do not report style \
            preferences or rules that are not listed.

            Answer with one JSON object and nothing else:
            {"findings":[{"file":"<path exactly as given>","line":<line number in the new file>,\
            "rule":"<short name of the broken rule>","message":"<what is wrong, one or two sentences>",\
            "suggestion":"<how to fix it>"}]}
            When no change breaks a rule, answer with an empty findings array.

            Writing findings:
            - Base every message on what the diff actually shows: say which lines were added or \
            removed. Never claim code changed when only a description, comment or string changed.
            - A rule that requires two parts of the code to agree (for example test steps and \
            their description) is broken by a change on either side.
            - When a finding can be fixed in more than one way, the suggestion names each option.
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

    /** Findings read from one AI answer, and how many listed items could not be used. */
    public record Parsed(@NotNull List<ReviewFinding> findings, int dropped) {}

    /** Reads findings, keeping only those for {@code allowedPaths} (as shown to the AI). */
    public static @NotNull List<ReviewFinding> parseFindings(@Nullable Map<?, ?> answer,
                                                             @NotNull Map<String, String> displayToAbsolute) {
        return parse(answer, displayToAbsolute).findings();
    }

    /**
     * Reads findings and counts the listed items that were dropped (no message, or a file that was
     * not reviewed). Common alternative key names are accepted, since providers vary. In a
     * single-file batch, a finding that names no file belongs to that file.
     */
    public static @NotNull Parsed parse(@Nullable Map<?, ?> answer, @NotNull Map<String, String> displayToAbsolute) {
        List<ReviewFinding> out = new ArrayList<>();
        if (answer == null || !(answer.get("findings") instanceof List<?> list)) return new Parsed(out, 0);
        String onlyFile = displayToAbsolute.size() == 1 ? displayToAbsolute.values().iterator().next() : null;
        int dropped = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> f)) {
                dropped++;
                continue;
            }
            String file = first(f, "file", "path", "filename");
            String abs = resolvePath(file, displayToAbsolute);
            if (abs == null && onlyFile != null && file.isBlank()) abs = onlyFile;
            String message = first(f, "message", "description", "issue");
            if (abs == null || message.isBlank()) {
                dropped++;
                continue;
            }
            out.add(new ReviewFinding(abs, toInt(f.get("line")), first(f, "rule", "name"), message,
                first(f, "suggestion", "fix")));
        }
        return new Parsed(out, dropped);
    }

    /**
     * What to record for one batch: findings per file (absolute path) for the files whose verdict
     * can be trusted, and an error when some files could not be judged and stay unreviewed.
     */
    public record Verdict(@NotNull Map<String, List<ReviewFinding>> toRecord, @Nullable String error) {}

    /**
     * Turns one AI answer into what may be recorded. A file is recorded as clean only when the
     * answer is a well-formed findings list in which every item could be used: an answer that is
     * missing, not a list, or has items that could not be tied to a file may hide a finding for
     * any file of the batch, so files without a usable finding are then left unreviewed.
     *
     * @param displayToAbsolute the batch's files, path shown to the AI → absolute path
     */
    public static @NotNull Verdict judge(@Nullable Map<?, ?> answer, @NotNull Map<String, String> displayToAbsolute) {
        int files = displayToAbsolute.size();
        if (answer == null) {
            return new Verdict(Map.of(), "The AI answer was not a findings list, so " + files
                + " file(s) were not marked reviewed. Try again or choose another model.");
        }
        if (!(answer.get("findings") instanceof List<?>)) {
            return new Verdict(Map.of(), "The AI answer had no findings list (\"findings\" was "
                + (answer.get("findings") == null ? "empty" : "not a list") + "), so " + files
                + " file(s) were not marked reviewed. Try again or choose another model.");
        }
        Parsed parsed = parse(answer, displayToAbsolute);
        Map<String, List<ReviewFinding>> byFile = new java.util.LinkedHashMap<>();
        for (String abs : displayToAbsolute.values()) byFile.put(abs, new ArrayList<>());
        for (ReviewFinding f : parsed.findings()) byFile.computeIfAbsent(f.path, k -> new ArrayList<>()).add(f);
        if (parsed.dropped() == 0) return new Verdict(byFile, null);

        // A dropped item may belong to any file: only files that already have findings (and so
        // are not approved anyway) are recorded; the rest stay unreviewed.
        Map<String, List<ReviewFinding>> withFindings = new java.util.LinkedHashMap<>();
        byFile.forEach((abs, list) -> {
            if (!list.isEmpty()) withFindings.put(abs, list);
        });
        int skipped = files - withFindings.size();
        String error = skipped == 0 ? null
            : "The AI answer listed " + parsed.dropped() + " finding(s) without a usable file or message, so "
                + skipped + " file(s) were not marked reviewed. Try again or choose another model.";
        return new Verdict(withFindings, error);
    }

    private static @NotNull String first(@NotNull Map<?, ?> m, @NotNull String... keys) {
        for (String k : keys) {
            String v = str(m.get(k));
            if (!v.isBlank()) return v;
        }
        return "";
    }

    public static @NotNull String fixSystemPrompt(@NotNull String rules) {
        return """
            You fix one code review finding in one file. Change as little as possible, keep the \
            file's indentation and style, and do not touch unrelated code.

            Team rules:
            ----
            %s
            ----

            How to edit:
            - Pick the smallest line range that needs to change. To only add code, insert instead \
            of replacing (see below).
            - The replacement replaces EVERY line from startLine to endLine. Copy each of those \
            lines into the replacement unchanged unless the fix itself must change it. Never drop \
            a declaration, call, brace or statement that the fix does not need to remove.
            - Follow the pattern the surrounding code already uses for the same thing, and put new \
            code at the matching step. Examples: if descriptions are collected with \
            desc.add("..."), add another desc.add("...") line next to the others; if they are \
            written inline as List.of("...", "..."), add the new entry inside that List.of(...) \
            instead of introducing a new variable or call. Reuse the same names, helpers, quoting \
            and wording style as the neighbouring lines.

            The result must compile:
            - Keep braces, parentheses and string quotes balanced inside the replaced range.
            - Only call methods, fields and types that exist in the file or in imports it already \
            has. Do not invent helpers. When the fix needs a type the file does not import, list \
            the full import line in "imports".
            - Keep each line's exact leading indentation (same tabs or spaces as the file). \
            Do not add blank lines, comments or trailing spaces the fix does not need, and never \
            comment code out instead of fixing it.

            The file is shown with a line number in front of every line. Answer with one JSON object \
            and nothing else:
            {"startLine":<first line to replace>,"endLine":<last line to replace, inclusive>,\
            "replacement":"<new text for those lines, without line numbers>",\
            "imports":["<import line to add, e.g. import java.util.Objects;>"]}
            To insert without removing, set endLine to startLine - 1 (the text goes before startLine).
            Leave "imports" empty when no new import is needed.
            """.formatted(rules.strip());
    }

    /**
     * Follow-up prompt when a fix removed lines it should have kept: shows the AI its answer and
     * the lines that went missing, and asks for a corrected one.
     */
    public static @NotNull String fixRetryUserPrompt(@NotNull String firstUserPrompt, @NotNull Fix previous,
                                                     @NotNull List<String> dropped) {
        StringBuilder sb = new StringBuilder(firstUserPrompt);
        sb.append("\nYour previous answer replaced lines ").append(previous.startLine()).append('-')
            .append(previous.endLine()).append(" with:\n```\n").append(previous.replacement().strip())
            .append("\n```\nThat deletes these existing lines, which the replacement no longer contains:\n```\n");
        for (String line : dropped) sb.append(line.strip()).append('\n');
        sb.append("```\nAnswer again with the same JSON shape. Keep those lines unless the finding itself "
            + "requires removing them; if it does, return the same answer.\n");
        return sb.toString();
    }

    /** Follow-up prompt when a fix leaves the file with parse errors. */
    public static @NotNull String fixSyntaxRetryUserPrompt(@NotNull String firstUserPrompt, @NotNull Fix previous,
                                                           @NotNull List<String> errors) {
        StringBuilder sb = new StringBuilder(firstUserPrompt);
        sb.append("\nYour previous answer replaced lines ").append(previous.startLine()).append('-')
            .append(previous.endLine()).append(" with:\n```\n").append(previous.replacement())
            .append("\n```\nWith that change the file no longer parses:\n");
        for (String e : errors) sb.append("- ").append(e).append('\n');
        sb.append("Answer again with the same JSON shape. Pick a range that covers every line the change "
            + "touches and keep braces, parentheses and quotes balanced.\n");
        return sb.toString();
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
        List<String> imports = new ArrayList<>();
        if (answer.get("imports") instanceof List<?> list) {
            for (Object o : list) {
                String line = str(o);
                // Only real import statements: anything else would be arbitrary code at the file's top.
                if (IMPORT_LINE.matcher(line).matches() && !line.contains("\n") && !imports.contains(line)) imports.add(line);
            }
        }
        return new Fix(start, end, replacement, imports);
    }

    private static final Pattern IMPORT_LINE = Pattern.compile("^(?:import|using|#include|from)\\s+\\S.*$");
    private static final Pattern IMPORT_OR_PACKAGE = Pattern.compile("^\\s*(?:import|using|#include|from)\\s+\\S.*$");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*(?:package|namespace)\\s+\\S.*$");

    /**
     * Repairs whitespace damage typical of AI fixes, so the diff shows only the real change:
     * replacement lines equal (ignoring whitespace) to a line of the replaced range keep that
     * line's exact text; new lines that lost the block's indentation get it back; trailing
     * whitespace and stray blank lines at either end are dropped.
     */
    public static @NotNull Fix normalize(@NotNull String content, @NotNull Fix fix) {
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        int total = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        List<String> range = new ArrayList<>();
        for (int i = fix.startLine(); i <= fix.endLine() && i <= total; i++) range.add(lines[i - 1]);

        List<String> repl = new ArrayList<>(List.of(fix.replacement().replace("\r\n", "\n").split("\n", -1)));
        for (int i = 0; i < repl.size(); i++) repl.set(i, stripTrailing(repl.get(i)));
        // Blank lines at either end: a replacement keeps them only where the replaced range had
        // one; an insert keeps them as separators unless the neighbouring line is already blank
        // (or the file edge), where they would double up.
        boolean keepLeading;
        boolean keepTrailing;
        if (!range.isEmpty()) {
            keepLeading = range.get(0).isBlank();
            keepTrailing = range.get(range.size() - 1).isBlank();
        } else {
            int before = fix.startLine() - 1;
            int after = fix.startLine();
            keepLeading = before >= 1 && !lines[before - 1].isBlank();
            keepTrailing = after <= total && !lines[after - 1].isBlank();
        }
        while (!repl.isEmpty() && repl.get(0).isEmpty() && !keepLeading) repl.remove(0);
        while (!repl.isEmpty() && repl.get(repl.size() - 1).isEmpty() && !keepTrailing) repl.remove(repl.size() - 1);
        if (repl.isEmpty()) return new Fix(fix.startLine(), fix.endLine(), "", fix.imports());

        // Base indentation the block should have: the range's first non-blank line, or for an
        // insert the line it goes before (or after, at the end of a block).
        String base = null;
        for (String r : range) if (!r.isBlank()) { base = indentOf(r); break; }
        if (base == null) {
            for (int i = fix.startLine(); i <= total && base == null; i++) if (!lines[i - 1].isBlank()) base = indentOf(lines[i - 1]);
            for (int i = fix.startLine() - 1; i >= 1 && base == null; i--) if (!lines[i - 1].isBlank()) base = indentOf(lines[i - 1]);
        }
        if (base == null) base = "";
        int minIndent = Integer.MAX_VALUE;
        for (String r : repl) if (!r.isBlank()) minIndent = Math.min(minIndent, indentOf(r).length());
        String shift = minIndent < base.length() ? base.substring(0, base.length() - minIndent) : "";
        // Where the AI's block indentation is right, nothing is re-indented.
        boolean tabs = base.contains("\t");

        List<String> pool = new ArrayList<>(range);
        List<String> out = new ArrayList<>();
        for (String r : repl) {
            if (r.isBlank()) {
                out.add("");
                continue;
            }
            String kept = takeSameLine(pool, r);
            if (kept != null) {
                out.add(kept);
            } else {
                String line = shift.isEmpty() ? r : shift + r;
                if (tabs) line = spacesToTabs(line, base);
                out.add(line);
            }
        }
        return new Fix(fix.startLine(), fix.endLine(), String.join("\n", out), fix.imports());
    }

    private static @Nullable String takeSameLine(@NotNull List<String> pool, @NotNull String line) {
        String key = line.strip();
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).strip().equals(key)) return pool.remove(i);
        }
        return null;
    }

    private static @NotNull String indentOf(@NotNull String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private static @NotNull String stripTrailing(@NotNull String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    /** In a tab-indented file, turns leading runs of spaces into tabs (4 spaces per tab, or the file's width). */
    private static @NotNull String spacesToTabs(@NotNull String line, @NotNull String base) {
        String indent = indentOf(line);
        if (!indent.contains(" ")) return line;
        int spaces = 0;
        int tabCount = 0;
        for (char c : indent.toCharArray()) {
            if (c == '\t') tabCount++;
            else spaces++;
        }
        if (spaces % 4 != 0) return line;
        return "\t".repeat(tabCount + spaces / 4) + line.substring(indent.length());
    }

    /** Imports of {@code fix} that {@code content} does not have yet, as they will be added. */
    public static @NotNull List<String> missingImports(@NotNull String content, @NotNull Fix fix) {
        if (fix.imports().isEmpty()) return List.of();
        java.util.Set<String> present = new java.util.HashSet<>();
        for (String l : content.replace("\r\n", "\n").split("\n", -1)) present.add(l.strip());
        List<String> out = new ArrayList<>();
        for (String i : fix.imports()) if (!present.contains(i.strip())) out.add(i.strip());
        return out;
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
        addImports(out, missingImports(content, fix));
        String joined = String.join(sep, out);
        return trailingNewline ? joined + sep : joined;
    }

    /** Inserts {@code imports} after the last import, else after the package line, else at the top. */
    private static void addImports(@NotNull List<String> lines, @NotNull List<String> imports) {
        if (imports.isEmpty()) return;
        int lastImport = -1;
        int pkg = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (IMPORT_OR_PACKAGE.matcher(l).matches()) lastImport = i;
            else if (pkg < 0 && PACKAGE.matcher(l).matches()) pkg = i;
            else if (lastImport >= 0 && !l.isBlank()) break; // imports only sit at the top
        }
        List<String> block = new ArrayList<>(imports);
        if (lastImport >= 0) {
            lines.addAll(lastImport + 1, block);
        } else if (pkg >= 0) {
            block.add(0, "");
            boolean blankAfter = pkg + 1 < lines.size() && lines.get(pkg + 1).isBlank();
            if (!blankAfter) block.add("");
            lines.addAll(pkg + 1, block);
        } else {
            block.add("");
            lines.addAll(0, block);
        }
    }

    /**
     * Existing non-blank lines inside the fix's range that the replacement neither keeps nor
     * rewrites into a similar line. Empty when the fix only adds or edits in place.
     */
    public static @NotNull List<String> droppedLines(@NotNull String content, @NotNull Fix fix) {
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        List<String> before = new ArrayList<>();
        for (int i = fix.startLine(); i <= fix.endLine() && i <= lines.length; i++) {
            if (!lines[i - 1].isBlank()) before.add(lines[i - 1].strip());
        }
        if (before.isEmpty()) return List.of();
        List<String> after = new ArrayList<>();
        for (String l : fix.replacement().replace("\r\n", "\n").split("\n", -1)) {
            if (!l.isBlank()) after.add(l.strip());
        }
        // Exact matches first, so a kept line is never paired with an edited one.
        List<String> unmatched = new ArrayList<>();
        for (String line : before) {
            if (!after.remove(line)) unmatched.add(line);
        }
        // Then the closest remaining new line, each used once: an edited line counts as kept.
        List<String> dropped = new ArrayList<>();
        for (String line : unmatched) {
            int best = -1;
            double bestScore = 0;
            for (int j = 0; j < after.size(); j++) {
                double score = similarity(line, after.get(j));
                if (score > bestScore) {
                    bestScore = score;
                    best = j;
                }
            }
            if (best >= 0 && bestScore >= EDIT_SIMILARITY) after.remove(best);
            else dropped.add(line);
        }
        return dropped;
    }

    static final double EDIT_SIMILARITY = 0.6;
    private static final int SIMILARITY_MAX_CHARS = 400;

    /** {@code 2·LCS / (|a|+|b|)} over characters, ignoring whitespace; 1.0 means equal. */
    static double similarity(@NotNull String a, @NotNull String b) {
        String x = a.replaceAll("\\s+", "");
        String y = b.replaceAll("\\s+", "");
        if (x.length() > SIMILARITY_MAX_CHARS) x = x.substring(0, SIMILARITY_MAX_CHARS);
        if (y.length() > SIMILARITY_MAX_CHARS) y = y.substring(0, SIMILARITY_MAX_CHARS);
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int i = 1; i <= x.length(); i++) {
            for (int j = 1; j <= y.length(); j++) {
                cur[j] = x.charAt(i - 1) == y.charAt(j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 2.0 * prev[y.length()] / (x.length() + y.length());
    }

    private static @Nullable String resolvePath(String file, Map<String, String> displayToAbsolute) {
        if (file.isBlank()) return null;
        String f = file.trim().replace('\\', '/');
        while (f.startsWith("./")) f = f.substring(2);
        String direct = displayToAbsolute.get(f);
        if (direct != null) return direct;
        String match = null;
        for (Map.Entry<String, String> e : displayToAbsolute.entrySet()) {
            if (e.getValue().equals(f) || e.getKey().endsWith("/" + f) || f.endsWith("/" + e.getKey())) {
                // Two files share this suffix (e.g. a bare "Test.java"): guessing would put the
                // finding on the wrong file and record the right one as clean.
                if (match != null && !match.equals(e.getValue())) return null;
                match = e.getValue();
            }
        }
        return match;
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
