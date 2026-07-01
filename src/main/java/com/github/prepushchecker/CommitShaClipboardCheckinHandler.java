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
                    // Primary: use git4idea's GitRepository model, which is always
                    // synced with the actual .git directory on disk. Calling update()
                    // forces a fresh read so we never return a cached pre-commit SHA.
                    // This also handles multi-root projects correctly: getRepositoryForRoot
                    // maps each panel root to its exact repository without relying on
                    // iteration order.
                    String sha = null;
                    GitRepositoryManager repoManager = GitRepositoryManager.getInstance(project);
                    for (VirtualFile root : roots) {
                        GitRepository repo = repoManager.getRepositoryForRoot(root);
                        if (repo != null) {
                            repo.update();
                            sha = repo.getCurrentRevision();
                            if (sha != null) break;
                        }
                    }
                    // Fallback 1: direct git subprocess per root (handles roots that
                    // git4idea does not recognise as exact VCS roots, e.g. module roots).
                    if (sha == null) {
                        for (VirtualFile root : roots) {
                            sha = GitOperations.headSha(root.getPath());
                            if (sha != null) break;
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
