package com.github.prepushchecker.review;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * After an IDE commit in on-push review mode, starts the AI review of the committed files in the
 * background ({@link ReviewService#preReviewAfterCommit}), so the later push rarely waits for it.
 */
public final class PreReviewCheckinHandler extends CheckinHandlerFactory {
    @Override
    public @NotNull CheckinHandler createHandler(@NotNull CheckinProjectPanel panel, @NotNull CommitContext commitContext) {
        return new CheckinHandler() {
            @Override
            public void checkinSuccessful() {
                Project project = panel.getProject();
                if (project.isDisposed()) return;
                // Read the panel on the EDT, where checkinSuccessful is called.
                List<Path> files = new ArrayList<>();
                for (VirtualFile vf : panel.getVirtualFiles()) {
                    if (vf.isInLocalFileSystem() && !vf.isDirectory()) files.add(vf.toNioPath());
                }
                ReviewService.getInstance(project).preReviewAfterCommit(files);
            }
        };
    }
}
