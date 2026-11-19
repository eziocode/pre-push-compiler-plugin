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
        if (value.startsWith("file:")) {
            try { value = Path.of(java.net.URI.create(value)).toString().replace('\\', '/'); }
            catch (IllegalArgumentException invalid) { return null; }
        }
        if (value.chars().anyMatch(Character::isISOControl)) return null;
        if (!isAbsolute(value)) return cleanRelative(value);
        String base = project.getBasePath();
        String relative = base == null ? null : relativeToRoot(value, base);
        if (relative != null) return relative;
        // An attached repository outside the project can have exactly the same relative
        // layout. Keep its root in the identity and in the persisted selection.
        for (String root : roots(project)) {
            if (base != null && root.equals(base)) continue;
            relative = relativeToRoot(value, root);
            if (relative != null) return root + "/" + relative;
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
        String value = macPublicPath(cleanAbsolute(input));
        String relative = relativeTo(value, macPublicPath(cleanAbsolute(root)));
        return relative == null ? null : cleanRelative(relative);
    }

    private static String macPublicPath(String path) {
        if (com.intellij.openapi.util.SystemInfoRt.isMac
                && (path.startsWith("/private/var/") || path.startsWith("/private/tmp/")
                    || path.equals("/private/var") || path.equals("/private/tmp"))) {
            return path.substring("/private".length());
        }
        return path;
    }

    static boolean canIgnoreDiagnostic(@NotNull Project project, @Nullable String diagnostic) {
        if (diagnostic == null) return false;
        String path = CompilationEntryRenderer.extractPath(diagnostic);
        String normalized = path == null ? null : normalizeProjectPath(project, path);
        return normalized != null && PushValidationPaths.isCompilableSource(normalized)
            && !isAlwaysBlocking(diagnostic);
    }

    /**
     * Infrastructure-level failures (compiler crashes, OOMs, aborted builds) can never be
     * suppressed by an ignore rule — only checks whether the message body names a real
     * compiler-infrastructure failure. Must NOT inspect the file path: source file names like
     * {@code PaymentException.java} legitimately contain words such as "exception".
     */
    static boolean isAlwaysBlocking(String diagnostic) {
        String path = CompilationEntryRenderer.extractPath(diagnostic);
        if (path == null || "unknown".equals(path) || !diagnostic.startsWith("[")
                || CrossLayerImportCheck.isLayerDiagnostic(diagnostic)) return true;

        String message = CompilationEntryRenderer.extractMessage(diagnostic);
        String body = message != null ? message : diagnostic;
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return CompilerOutputRules.INFRASTRUCTURE_PATTERN.matcher(lower.trim()).find();
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
        if (value.equals(root)) return "";
        String prefix = root.endsWith("/") ? root : root + "/";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : null;
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
        return value.startsWith("/") || (value.length() >= 3 && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':' && value.charAt(2) == '/');
    }
}
