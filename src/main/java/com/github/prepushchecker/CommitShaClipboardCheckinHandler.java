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

/**
 * Copies the HEAD commit SHA to the system clipboard immediately after a
 * successful IDE commit when the user has chosen the "After Commit" trigger
 * in the Compilation Checker settings.
 */
public final class CommitShaClipboardCheckinHandler extends CheckinHandlerFactory {

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
                    // Primary: read the authoritative HEAD SHA with a direct
                    // `git rev-parse HEAD` subprocess against each resolved repository
                    // root. checkinSuccessful() fires AFTER the commit object is written,
                    // so rev-parse returns the exact new commit id git created.
                    String sha = null;
                    for (String root : repoRoots) {
                        sha = GitOperations.headSha(root);
                        if (sha != null) break;
                    }
                    // Fallback: project base path (single-repo projects where
                    // panel.getRoots() could not be mapped to a repository).
                    if (sha == null) {
                        String basePath = project.getBasePath();
                        if (basePath != null) sha = GitOperations.headSha(basePath);
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

    static @NotNull List<VirtualFile> preferredRepositoryLookupLocations(
        @NotNull Collection<VirtualFile> committedFiles,
        @NotNull Collection<VirtualFile> fallbackRoots
    ) {
        LinkedHashSet<VirtualFile> ordered = new LinkedHashSet<>();
        ordered.addAll(committedFiles);
        ordered.addAll(selectCommitRoots(fallbackRoots, committedFiles));
        return new ArrayList<>(ordered);
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

        List<VirtualFile> sortedRoots = new ArrayList<>(roots);
        sortedRoots.sort(Comparator.comparingInt((VirtualFile root) -> root.getPath().length()).reversed());

        LinkedHashSet<VirtualFile> selected = new LinkedHashSet<>();
        for (VirtualFile file : committedFiles) {
            for (VirtualFile root : sortedRoots) {
                if (isUnderRoot(file, root)) {
                    selected.add(root);
                    break;
                }
            }
        }

        if (selected.isEmpty()) selected.addAll(roots);
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
