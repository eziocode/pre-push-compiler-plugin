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
                    String sha = null;
                    for (VirtualFile root : roots) {
                        sha = GitOperations.headSha(root.getPath());
                        if (sha != null) break;
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
