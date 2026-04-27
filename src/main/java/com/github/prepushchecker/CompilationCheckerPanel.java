package com.github.prepushchecker;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class CompilationCheckerPanel extends JPanel implements Disposable {

    private final Project project;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JBLabel statusLabel = new JBLabel(" ");
    private final Runnable serviceListener = this::onServiceUpdate;

    CompilationCheckerPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        CompilationErrorService.getInstance(project).addListener(serviceListener);

        // ── Toolbar ──────────────────────────────────────────────────────────
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RunCheckAction());
        group.addSeparator();
        group.add(new RebuildCachesAction());
        group.addSeparator();
        group.add(new ReportAction());
        var toolbar = ActionManager.getInstance()
            .createActionToolbar("CompilationCheckerToolbar", group, true);
        toolbar.setTargetComponent(this);
        add(toolbar.getComponent(), BorderLayout.NORTH);

        // ── Error list ────────────────────────────────────────────────────────
        JBList<String> errorList = new JBList<>(listModel);
        errorList.setCellRenderer(new CompilationEntryRenderer());
        errorList.getEmptyText().setText("No compilation errors");
        errorList.getEmptyText().appendLine("Run a check or trigger a push to populate this list");

        // Double-click or Enter → navigate to file
        errorList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    CompilationEntryRenderer.navigateTo(project, errorList.getSelectedValue());
                }
            }
        });
        errorList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    CompilationEntryRenderer.navigateTo(project, errorList.getSelectedValue());
                }
            }
        });

        add(new JBScrollPane(errorList), BorderLayout.CENTER);

        // ── Status bar ────────────────────────────────────────────────────────
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(statusLabel, BorderLayout.SOUTH);

        onServiceUpdate();
    }

    // ── Disposable ────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        CompilationErrorService.getInstance(project).removeListener(serviceListener);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void onServiceUpdate() {
        List<String> errors = CompilationErrorService.getInstance(project).getErrors();
        listModel.clear();
        errors.forEach(listModel::addElement);
        statusLabel.setText(errors.isEmpty() ? " " : errors.size() + " error(s) from last check");
    }

    // ── Toolbar actions ───────────────────────────────────────────────────────

    private final class RunCheckAction extends AnAction {

        RunCheckAction() {
            super("Run Compilation Check", "Compile the project and show errors",
                AllIcons.Actions.Compile);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Running Compilation Check", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        CompilerManager compiler = CompilerManager.getInstance(project);
                        CountDownLatch latch = new CountDownLatch(1);
                        AtomicReference<List<String>> result =
                            new AtomicReference<>(Collections.emptyList());

                        ApplicationManager.getApplication().invokeAndWait(() ->
                            compiler.make(compiler.createProjectCompileScope(project),
                                (aborted, errorCount, warnings, ctx) -> {
                                    if (!aborted && errorCount > 0) {
                                        result.set(PrePushCompilationHandler.formatCompilerMessages(
                                            project, ctx.getMessages(CompilerMessageCategory.ERROR)));
                                    }
                                    latch.countDown();
                                }),
                            ModalityState.defaultModalityState()
                        );

                        try {
                            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                                indicator.checkCanceled();
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }

                        // Record project-scope result so a subsequent push with no file
                        // changes can reuse the cached verdict instead of rebuilding.
                        CompilationErrorService.getInstance(project).recordCompletion(
                            true, java.util.Collections.emptyMap(), result.get());
                    }
                });
        }
    }

    private final class RebuildCachesAction extends AnAction {

        RebuildCachesAction() {
            super("Rebuild Compiler Caches",
                "Full rebuild — use when incremental builds seem to miss errors " +
                    "(stale JPS dep-graph, interrupted builds, external writes).",
                AllIcons.Actions.ForceRefresh);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            int choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                "Rebuild will clear JPS caches and recompile the entire project. " +
                    "This can take several minutes on a large project. Continue?",
                "Rebuild Compiler Caches",
                com.intellij.openapi.ui.Messages.getQuestionIcon()
            );
            if (choice != com.intellij.openapi.ui.Messages.YES) return;

            ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Rebuilding Compiler Caches", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        CompilerManager compiler = CompilerManager.getInstance(project);
                        CountDownLatch latch = new CountDownLatch(1);
                        AtomicReference<List<String>> result =
                            new AtomicReference<>(Collections.emptyList());

                        ApplicationManager.getApplication().invokeAndWait(() ->
                            compiler.rebuild((aborted, errorCount, warnings, ctx) -> {
                                if (!aborted && errorCount > 0) {
                                    result.set(PrePushCompilationHandler.formatCompilerMessages(
                                        project, ctx.getMessages(CompilerMessageCategory.ERROR)));
                                }
                                latch.countDown();
                            }),
                            ModalityState.defaultModalityState()
                        );

                        try {
                            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                                indicator.checkCanceled();
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }

                        CompilationErrorService.getInstance(project).recordCompletion(
                            true, java.util.Collections.emptyMap(), result.get());
                    }
                });
        }
    }

    private final class ReportAction extends AnAction {

        private static final String ISSUES_NEW_URL =
            "https://github.com/eziocode/IntelliJ-Plugins/issues/new";

        ReportAction() {
            super("Report Issue", "Open a new GitHub issue for this plugin",
                AllIcons.General.BalloonWarning);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            int errorCount = CompilationErrorService.getInstance(project).getErrors().size();
            String title = errorCount > 0
                ? "[Pre-Push Checker] Issue with compilation check (" + errorCount + " error(s))"
                : "[Pre-Push Checker] ";
            String url = ISSUES_NEW_URL + "?title="
                + URLEncoder.encode(title, StandardCharsets.UTF_8);
            BrowserUtil.browse(url);
        }
    }
}
