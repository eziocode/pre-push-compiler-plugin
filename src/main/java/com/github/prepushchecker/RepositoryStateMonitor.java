package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clears branch-specific diagnostics and keeps hook/server metadata current when
 * IntelliJ reports repository changes.
 */
@Service(Service.Level.PROJECT)
public final class RepositoryStateMonitor {
    private static final Logger LOG = Logger.getInstance(RepositoryStateMonitor.class);

    private final Project project;
    private final AtomicBoolean started = new AtomicBoolean();

    public RepositoryStateMonitor(@NotNull Project project) {
        this.project = project;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        project.getMessageBus().connect(project).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            repository -> {
                CompilationErrorService.getInstance(project).clearErrors();
                if (project.isDisposed()) return;
                String root = repository.getRoot().getPath();
                PrePushLocalServer server = project.getService(PrePushLocalServer.class);
                if (server != null) server.publishPortFile(root);
                ExternalPushErrorLoader.watchRepository(project, root);
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        PrePushCheckerSettings.syncSettingsFile(project, root);
                        GitHookInstaller.repair(root);
                    } catch (Throwable failure) {
                        LOG.warn("Could not refresh pre-push integration for " + root, failure);
                    }
                });
            });
    }

    public static void runStartup(@NotNull Project project) {
        RepositoryStateMonitor monitor = project.getService(RepositoryStateMonitor.class);
        if (monitor != null) monitor.start();
    }
}
