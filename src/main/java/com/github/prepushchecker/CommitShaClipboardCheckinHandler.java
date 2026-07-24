package com.github.prepushchecker;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Copies the HEAD commit SHA to the system clipboard immediately after a
 * successful IDE commit when the user has chosen the "After Commit" trigger
 * in the Compilation Checker settings.
 */
public final class CommitShaClipboardCheckinHandler extends CheckinHandlerFactory {
    private static final int STABLE_READS_REQUIRED = 3;
    private static final int MAX_SHA_READS = 30;
    private static final long SHA_READ_DELAY_MILLIS = 100L;
    private static final long SHA_WATCH_DURATION_MILLIS = 10_000L;
    private static final ConcurrentHashMap<Project, Long> WATCH_GENERATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Project, Future<?>> WATCHERS = new ConcurrentHashMap<>();

    @Override
    public @NotNull CheckinHandler createHandler(
        @NotNull CheckinProjectPanel panel,
        @NotNull CommitContext commitContext
    ) {
        return new CheckinHandler() {
            @Override
            public void checkinSuccessful() {
                Project project = panel.getProject();
                if (project.isDisposed()) return;
                if (!PrePushCheckerSettings.isCopyCommitShaEnabled(project)) return;
                if (PrePushCheckerSettings.getCopyCommitShaTrigger(project)
                        != PrePushCheckerSettings.ShaTrigger.AFTER_COMMIT) return;

                PrePushCheckerSettings.ShaFormat format =
                    PrePushCheckerSettings.getCopyCommitShaFormat(project);

                // Capture roots on the EDT (checkinSuccessful is called on the EDT);
                // getRoots()/getVirtualFiles() must not be called from a background thread.
                Collection<VirtualFile> roots = panel.getRoots();
                Collection<VirtualFile> committedFiles = panel.getVirtualFiles();

                // Prefer the committed files themselves when mapping to repositories.
                // After branch switches or IDE restarts, the panel root list can lag the
                // currently checked-out repository, while the selected files still point
                // at the exact working tree that owns the commit.
                List<String> repoRoots = resolveRepositoryRoots(project, committedFiles, roots);

                startShaWatcher(project, repoRoots, format);
            }
        };
    }

