package com.github.prepushchecker;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Swaps branch-specific diagnostics (via {@link SmartBuildService} snapshots) and keeps hook/server metadata current when
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

    /** Git fires GIT_REPO_CHANGE in bursts (fetch, index refresh, commit). Coalesce them. */
    private static final long DEBOUNCE_MILLIS = 2_000L;
    /** Hook repair spawns several git processes; once a minute per root is plenty. */
    private static final long REPAIR_INTERVAL_MILLIS = 60_000L;

    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> pending =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> lastRepairAt = new java.util.concurrent.ConcurrentHashMap<>();

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        project.getMessageBus().connect(project).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            repository -> {
                if (project.isDisposed()) return;
                // Restores the snapshot verdict of the new state, or clears stale errors.
                // SmartBuildService debounces on its own.
                SmartBuildService.getInstance(project).repositoryChanged();
                scheduleRefresh(repository.getRoot().getPath());
            });
        // Uncommitted edits reverted or resolved: their review results may be stale now.
        project.getMessageBus().connect(project).subscribe(
            com.intellij.openapi.vcs.changes.ChangeListListener.TOPIC,
            new com.intellij.openapi.vcs.changes.ChangeListListener() {
                @Override
                public void changeListUpdateDone() {
                    if (project.isDisposed()
                            || com.github.prepushchecker.review.ReviewStateStore.getInstance(project).isEmpty()) {
                        return;
                    }
                    for (GitRepository repository
                            : git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories()) {
                        schedulePrune(repository.getRoot().getPath());
                    }
                }
            });
    }

    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> pendingPrunes =
        new java.util.concurrent.ConcurrentHashMap<>();

    private void schedulePrune(@NotNull String root) {
        pendingPrunes.compute(root, (key, previous) -> {
            if (previous != null) previous.cancel(false);
            return com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(() -> prune(root), DEBOUNCE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
    }

    private void prune(@NotNull String root) {
        pendingPrunes.remove(root);
        try {
            if (project.isDisposed()) return;
            // A push being checked has not reached the remote yet; prune after it.
            if (PrePushValidationCoordinator.getInstance(project).hasPendingWork()) {
                schedulePrune(root);
                return;
            }
            com.github.prepushchecker.review.ReviewService.getInstance(project)
                .pruneStale(java.nio.file.Path.of(root));
        } catch (Throwable failure) {
            LOG.debug("Could not clear stale review results for " + root, failure);
        }
    }

    private void scheduleRefresh(@NotNull String root) {
        pending.compute(root, (key, previous) -> {
            if (previous != null) previous.cancel(false);
            return com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(() -> refresh(root), DEBOUNCE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
    }

    private void refresh(@NotNull String root) {
        pending.remove(root);
        try {
            if (project.isDisposed()) return;
            PrePushLocalServer server = project.getService(PrePushLocalServer.class);
            if (server != null) server.publishPortFile(root);
            // Push, commit, reset or checkout: drop review results with nothing left to push.
            schedulePrune(root);
            ExternalPushErrorLoader.watchRepository(project, root);
            PrePushCheckerSettings.syncSettingsFile(project, root);
            long now = System.currentTimeMillis();
            Long last = lastRepairAt.get(root);
            if (last != null && now - last < REPAIR_INTERVAL_MILLIS) return;
            lastRepairAt.put(root, now);
            GitHookInstaller.HookRepairResult result = GitHookInstaller.repair(root);
            if (!result.isSuccess()) {
                HookNotifier.showNotInstalled(project, List.of(result));
            }
        } catch (Throwable failure) {
            LOG.warn("Could not refresh pre-push integration for " + root, failure);
        }
    }

    public static void runStartup(@NotNull Project project) {
        RepositoryStateMonitor monitor = project.getService(RepositoryStateMonitor.class);
        if (monitor != null) monitor.start();
    }
}
