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
import java.util.Collection;

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
                // getRoots() must not be called from a background thread.
                Collection<VirtualFile> roots = panel.getRoots();

                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    // Primary: read the authoritative HEAD SHA with a direct
                    // `git rev-parse HEAD` subprocess against each committed root.
                    // checkinSuccessful() fires AFTER the commit object is written,
                    // so rev-parse returns the exact new commit. This avoids the
                    // git4idea repo.update() path, whose refresh is asynchronous and
                    // could return the pre-commit (parent) revision — the root cause
                    // of the "copied SHA differs from the actual commit" report.
                    String sha = null;
                    for (VirtualFile root : roots) {
                        sha = GitOperations.headSha(root.getPath());
                        if (sha != null) break;
                    }
                    // Fallback 1: git4idea's GitRepository model. update() forces a
                    // fresh read of .git/HEAD; getRepositoryForRoot maps each panel
                    // root to its exact repository without relying on iteration order.
                    if (sha == null) {
                        GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
                        for (VirtualFile root : roots) {
                            GitRepository repo = repoManager.getRepositoryForRoot(root);
                            if (repo != null) {
                                repo.update();
                                sha = repo.getCurrentRevision();
                                if (sha != null) break;
                            }
                        }
                    }
                    // Fallback 2: project base path (single-repo projects where
                    // panel.getRoots() may return an unexpected ordering).
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
}
