package com.github.prepushchecker.review;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * "Review with AI" in the commit panel, next to the commit message button. Reviews exactly the
 * checked files, unversioned ones included; without a commit panel, every local change.
 */
public final class ReviewChangesAction extends AnAction implements DumbAware {

    public ReviewChangesAction() {
        super("Review Changes with AI (Pre-Push Checker)",
            "Pre-Push Checker: Review the checked files against the repository's review rules",
            AllIcons.Actions.ShowCode);
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
        boolean running = ReviewService.getInstance(project).isRunning();
        ChangeListManager clm = ChangeListManager.getInstance(project);
        boolean hasChanges = !clm.getAllChanges().isEmpty() || !clm.getUnversionedFilesPaths().isEmpty();
        e.getPresentation().setEnabled(hasChanges && !running && ReviewSettings.isEnabled(project));
        e.getPresentation().setIcon(running ? AllIcons.Process.Step_1 : AllIcons.Actions.ShowCode);
        e.getPresentation().setDescription(running
            ? "Pre-Push Checker: AI review in progress"
            : "Pre-Push Checker: Review the checked files against the repository's review rules");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        // Save before reading the file list and contents, so the review sees what is on disk now.
        FileDocumentManager.getInstance().saveAllDocuments();
        CommitWorkflowUi ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
        List<Path> files = new ArrayList<>();
        ReviewService service = ReviewService.getInstance(project);
        if (ui != null) {
            // Exactly the checked files: the user's selection.
            addChanges(files, ui.getIncludedChanges());
            for (FilePath fp : ui.getIncludedUnversionedFiles()) files.add(Path.of(fp.getPath()));
            service.reviewInBackground(files, null);
        } else {
            ChangeListManager clm = ChangeListManager.getInstance(project);
            addChanges(files, clm.getAllChanges());
            for (FilePath fp : clm.getUnversionedFilesPaths()) files.add(Path.of(fp.getPath()));
            service.reviewLocalChangesInBackground(files);
        }
    }

    static void addChanges(@NotNull List<Path> out, @NotNull Collection<Change> changes) {
        for (Change c : changes) {
            ContentRevision after = c.getAfterRevision();
            if (after == null) continue; // deleted: nothing to review
            out.add(Path.of(after.getFile().getPath()));
        }
    }
}
