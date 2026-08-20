package com.github.prepushchecker;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared exact diagnostic-path normalization and ignore filtering. */
final class DiagnosticPathMatcher {
    private DiagnosticPathMatcher() {}

    static @Nullable String normalizeProjectPath(@NotNull Project project, @NotNull String input) {
        String value = input.trim().replace('\\', '/');
        if (value.startsWith("file://")) value = value.substring("file://".length());
        int snapshot = value.indexOf("/prepushchecker-snapshot.");
        if (snapshot >= 0) {
            int worktree = value.indexOf("/worktree/", snapshot);
            if (worktree >= 0) value = value.substring(worktree + 10);
        }
        while (value.startsWith("./")) value = value.substring(2);
        if (!isAbsolute(value)) return cleanRelative(value);

        for (String root : roots(project)) {
            String relative = relativeTo(value, root);
            if (relative != null) return cleanRelative(relative);
            if (root.startsWith("/private/")) {
                relative = relativeTo(value, root.substring(8));
            } else {
                relative = relativeTo(value, "/private" + root);
            }
            if (relative != null) return cleanRelative(relative);
        }
        return null;
    }

    static @NotNull List<String> filterIgnored(
        @NotNull Project project, @NotNull List<String> diagnostics
    ) {
        Set<String> ignored = IgnoredCompilationFiles.getInstance(project).enabledPaths();
        if (ignored.isEmpty() || diagnostics.isEmpty()) return List.copyOf(diagnostics);
        List<String> result = new ArrayList<>(diagnostics.size());
        for (String diagnostic : diagnostics) {
            String path = CompilationEntryRenderer.extractPath(diagnostic);
            String normalized = path == null ? null : normalizeProjectPath(project, path);
            if (normalized == null || isAlwaysBlocking(diagnostic)
                    || !ignored.contains(normalized)) result.add(diagnostic);
        }
        return List.copyOf(result);
    }

    static boolean isIgnored(@NotNull Project project, @NotNull String path) {
        String normalized = normalizeProjectPath(project, path);
        return normalized != null
            && IgnoredCompilationFiles.getInstance(project).isIgnored(normalized);
    }

    static boolean isIgnoredDiagnostic(@NotNull Project project, @NotNull String diagnostic) {
        String path = CompilationEntryRenderer.extractPath(diagnostic);
        return path != null && !isAlwaysBlocking(diagnostic) && isIgnored(project, path);
    }

    static @Nullable String relativeToRoot(@NotNull String input, @NotNull String root) {
        String value = cleanAbsolute(input);
        String relative = relativeTo(value, root);
        if (relative == null) {
            String cleanRoot = cleanAbsolute(root);
            relative = cleanRoot.startsWith("/private/")
                ? relativeTo(value, cleanRoot.substring(8))
                : relativeTo(value, "/private" + cleanRoot);
        }
        return relative == null ? null : cleanRelative(relative);
    }

    static boolean canIgnoreDiagnostic(@NotNull Project project, @Nullable String diagnostic) {
        if (diagnostic == null) return false;
        String path = CompilationEntryRenderer.extractPath(diagnostic);
        String normalized = path == null ? null : normalizeProjectPath(project, path);
        return normalized != null && PushValidationPaths.isCompilableSource(normalized)
            && !isAlwaysBlocking(diagnostic);
    }

    private static final java.util.regex.Pattern THROWN_TYPE_PREFIX = java.util.regex.Pattern.compile(
        "^(java|javax|kotlin|org\\.jetbrains)\\..*(Exception|Error)\\b");

    /**
     * Infrastructure-level failures (compiler crashes, OOMs, aborted builds) can never be
     * suppressed by an ignore rule — only checks whether the message body names a real
     * compiler-infrastructure failure. Must NOT inspect the file path: source file names like
     * {@code PaymentException.java} legitimately contain words such as "exception".
     */
    private static boolean isAlwaysBlocking(String diagnostic) {
        String path = CompilationEntryRenderer.extractPath(diagnostic);
        if (path == null || "unknown".equals(path)) return true;

        String message = CompilationEntryRenderer.extractMessage(diagnostic);
        String body = message != null ? message : diagnostic;
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("internal compiler error")
            || lower.contains("internal error")
            || lower.contains("exception in thread")
            || lower.contains("stackoverflowerror")
            || lower.contains("stack overflow")
            || lower.contains("outofmemoryerror")
            || (lower.contains("annotation processor") && lower.contains("failed"))
            || lower.contains("compiler plugin")
            || lower.contains("timed out")
            || lower.contains("was aborted")
            || lower.contains("build script")
            || THROWN_TYPE_PREFIX.matcher(body.trim()).find();
    }

    static Set<String> roots(Project project) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        if (project.getBasePath() != null) roots.add(cleanAbsolute(project.getBasePath()));
        for (git4idea.repo.GitRepository repository
                : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
            roots.add(cleanAbsolute(repository.getRoot().getPath()));
        }
        return roots;
    }

    private static String relativeTo(String value, String root) {
        String normalizedRoot = cleanAbsolute(root);
        if (value.equals(normalizedRoot)) return "";
        return value.startsWith(normalizedRoot + "/")
            ? value.substring(normalizedRoot.length() + 1) : null;
    }

    private static String cleanAbsolute(String value) {
        try { return Path.of(value).toAbsolutePath().normalize().toString().replace('\\', '/'); }
        catch (RuntimeException ignored) { return value.replace('\\', '/').replaceAll("/+$", ""); }
    }

    private static String cleanRelative(String value) {
        if (value.isBlank()) return null;
        try {
            String normalized = Path.of(value).normalize().toString().replace('\\', '/');
            return normalized.equals("..") || normalized.startsWith("../") ? null : normalized;
        } catch (RuntimeException ignored) { return null; }
    }

    private static boolean isAbsolute(String value) {
        return value.startsWith("/") || value.matches("^[A-Za-z]:/.*");
    }
}
