package com.github.prepushchecker;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class PrePushCheckerSettings {
    private static final String STRICT_SNAPSHOT_GUARD_KEY =
        "prepushchecker.strictSnapshotGuard.enabled";
    private static final String STASH_SNAPSHOT_FALLBACK_KEY =
        "prepushchecker.strictSnapshotGuard.stashFallback.enabled";
    private static final String DISABLE_BUILD_TOOL_FALLBACK_KEY =
        "prepushchecker.buildToolFallback.disabled";
    private static final String COPY_SHA_ENABLED_KEY =
        "prepushchecker.copyCommitSha.enabled";
    private static final String COPY_SHA_FORMAT_KEY =
        "prepushchecker.copyCommitSha.format";
    private static final String COPY_SHA_TRIGGER_KEY =
        "prepushchecker.copyCommitSha.trigger";

    /** Full 40-character SHA or the standard 7-character abbreviated form. */
    enum ShaFormat { FULL, SHORT }

    /** When the commit SHA should be auto-copied to the clipboard. */
    enum ShaTrigger { AFTER_PUSH, AFTER_COMMIT }

    private static final String BYPASS_TOKEN_NAME = "bypass-token";
    private static final long BYPASS_TOKEN_MAX_AGE_MS = 60 * 60 * 1000L; // 1 hour

    private PrePushCheckerSettings() {
    }

    static boolean isStrictSnapshotGuardEnabled(@NotNull Project project) {
        return PropertiesComponent.getInstance(project)
            .getBoolean(STRICT_SNAPSHOT_GUARD_KEY, false);
    }

    static void setStrictSnapshotGuardEnabled(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project)
            .setValue(STRICT_SNAPSHOT_GUARD_KEY, enabled, false);
    }

    static boolean isStashSnapshotFallbackEnabled(@NotNull Project project) {
        return PropertiesComponent.getInstance(project)
            .getBoolean(STASH_SNAPSHOT_FALLBACK_KEY, false);
    }

    static void setStashSnapshotFallbackEnabled(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project)
            .setValue(STASH_SNAPSHOT_FALLBACK_KEY, enabled, false);
    }

    static boolean isBuildToolFallbackDisabled(@NotNull Project project) {
        return PropertiesComponent.getInstance(project)
            .getBoolean(DISABLE_BUILD_TOOL_FALLBACK_KEY, false);
    }

    static void setBuildToolFallbackDisabled(@NotNull Project project, boolean disabled) {
        PropertiesComponent.getInstance(project)
            .setValue(DISABLE_BUILD_TOOL_FALLBACK_KEY, disabled, false);
    }

    /** Returns {@code true} if the auto-copy-SHA feature is active (default {@code true}). */
    static boolean isCopyCommitShaEnabled(@NotNull Project project) {
        return PropertiesComponent.getInstance(project)
            .getBoolean(COPY_SHA_ENABLED_KEY, true);
    }

    static void setCopyCommitShaEnabled(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project)
            .setValue(COPY_SHA_ENABLED_KEY, enabled, true);
    }

    /** Returns the SHA format to copy — {@link ShaFormat#FULL} by default. */
    static @NotNull ShaFormat getCopyCommitShaFormat(@NotNull Project project) {
        String val = PropertiesComponent.getInstance(project)
            .getValue(COPY_SHA_FORMAT_KEY, ShaFormat.FULL.name());
        try { return ShaFormat.valueOf(val); }
        catch (IllegalArgumentException ignored) { return ShaFormat.FULL; }
    }

    static void setCopyCommitShaFormat(@NotNull Project project, @NotNull ShaFormat format) {
        PropertiesComponent.getInstance(project)
            .setValue(COPY_SHA_FORMAT_KEY, format.name());
    }

    /** Returns when to copy — {@link ShaTrigger#AFTER_PUSH} by default. */
    static @NotNull ShaTrigger getCopyCommitShaTrigger(@NotNull Project project) {
        String val = PropertiesComponent.getInstance(project)
            .getValue(COPY_SHA_TRIGGER_KEY, ShaTrigger.AFTER_PUSH.name());
        try { return ShaTrigger.valueOf(val); }
        catch (IllegalArgumentException ignored) { return ShaTrigger.AFTER_PUSH; }
    }

    static void setCopyCommitShaTrigger(@NotNull Project project, @NotNull ShaTrigger trigger) {
        PropertiesComponent.getInstance(project)
            .setValue(COPY_SHA_TRIGGER_KEY, trigger.name());
    }

    /**
     * Writes (or refreshes) the settings file read by the hook script at push time.
     * Called on project open and whenever a setting changes via the UI.
     */
    static void syncSettingsFile(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) return;
        syncSettingsFile(project, basePath);
        for (git4idea.repo.GitRepository repository
                : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
            String root = repository.getRoot().getPath();
            if (!root.equals(basePath)) syncSettingsFile(project, root);
        }
    }

    static void syncSettingsFile(@NotNull Project project, @NotNull String repositoryRoot) {
        if (repositoryRoot.isBlank()) return;
        Path settingsFile = Path.of(repositoryRoot, ".idea/pre-push-checker/settings");
        try {
            Files.createDirectories(settingsFile.getParent());
            boolean disableFallback = isBuildToolFallbackDisabled(project);
            StringBuilder content = new StringBuilder();
            content.append("disableBuildToolFallback=").append(disableFallback).append('\n');
            String projectBase = project.getBasePath();
            for (String path : IgnoredCompilationFiles.getInstance(project).enabledPaths()) {
                if (projectBase == null) continue;
                String repositoryRelative = DiagnosticPathMatcher.relativeToRoot(
                    Path.of(projectBase).resolve(path).normalize().toString(), repositoryRoot);
                if (repositoryRelative != null && !repositoryRelative.contains("\n")) {
                    content.append("ignoredFile=").append(repositoryRelative).append('\n');
                }
            }
            String preferredJavaHome = resolveProjectJavaHome(project);
            if (preferredJavaHome != null) {
                content.append("preferredJavaHome=").append(preferredJavaHome).append('\n');
            }
            Files.writeString(settingsFile, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Non-fatal; hook falls back to default (fallback enabled).
        }
    }

    /**
     * Writes the bypass token (activation time in epoch millis) for the project base path
     * and every git root, so the hook skips the compilation check while the token is fresh.
     * The hook treats tokens older than {@link #BYPASS_TOKEN_MAX_AGE_MS} as expired, which
     * covers the IDE being closed or crashing while the bypass switch is on.
     */
    static void setForcePushBypass(@NotNull Project project, long activatedAtMillis) {
        for (String root : bypassRoots(project)) {
            Path tokenFile = Path.of(root, ".idea/pre-push-checker", BYPASS_TOKEN_NAME);
            try {
                Files.createDirectories(tokenFile.getParent());
                Files.writeString(tokenFile, String.valueOf(activatedAtMillis) + '\n',
                    StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
    }

    /** Removes the bypass token from the project base path and every git root. */
    static void clearForcePushBypass(@NotNull Project project) {
        for (String root : bypassRoots(project)) {
            Path tokenFile = Path.of(root, ".idea/pre-push-checker", BYPASS_TOKEN_NAME);
            try { Files.deleteIfExists(tokenFile); } catch (IOException ignored) {}
        }
    }

    static long bypassMaxAgeMillis() {
        return BYPASS_TOKEN_MAX_AGE_MS;
    }

    private static java.util.Set<String> bypassRoots(@NotNull Project project) {
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) roots.add(basePath);
        if (project.isDisposed()) return roots;
        try {
            for (git4idea.repo.GitRepository repository
                    : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
                roots.add(repository.getRoot().getPath());
            }
        } catch (RuntimeException ignored) {
            // Project is closing; the base path is still cleaned up.
        }
        return roots;
    }

    private static String resolveProjectJavaHome(@NotNull Project project) {
        // Project SDK belongs to the IntelliJ project model. ProjectActivity executes on a
        // background coroutine in current IDEs, so this lookup needs explicit read access.
        String homePath = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> {
                Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
                return sdk == null ? null : sdk.getHomePath();
            });
        if (homePath == null || homePath.isBlank()) return null;
        Path javaBin = Path.of(homePath, "bin", "java");
        if (!Files.isExecutable(javaBin)) return null;
        return homePath;
    }
}
