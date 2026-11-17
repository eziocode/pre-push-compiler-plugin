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
        // One click between the two pages, next to the tool window's own header icons; works the
        // same whether the pages show as tabs or as the narrow-width dropdown.
        toolWindow.setTitleActions(java.util.List.of(new SwitchPageAction(toolWindow, content, reviewContent)));
    }

    /** Header icon that shows the other page: Code Review from Compilation, and back. */
    static final class SwitchPageAction extends com.intellij.openapi.actionSystem.AnAction
            implements com.intellij.openapi.project.DumbAware {
        private final ToolWindow toolWindow;
        private final Content compilation;
        private final Content review;

        SwitchPageAction(@NotNull ToolWindow toolWindow, @NotNull Content compilation, @NotNull Content review) {
            super("Show Code Review", "Switch between the Compilation and Code Review pages",
                com.intellij.icons.AllIcons.Actions.Preview);
            this.toolWindow = toolWindow;
            this.compilation = compilation;
            this.review = review;
        }

        private boolean onReview() {
            return toolWindow.getContentManager().getSelectedContent() == review;
        }

        @Override
        public void update(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
            boolean onReview = onReview();
            e.getPresentation().setText(onReview ? "Show Compilation" : "Show Code Review");
            e.getPresentation().setIcon(onReview
                ? com.intellij.icons.AllIcons.Actions.Compile
                : com.intellij.icons.AllIcons.Actions.Preview);
        }

        @Override
        public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
            toolWindow.getContentManager().setSelectedContent(onReview() ? compilation : review, true);
        }
    }
}