    private static void startShaWatcher(
        @NotNull Project project,
        @NotNull List<String> repoRoots,
        @NotNull PrePushCheckerSettings.ShaFormat format
    ) {
        long generation = WATCH_GENERATIONS.merge(project, 1L, Long::sum);
        Future<?> previous = WATCHERS.remove(project);
        if (previous != null) previous.cancel(true);

        Future<?> watcher = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                watchHeadSha(repoRoots, SHA_WATCH_DURATION_MILLIS, sha -> {
                    if (project.isDisposed()
                        || !Long.valueOf(generation).equals(WATCH_GENERATIONS.get(project))) return;
                    String displaySha = format == PrePushCheckerSettings.ShaFormat.SHORT
                        ? sha.substring(0, 7) : sha;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()
                            || !Long.valueOf(generation).equals(WATCH_GENERATIONS.get(project))) return;
                        CopyPasteManager.getInstance().setContents(new StringSelection(displaySha));
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Pre-Push Compilation Checker")
                            .createNotification("Commit SHA Copied", displaySha,
                                NotificationType.INFORMATION)
                            .notify(project);
                    });
                });
            } finally {
                if (Long.valueOf(generation).equals(WATCH_GENERATIONS.get(project))) {
                    WATCHERS.remove(project);
                }
            }
        });
        WATCHERS.put(project, watcher);
    }

    private static boolean isCommitSha(String value) {
        return value != null && value.length() == 40
            && value.chars().allMatch(c -> (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    static void copyValidatedPushShaSilently(
        @NotNull Project project,
        @NotNull String sha
    ) {
        if (!isCommitSha(sha)
                || !PrePushCheckerSettings.isCopyCommitShaEnabled(project)
                || PrePushCheckerSettings.getCopyCommitShaTrigger(project)
                    != PrePushCheckerSettings.ShaTrigger.AFTER_PUSH) {
            return;
        }
        PrePushCheckerSettings.ShaFormat format =
            PrePushCheckerSettings.getCopyCommitShaFormat(project);
        String displaySha = format == PrePushCheckerSettings.ShaFormat.SHORT
            ? sha.substring(0, 7) : sha;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                CopyPasteManager.getInstance().setContents(new StringSelection(displaySha));
            }
        });
    }

    static String readStableHeadSha(@NotNull List<String> repoRoots) {
        final String[] current = {null};
        watchStableSha(() -> readCurrentHeadSha(repoRoots), MAX_SHA_READS,
            SHA_READ_DELAY_MILLIS, () -> current[0] = null,
            sha -> current[0] = sha);
        return current[0];
    }

    /**
     * Watches HEAD for the duration of the post-commit settling window. Every
     * distinct SHA accepted after three consecutive reads is emitted, including
     * the first stable SHA. This matters because rebase/amend workflows can move
     * HEAD after the first stable post-commit read.
     */
    static void watchHeadSha(
        @NotNull List<String> repoRoots,
        long durationMillis,
        @NotNull Consumer<String> onSha
    ) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        watchStableSha(() -> readCurrentHeadSha(repoRoots), Integer.MAX_VALUE,
            SHA_READ_DELAY_MILLIS, () -> {
            if (System.nanoTime() >= deadline) throw new WatchComplete();
        }, onSha);
    }

    static void watchStableSha(
        @NotNull Supplier<String> headReader,
        int maxReads,
        long delayMillis,
        @NotNull Runnable beforeRead,
        @NotNull Consumer<String> onSha
    ) {
        String previous = null;
        int stableReads = 0;
        String emitted = null;
        for (int read = 0; read < maxReads; read++) {
            try {
                beforeRead.run();
            } catch (WatchComplete complete) {
                return;
            }
            if (read > 0 && delayMillis > 0) {
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            String current = headReader.get();
            if (!isCommitSha(current)) {
                previous = null;
                stableReads = 0;
                continue;
            }
            if (current.equals(previous)) stableReads++;
            else stableReads = 1;
            previous = current;
            if (stableReads >= STABLE_READS_REQUIRED && !current.equals(emitted)) {
                emitted = current;
                onSha.accept(current);
            }
        }
    }

    private static String readCurrentHeadSha(@NotNull List<String> repoRoots) {
        for (String root : repoRoots) {
            String current = GitOperations.headSha(root);
            if (current != null) return current;
        }
        return null;
    }

    private static final class WatchComplete extends RuntimeException {
        private WatchComplete() {
        }
    }

    /**
     * Maps each committed root {@link VirtualFile} to the canonical root of its exact
     * git repository using IntelliJ's Git integration ({@link GitRepositoryManager}).
     *
     * <p>Reading HEAD from the repository the IDE actually tracks — rather than a raw
     * panel-root path — guarantees the copied SHA belongs to the correct repository in
     * multi-root workspaces and submodule layouts, and stays fully repository-agnostic
     * (no hardcoded roots). Falls back to the raw path when the model cannot map a root.
     * Returns an ordered, de-duplicated list preserving the input order.</p>
     */
    static @NotNull List<String> resolveRepositoryRoots(
        @NotNull Project project,
        @NotNull Collection<VirtualFile> committedFiles,
        @NotNull Collection<VirtualFile> fallbackRoots
    ) {
        GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (VirtualFile location : preferredRepositoryLookupLocations(committedFiles, fallbackRoots)) {
            String repoRoot = resolveRepositoryRoot(repoManager, location);
            if (repoRoot != null) resolved.add(repoRoot);
        }

        if (resolved.isEmpty()) {
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isBlank()) {
                resolved.add(basePath);
            }
        }
        return new ArrayList<>(resolved);
    }

    /**
     * Builds the ordered list of {@link VirtualFile} locations to probe for a repository
     * root, most-specific first:
     * <ol>
     *   <li>every committed file — these point at the exact working tree that owns the
     *       commit, so they map to the correct repository even after a branch switch or
     *       IDE restart when the panel root list can lag;</li>
     *   <li>the fallback panel roots reduced by {@link #selectCommitRoots}, which keeps
     *       only the deepest/most-nested root that actually owns each committed file (or
     *       the single deepest root when nothing can be anchored).</li>
     * </ol>
     * Reducing the roots — rather than appending every panel root — prevents an enclosing
     * parent repository from being probed for HEAD when a nested repository owns the
     * commit. The result is de-duplicated while preserving order.
     */
    static @NotNull List<VirtualFile> preferredRepositoryLookupLocations(
        @NotNull Collection<VirtualFile> committedFiles,
        @NotNull Collection<VirtualFile> fallbackRoots
    ) {
        LinkedHashSet<VirtualFile> ordered = new LinkedHashSet<>();
        ordered.addAll(committedFiles);
        ordered.addAll(selectCommitRoots(fallbackRoots, committedFiles));
        return new ArrayList<>(ordered);
    }

    /** Returns {@code roots} ordered by path length descending (deepest/most-nested first). */
    private static @NotNull List<VirtualFile> sortRootsDeepestFirst(
        @NotNull Collection<VirtualFile> roots
    ) {
        List<VirtualFile> sorted = new ArrayList<>(roots);
        sorted.sort(Comparator.comparingInt((VirtualFile root) -> root.getPath().length()).reversed());
        return sorted;
    }

    static @org.jetbrains.annotations.Nullable String resolveRepositoryRoot(
        @NotNull GitRepositoryManager repoManager,
        @NotNull VirtualFile location
    ) {
        GitRepository repo = repoManager.getRepositoryForFileQuick(location);
        if (repo == null) {
            repo = repoManager.getRepositoryForRootQuick(location);
        }
        return repo != null ? repo.getRoot().getPath() : null;
    }

    static @NotNull List<VirtualFile> selectCommitRoots(
        @NotNull Collection<VirtualFile> roots,
        @NotNull Collection<VirtualFile> committedFiles
    ) {
        if (roots.isEmpty()) return List.of();

        List<VirtualFile> sortedRoots = sortRootsDeepestFirst(roots);

        LinkedHashSet<VirtualFile> selected = new LinkedHashSet<>();
        for (VirtualFile file : committedFiles) {
            for (VirtualFile root : sortedRoots) {
                if (isUnderRoot(file, root)) {
                    selected.add(root);
                    break;
                }
            }
        }

        // No committed file could be mapped to a root (e.g. the panel exposed no
        // committed files, or none live under any known root). Fall back to the
        // single deepest/most-nested root rather than every root, so a nested
        // repository is never shadowed by its enclosing parent when reading HEAD.
        if (selected.isEmpty() && !sortedRoots.isEmpty()) selected.add(sortedRoots.get(0));
        return new ArrayList<>(selected);
    }

    private static boolean isUnderRoot(@NotNull VirtualFile file, @NotNull VirtualFile root) {
        return isUnderRootPath(file.getPath(), root.getPath());
    }

    static boolean isUnderRootPath(@NotNull String filePath, @NotNull String rootPath) {
        if (filePath.equals(rootPath)) return true;
        String normalizedRoot = rootPath.endsWith("/") ? rootPath : rootPath + "/";
        return filePath.startsWith(normalizedRoot);
    }
}
