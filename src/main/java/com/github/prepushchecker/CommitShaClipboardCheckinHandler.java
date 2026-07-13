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

/**
 * Copies the HEAD commit SHA to the system clipboard immediately after a
 * successful IDE commit when the user has chosen the "After Commit" trigger
 * in the Compilation Checker settings.
 */
public final class CommitShaClipboardCheckinHandler extends CheckinHandlerFactory {
    private static final int STABLE_READS_REQUIRED = 3;
    private static final int MAX_SHA_READS = 30;
    private static final long SHA_READ_DELAY_MILLIS = 100L;

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

                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    // Git/IDE can advance HEAD through several transient revisions after
                    // checkinSuccessful(). Require three consecutive identical reads so we
                    // copy final post-commit HEAD, never the first stale/intermediate tip.
                    String sha = readStableHeadSha(repoRoots);
                    // Fallback: project base path (single-repo projects where
                    // panel.getRoots() could not be mapped to a repository).
                    if (sha == null) {
                        String basePath = project.getBasePath();
                        if (basePath != null) sha = readStableHeadSha(List.of(basePath));
                    }
                    if (sha == null) return;
                    final String displaySha = format == PrePushCheckerSettings.ShaFormat.SHORT
                        ? sha.substring(0, 7) : sha;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        CopyPasteManager.getInstance().setContents(new StringSelection(displaySha));
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Pre-Push Compilation Checker")
                            .createNotification("Commit SHA Copied", displaySha,
                                NotificationType.INFORMATION)
                            .notify(project);
                    });
                });
            }
        };
    }

    private static boolean isCommitSha(String value) {
        return value != null && value.length() == 40
            && value.chars().allMatch(c -> (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    static String readStableHeadSha(@NotNull List<String> repoRoots) {
        String previous = null;
        int stableReads = 0;
        for (int read = 0; read < MAX_SHA_READS; read++) {
            if (read > 0) {
                try {
                    TimeUnit.MILLISECONDS.sleep(SHA_READ_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            String current = null;
            for (String root : repoRoots) {
                current = GitOperations.headSha(root);
                if (current != null) break;
            }
            if (!isCommitSha(current)) {
                previous = null;
                stableReads = 0;
                continue;
            }
            if (current.equals(previous)) stableReads++;
            else stableReads = 1;
            previous = current;
            if (stableReads >= STABLE_READS_REQUIRED) return current;
        }
        return null;
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
