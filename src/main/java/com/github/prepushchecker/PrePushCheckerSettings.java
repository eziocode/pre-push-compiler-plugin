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
