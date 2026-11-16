package com.github.prepushchecker;

import com.github.prepushchecker.review.ReviewPanel;
import com.github.prepushchecker.review.ReviewService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class CompilationCheckerToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CompilationCheckerPanel panel = new CompilationCheckerPanel(project, toolWindow);
        Content content = ContentFactory.getInstance().createContent(panel, "Compilation", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(toolWindow.getDisposable(), panel);

        ReviewPanel review = new ReviewPanel(project);
        Content reviewContent = ContentFactory.getInstance().createContent(review, ReviewService.TAB_NAME, false);
        reviewContent.setDisposer(review);
        toolWindow.getContentManager().addContent(reviewContent);

        AdaptiveContentTabs.install(toolWindow);
    }
}
