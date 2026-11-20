package com.github.prepushchecker.commitgen;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.vcs.commit.CommitWorkflowUi;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Action that generates a commit message for the current git diff using the
 * configured AI provider and injects it into the commit message text area.
 *
 * <p>Registered in {@code plugin.xml} and added to the
 * {@code Vcs.MessageActionGroup} group so it appears in the commit dialog.
 */
public final class GenerateCommitMessageAction extends AnAction {
    private static final Key<AtomicBoolean> GENERATING_KEY = Key.create("prepushchecker.ai.generating");

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
        boolean generating = isGenerating(project);
        // Disable (grey out) when there are no staged or local changes — same
        // visual behaviour as the Copilot commit-message button.
        ChangeListManager clm = ChangeListManager.getInstance(project);
        boolean hasChanges = !clm.getAllChanges().isEmpty() || !clm.getUnversionedFilesPaths().isEmpty();
        e.getPresentation().setIcon(generating ? AllIcons.Actions.Suspend : AllIcons.Actions.Lightning);
        e.getPresentation().setEnabled(hasChanges && !generating);
        e.getPresentation().setDescription(generating
            ? "Pre-Push Checker: AI commit message generation in progress"
            : hasChanges
            ? "Pre-Push Checker: Generate a commit message from staged changes using the configured AI provider"
            : "Pre-Push Checker: No changes detected — make or stage some changes first");
        if (hasChanges && !generating) pokeSpeculator(project, e);
    }

    private static volatile long lastPoke;

    /**
     * The commit panel is showing: let {@link CommitMessageSpeculator} write the message ahead of
     * time (it checks the user opted in by using Generate). The checked files are read on the EDT,
     * at most once a second.
     */
    private static void pokeSpeculator(@NotNull Project project, @NotNull AnActionEvent e) {
        if (!CommitMessageSettings.getInstance().settings().commitGenerateUsed) return;
        long now = System.currentTimeMillis();
        if (now - lastPoke < 1_000) return;
        lastPoke = now;
        CommitWorkflowUi ui = getCommitWorkflowUi(e);
        if (ui == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                CommitMessageSpeculator.getInstance(project)
                    .panelShown(ui.getIncludedChanges(), includedUnversionedPaths(ui));
            } catch (RuntimeException ignored) {
                // the commit panel went away
            }
        }, ModalityState.any());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        AtomicBoolean generating = project.getUserData(GENERATING_KEY);
        if (generating != null && generating.get()) return;
        AtomicBoolean generationState = new AtomicBoolean(true);
        project.putUserData(GENERATING_KEY, generationState);
        e.getPresentation().setIcon(AllIcons.Actions.Suspend);
        e.getPresentation().setEnabled(false);

        // Capture the commit workflow UI from the action context (commit dialog)
        CommitWorkflowUi commitWorkflowUi = getCommitWorkflowUi(e);

        // Respect the file-level checkboxes: only generate for the checked files.
        // Fall back to all changes when no specific files are selected (empty list).
        List<Change> includedChanges = commitWorkflowUi != null
            ? commitWorkflowUi.getIncludedChanges()
            : Collections.emptyList();
        // Checked unversioned (new) files: git diff does not show them, so pass them separately.
        List<java.nio.file.Path> includedUnversioned = commitWorkflowUi != null
            ? includedUnversionedPaths(commitWorkflowUi)
            : Collections.emptyList();
        String currentText = commitWorkflowUi != null ? commitWorkflowUi.getCommitMessageUi().getText() : null;
        // From now on the message for the checked files is prepared while the commit panel is open.
        CommitMessageSettings.getInstance().settings().commitGenerateUsed = true;

        ProgressManager.getInstance().run(
            // Cancellable: cancelling kills a running AI CLI (see AiCliRunner) and drops the result.
            new Task.Backgroundable(project, "Generating Commit Message with AI", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText("Contacting AI provider…");
                    AtomicBoolean finished = new AtomicBoolean();
                    try {
                        java.util.function.Consumer<String> onPartial = commitWorkflowUi == null ? null
                            : partialWriter(project, commitWorkflowUi, indicator, finished);
                        String message = CommitMessageGeneratorService.getInstance(project)
                            .generate(includedChanges, includedUnversioned, currentText, onPartial);
                        indicator.checkCanceled();
                        finished.set(true);
                        String finalMessage = message;
                        // setText edits the commit message document: needs a write-safe context.
                        com.github.prepushchecker.WriteSafeEdt.invokeLater(() -> {
                            if (project.isDisposed() || indicator.isCanceled()) return;
                            if (commitWorkflowUi != null) {
                                commitWorkflowUi.getCommitMessageUi().setText(finalMessage);
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
                        });
                    } catch (ProcessCanceledException cancelled) {
                        finished.set(true);
                        throw cancelled;
                    } catch (Exception ex) {
                        finished.set(true);
                        if (indicator.isCanceled()) return;
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
                    } finally {
                        generationState.set(false);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!project.isDisposed()) {
                                project.putUserData(GENERATING_KEY, null);
                            }
                        });
                    }
                }
            });
    }

    /**
     * Shows the answer in the commit field while a streaming provider writes it: at most ~10
     * updates a second, none after the final text is set ({@code finished}).
     */
    private static @NotNull java.util.function.Consumer<String> partialWriter(@NotNull Project project,
                                                                            @NotNull CommitWorkflowUi ui,
                                                                            @NotNull ProgressIndicator indicator,
                                                                            @NotNull AtomicBoolean finished) {
        java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicReference<String> latest = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
        return text -> {
            latest.set(text);
            long now = System.currentTimeMillis();
            if (now - last.get() < 100 || !queued.compareAndSet(false, true)) return;
            last.set(now);
            com.github.prepushchecker.WriteSafeEdt.invokeLater(() -> {
                queued.set(false);
                if (finished.get() || project.isDisposed() || indicator.isCanceled()) return;
                String partial = latest.get();
                if (partial != null && !partial.isBlank()) ui.getCommitMessageUi().setText(partial.strip());
            });
        };
    }

    private static CommitWorkflowUi getCommitWorkflowUi(@NotNull AnActionEvent e) {
        try {
            CommitWorkflowUi workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
            if (workflowUi != null) return workflowUi;
        } catch (Exception ignored) {}
        return null;
    }

    /** Paths of the unversioned files checked in the commit panel. */
    public static @NotNull List<java.nio.file.Path> includedUnversionedPaths(@NotNull CommitWorkflowUi ui) {
        List<java.nio.file.Path> out = new java.util.ArrayList<>();
        for (com.intellij.openapi.vcs.FilePath fp : ui.getIncludedUnversionedFiles()) {
            out.add(java.nio.file.Path.of(fp.getPath()));
        }
        return out;
    }

    private static boolean isGenerating(@NotNull Project project) {
        AtomicBoolean state = project.getUserData(GENERATING_KEY);
        return state != null && state.get();
    }
}
