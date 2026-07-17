package com.github.prepushchecker;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.application.ApplicationManager;
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
    public void pluginLoaded(@NotNull IdeaPluginDescriptor descriptor) {
        if (!isOurPlugin(descriptor)) return;

        // A ProjectActivity runs when a project opens, but is not reliably replayed when a
        // plugin is installed dynamically into an already-open IDE. Mark the plugin installed
        // immediately, then repair every open project off the UI/write-action thread.
        GitHookInstaller.touchGlobalMarker();
        ApplicationManager.getApplication().executeOnPooledThread(this::installHooksForOpenProjects);
    }

    @Override
    public void beforePluginUnload(@NotNull IdeaPluginDescriptor descriptor, boolean isUpdate) {
        if (isUpdate) return;
        if (!isOurPlugin(descriptor)) return;

        // Remove the global marker and proactively clean previously managed repos,
        // including those that are not currently open in this IDE session.
        GitHookInstaller.removeGlobalMarker();
        GitHookInstaller.uninstallTrackedRepos();

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

    void installHooksForOpenProjects() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            try {
                GitHookInstaller.runStartup(project);
            } catch (Throwable t) {
                LOG.warn("Hook installation failed for project " + project.getName(), t);
            }
        }
    }

    private static boolean isOurPlugin(IdeaPluginDescriptor descriptor) {
        return descriptor.getPluginId() != null
            && OUR_PLUGIN_ID.equals(descriptor.getPluginId().getIdString());
    }
}
