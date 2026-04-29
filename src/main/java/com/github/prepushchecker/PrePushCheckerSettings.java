package com.github.prepushchecker;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
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
            Files.writeString(settingsFile,
                "disableBuildToolFallback=" + disableFallback + "\n",
                StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Non-fatal; hook falls back to default (fallback enabled).
        }
    }
}
