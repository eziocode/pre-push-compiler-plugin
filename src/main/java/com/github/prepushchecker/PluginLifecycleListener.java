package com.github.prepushchecker;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

/**
 * Cleans up plugin-owned files (managed hooks, <code>.idea/pre-push-checker/</code> cache,
 * and the <code>.git/info/exclude</code> block) when the plugin is uninstalled or disabled.
 *
 * <p>Ignores unload events that are part of an update — those are followed by an immediate
 * re-install which would have to re-create everything anyway.
 */
public final class PluginLifecycleListener implements DynamicPluginListener {

    private static final Logger LOG = Logger.getInstance(PluginLifecycleListener.class);
    private static final String OUR_PLUGIN_ID = "com.github.prepushchecker";

    @Override
    public void beforePluginUnload(@NotNull IdeaPluginDescriptor descriptor, boolean isUpdate) {
        if (isUpdate) return;
        if (descriptor.getPluginId() == null
                || !OUR_PLUGIN_ID.equals(descriptor.getPluginId().getIdString())) {
            return;
        }
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            String basePath = project.getBasePath();
            if (basePath != null) {
                try {
                    GitHookInstaller.uninstall(basePath);
                } catch (Throwable t) {
                    LOG.warn("Cleanup failed for project " + project.getName(), t);
                }
            }
        }
    }
}
