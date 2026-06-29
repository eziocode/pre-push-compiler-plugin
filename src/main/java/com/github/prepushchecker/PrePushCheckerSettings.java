package com.github.prepushchecker;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
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
    private static final String REBASE_PRECHECK_KEY =
        "prepushchecker.rebasePrecheck.enabled";
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

    /**
     * When enabled, {@code PrePushCompilationHandler.handle} fetches each push
     * root before scheduling the compile and offers the user a chance to
     * rebase first if the remote is ahead. Default {@code false} — fetch on
     * every push is a network round-trip and a setting change to a ref the
     * user might not want.
     */
    static boolean isRebasePrecheckEnabled(@NotNull Project project) {
        return PropertiesComponent.getInstance(project)
            .getBoolean(REBASE_PRECHECK_KEY, false);
    }

    static void setRebasePrecheckEnabled(@NotNull Project project, boolean enabled) {
        PropertiesComponent.getInstance(project)
            .setValue(REBASE_PRECHECK_KEY, enabled, false);
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
        Path settingsFile = Path.of(basePath, ".idea/pre-push-checker/settings");
        try {
            Files.createDirectories(settingsFile.getParent());
            boolean disableFallback = isBuildToolFallbackDisabled(project);
            StringBuilder content = new StringBuilder();
            content.append("disableBuildToolFallback=").append(disableFallback).append('\n');
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
     * Creates a one-shot bypass token so the next push attempt skips the compilation
     * check. The hook consumes (deletes) the token, making it single-use. Expires
     * after {@link #BYPASS_TOKEN_MAX_AGE_MS} to avoid stale flags.
     */
    static void setForcePushBypass(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) return;
        Path tokenFile = Path.of(basePath, ".idea/pre-push-checker", BYPASS_TOKEN_NAME);
        try {
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, String.valueOf(System.currentTimeMillis()) + '\n',
                StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    /**
     * Returns {@code true} if a valid (non-expired) bypass token exists.
     */
    static boolean isForcePushBypassActive(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) return false;
        Path tokenFile = Path.of(basePath, ".idea/pre-push-checker", BYPASS_TOKEN_NAME);
        if (!Files.exists(tokenFile)) return false;
        try {
            String content = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            long timestamp = Long.parseLong(content);
            return (System.currentTimeMillis() - timestamp) < BYPASS_TOKEN_MAX_AGE_MS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Removes the bypass token (called when the user toggles the force push off,
     * or after the token has been consumed by a push).
     */
    static void clearForcePushBypass(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) return;
        Path tokenFile = Path.of(basePath, ".idea/pre-push-checker", BYPASS_TOKEN_NAME);
        try { Files.deleteIfExists(tokenFile); } catch (IOException ignored) {}
    }

    private static String resolveProjectJavaHome(@NotNull Project project) {
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null) return null;
        String homePath = sdk.getHomePath();
        if (homePath == null || homePath.isBlank()) return null;
        Path javaBin = Path.of(homePath, "bin", "java");
        if (!Files.isExecutable(javaBin)) return null;
        return homePath;
    }
}
