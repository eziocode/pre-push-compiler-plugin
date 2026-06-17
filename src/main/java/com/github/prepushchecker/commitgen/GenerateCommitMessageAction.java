package com.github.prepushchecker.commitgen;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.ui.CommitMessage;
import com.intellij.openapi.vcs.VcsDataKeys;
import org.jetbrains.annotations.NotNull;

/**
 * Action that generates a commit message for the current git diff using the
 * configured AI provider and injects it into the commit message text area.
 *
 * <p>Registered in {@code plugin.xml} and added to the
 * {@code Vcs.MessageActionGroup} group so it appears in the commit dialog.
 */
public final class GenerateCommitMessageAction extends AnAction {

    public GenerateCommitMessageAction() {
        super("Generate Commit Message (Pre-Push Checker)",
              "Pre-Push Checker: Generate a commit message from staged changes using the configured AI provider",
              AllIcons.Actions.Lightning);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) {
            e.getPresentation().setEnabled(false);
            return;
        }
        // Disable (grey out) when there are no staged or local changes — same
        // visual behaviour as the Copilot commit-message button.
        boolean hasChanges = !ChangeListManager.getInstance(project).getAllChanges().isEmpty();
        e.getPresentation().setEnabled(hasChanges);
        e.getPresentation().setDescription(hasChanges
            ? "Pre-Push Checker: Generate a commit message from staged changes using the configured AI provider"
            : "Pre-Push Checker: No changes detected — make or stage some changes first");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // Capture the commit message control from the action context (commit dialog)
        CommitMessage commitMessageControl = getCommitMessageControl(e);

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "Generating Commit Message with AI", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText("Contacting AI provider…");
                    try {
                        String message =
                            CommitMessageGeneratorService.getInstance(project).generate();
                        String finalMessage = message;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (project.isDisposed()) return;
                            if (commitMessageControl != null) {
                                commitMessageControl.setCommitMessage(finalMessage);
                            } else {
                                // Fallback: show in a dialog the user can copy
                                Messages.showMultilineInputDialog(
                                    project,
                                    "Copy the generated message into the commit dialog:",
                                    "Generated Commit Message",
                                    finalMessage,
                                    AllIcons.Actions.Lightning,
                                    null);
                            }
                        }, ModalityState.any());
                    } catch (Exception ex) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (project.isDisposed()) return;
                            String msg = ex.getMessage() != null
                                ? ex.getMessage()
                                : ex.getClass().getSimpleName();
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("Pre-Push Compilation Checker")
                                .createNotification(
                                    "AI commit message generation failed",
                                    msg,
                                    NotificationType.WARNING)
                                .notify(project);
                        }, ModalityState.any());
                    }
                }
            });
    }

    private static CommitMessage getCommitMessageControl(@NotNull AnActionEvent e) {
        try {
            Object raw = e.getDataContext().getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL.getName());
            if (raw instanceof CommitMessage cm) return cm;
        } catch (Exception ignored) {}
        return null;
    }
}
