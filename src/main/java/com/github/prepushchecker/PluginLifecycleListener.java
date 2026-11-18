package com.github.prepushchecker;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginStateListener;
import com.intellij.ide.plugins.PluginStateManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Cleans up plugin-owned files (managed hooks, <code>.idea/pre-push-checker/</code> cache,
 * and the <code>.git/info/exclude</code> block) when the plugin is uninstalled or disabled.
 *
 * <p>Ignores unload events that are part of an update — those are followed by an immediate
 * re-install which would have to re-create everything anyway.
 */
public final class PluginLifecycleListener implements DynamicPluginListener, AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(PluginLifecycleListener.class);
    private static final String OUR_PLUGIN_ID = "com.github.prepushchecker";

    // Plugin Settings also sends this event for a removal queued for the next restart.
    @Service
    public static final class UninstallWatcher implements PluginStateListener, Disposable {
        public UninstallWatcher() {
            PluginStateManager.addStateListener(this);
        }

        @Override
        public void uninstall(@NotNull IdeaPluginDescriptor descriptor) {
            if (isOurPlugin(descriptor)) cleanRepositories();
        }

        @Override
        public void install(@NotNull IdeaPluginDescriptor descriptor) {}

        @Override
        public void dispose() {
            PluginStateManager.removeStateListener(this);
        }
    }

    static void watchForUninstall() {
        ApplicationManager.getApplication().getService(UninstallWatcher.class);
    }

    @Override
    public void welcomeScreenDisplayed() {
        watchForUninstall();
    }

    @Override
    public void pluginLoaded(@NotNull IdeaPluginDescriptor descriptor) {
        if (!isOurPlugin(descriptor)) return;

        // Newer IDEs replay ProjectActivities on dynamic load, older ones not reliably. Install
        // here as well so the hook is in place right away; GitHookInstaller serialises repairs
        // per root, so overlapping with a replayed activity is harmless.
        watchForUninstall();
        GitHookInstaller.touchGlobalMarker();
        ApplicationManager.getApplication().executeOnPooledThread(this::installHooksForOpenProjects);
    }

    @Override
    public void beforePluginUnload(@NotNull IdeaPluginDescriptor descriptor, boolean isUpdate) {
        if (isUpdate) return;
        if (!isOurPlugin(descriptor)) return;

        cleanRepositories();
    }

    private static void cleanRepositories() {
        GitHookInstaller.beginRemoval();
        Set<String> roots = GitHookInstaller.snapshotTrackedRepos();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            String basePath = project.getBasePath();
            if (basePath != null) roots.add(basePath);
            var repositories = project.getServiceIfCreated(GitRepositoryManager.class);
            if (repositories != null) {
                repositories.getRepositories().forEach(repo -> roots.add(repo.getRoot().getPath()));
            }
        }
        for (String root : roots) {
            try {
                GitHookInstaller.uninstall(root);
            } catch (RuntimeException failure) {
                LOG.warn("Could not clean pre-push files in " + root, failure);
            }
        }
    }

    void installHooksForOpenProjects() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;
            try {
                // Listen for repository changes first, then install the hook before anything else.
                RepositoryStateMonitor.runStartup(project);
                GitHookInstaller.installForProject(project, true);
                PrePushLocalServer.runStartup(project);
                ExternalPushErrorLoader.runStartup(project);
                SmartBuildService.runStartup(project);
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
