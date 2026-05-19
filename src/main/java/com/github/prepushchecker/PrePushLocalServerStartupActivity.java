package com.github.prepushchecker;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Java startup fallback for IDE builds that skip Kotlin ProjectActivity dispatch.
 */
public final class PrePushLocalServerStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        PrePushLocalServer.runStartup(project);
    }
}
