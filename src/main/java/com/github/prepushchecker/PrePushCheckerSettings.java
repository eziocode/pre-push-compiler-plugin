package com.github.prepushchecker;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

final class PrePushCheckerSettings {
    private static final String STRICT_SNAPSHOT_GUARD_KEY =
        "prepushchecker.strictSnapshotGuard.enabled";

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
}
