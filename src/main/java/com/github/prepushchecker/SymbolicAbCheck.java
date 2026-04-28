package com.github.prepushchecker;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects A/B push hazards without invoking a build: scans git diffs of unpushed local
 * changes for newly declared identifiers, then checks whether the HEAD content of pushed
 * files references any of those identifiers. Catches the canonical case where the pushed
 * commit calls a method/class/field that only exists in an unpushed local edit.
 */
final class SymbolicAbCheck {
    private static final Logger LOG = Logger.getInstance(SymbolicAbCheck.class);
    private static final long GIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final int MAX_MESSAGES = 50;

    private static final Pattern METHOD_DECL = Pattern.compile(
        "^\\+\\s*(?:@\\w[\\w.]*(?:\\([^)]*\\))?\\s+)*"
            + "(?:public|protected|private|static|final|abstract|synchronized|default|native)"
            + "(?:\\s+(?:public|protected|private|static|final|abstract|synchronized|default|native))*"
            + "\\s+(?:<[^>]+>\\s+)?"
            + "(?:[\\w.<>\\[\\],\\s?]+?)\\s+(\\w+)\\s*\\(");

    private static final Pattern TYPE_DECL = Pattern.compile(
        "^\\+\\s*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\\s+)*"
            + "(?:class|interface|enum|record|@interface)\\s+(\\w+)");

    private static final Pattern FIELD_DECL = Pattern.compile(
        "^\\+\\s*(?:public|protected|private)"
            + "(?:\\s+(?:static|final|volatile|transient))*"
            + "\\s+(?:<[^>]+>\\s+)?"
            + "(?:[\\w.<>\\[\\],\\s?]+?)\\s+(\\w+)\\s*[=;]");

    private static final Set<String> STOP_LIST = Set.of(
        "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue",
        "true", "false", "null", "this", "super", "new", "throw", "throws", "try", "catch",
        "finally", "import", "package", "public", "private", "protected", "static", "final",
        "void", "int", "long", "short", "byte", "char", "boolean", "float", "double",
        "var", "yield", "instanceof", "abstract", "synchronized", "volatile", "transient",
        "String", "Object", "List", "Map", "Set", "Collection", "ArrayList", "HashMap"
    );

    private SymbolicAbCheck() {
    }

    static @NotNull List<String> detect(
        @NotNull Project project,
        @NotNull Collection<String> pushedPaths,
        @NotNull Collection<String> unpushedPaths
    ) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank() || pushedPaths.isEmpty() || unpushedPaths.isEmpty()) {
            return List.of();
        }
        Path projectRoot = Path.of(basePath);

        Map<String, Set<String>> addedByFile = new LinkedHashMap<>();
        for (String unpushed : unpushedPaths) {
            if (!isJavaPath(unpushed)) continue;
            Set<String> added = extractAddedDeclarations(projectRoot, unpushed);
            if (!added.isEmpty()) {
                addedByFile.put(unpushed, added);
            }
        }
        if (addedByFile.isEmpty()) return List.of();

        List<String> messages = new ArrayList<>();
        for (String pushed : pushedPaths) {
            if (!isJavaPath(pushed)) continue;
            String headContent = readHeadContent(projectRoot, pushed);
            if (headContent == null || headContent.isEmpty()) continue;
            for (Map.Entry<String, Set<String>> entry : addedByFile.entrySet()) {
                for (String identifier : entry.getValue()) {
                    if (containsIdentifier(headContent, identifier)) {
                        messages.add(String.format(
                            "[%s] references '%s', defined only in unpushed local change to %s",
                            pushed, identifier, entry.getKey()));
                        if (messages.size() >= MAX_MESSAGES) {
                            return List.copyOf(messages);
                        }
                    }
                }
            }
        }
        return List.copyOf(messages);
    }

    private static boolean isJavaPath(@NotNull String path) {
        return path.endsWith(".java") || path.endsWith(".kt");
    }

    private static @NotNull Set<String> extractAddedDeclarations(@NotNull Path projectRoot, @NotNull String path) {
        List<String> diff = runGit(projectRoot,
            List.of("git", "diff", "--no-color", "--unified=0", "HEAD", "--", path));
        if (diff.isEmpty()) return Set.of();

        Set<String> identifiers = new LinkedHashSet<>();
        for (String line : diff) {
            if (line == null || line.isEmpty()) continue;
            if (line.startsWith("+++")) continue;
            if (!line.startsWith("+")) continue;
            collectFromLine(line, identifiers);
        }
        identifiers.removeAll(STOP_LIST);
        return identifiers;
    }

    private static void collectFromLine(@NotNull String line, @NotNull Set<String> sink) {
        Matcher m = METHOD_DECL.matcher(line);
        if (m.find()) sink.add(m.group(1));
        m = TYPE_DECL.matcher(line);
        if (m.find()) sink.add(m.group(1));
        m = FIELD_DECL.matcher(line);
        if (m.find()) sink.add(m.group(1));
    }

    private static @Nullable String readHeadContent(@NotNull Path projectRoot, @NotNull String path) {
        List<String> lines = runGit(projectRoot, List.of("git", "show", "HEAD:" + path));
        if (lines.isEmpty()) return null;
        return String.join("\n", lines);
    }

    private static boolean containsIdentifier(@NotNull String content, @NotNull String identifier) {
        if (identifier.length() < 2) return false;
        int from = 0;
        while (from < content.length()) {
            int idx = content.indexOf(identifier, from);
            if (idx < 0) return false;
            int end = idx + identifier.length();
            boolean leftBoundary = idx == 0 || !isIdentifierChar(content.charAt(idx - 1));
            boolean rightBoundary = end >= content.length() || !isIdentifierChar(content.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            from = idx + 1;
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static @NotNull List<String> runGit(@NotNull Path directory, @NotNull List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(false)
                .start();
            List<String> out = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.add(line);
                }
            }
            if (!process.waitFor(GIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) return List.of();
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("git command failed: " + command, e);
            return List.of();
        }
    }
}
