package com.github.prepushchecker;

import com.github.prepushchecker.commitgen.CommitMessageGeneratorService;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBCheckBox;
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

    private static final Icon REPAIR_HOOKS_ICON =
        IconLoader.getIcon("/icons/repairHooks.svg", CompilationCheckerPanel.class);

    private final Project project;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JBLabel statusLabel = new JBLabel(" ");
    private final Runnable serviceListener = this::onServiceUpdate;
    private String hookStatusText = " ";

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
        group.add(new RepairHooksAction());
        group.addSeparator();
        group.add(new ForceNextPushAction());
        group.addSeparator();
        group.add(new GenerateCommitMsgAction());
        group.addSeparator();
        group.add(new ReportAction());
        var toolbar = ActionManager.getInstance()
            .createActionToolbar("CompilationCheckerToolbar", group, true);
        toolbar.setTargetComponent(this);

        JPanel header = new JPanel(new BorderLayout());
        header.add(toolbar.getComponent(), BorderLayout.NORTH);
        header.add(createSettingsPanel(), BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

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
        if (errors.isEmpty()) {
            statusLabel.setText(hookStatusText);
        } else {
            String hookSuffix = hookStatusText.isBlank() ? "" : " - " + hookStatusText;
            statusLabel.setText(errors.size() + " error(s) from last check" + hookSuffix);
        }
    }

    private JComponent createSettingsPanel() {
        JBCheckBox strictGuard = new JBCheckBox("Enable strict A/B dependency guard");
        strictGuard.setSelected(PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project));
        strictGuard.setToolTipText(
            "Validates committed HEAD in a clean temporary worktree only when local " +
                "source/build changes could mask pushed-snapshot failures.");

        JBCheckBox stashFallback = new JBCheckBox("Allow stash fallback when snapshot worktree is unavailable");
        stashFallback.setSelected(PrePushCheckerSettings.isStashSnapshotFallbackEnabled(project));
        stashFallback.setEnabled(strictGuard.isSelected());
        stashFallback.setToolTipText(
            "Advanced fallback: temporarily stashes local changes, compiles HEAD, then restores them. " +
                "Disabled by default because detached worktree validation is safer.");

        JBCheckBox disableFallback = new JBCheckBox("Disable build-tool fallback (skip check when IntelliJ is not running)");
        disableFallback.setSelected(PrePushCheckerSettings.isBuildToolFallbackDisabled(project));
        disableFallback.setToolTipText(
            "When enabled, pushes from terminal/Sublime Merge/etc. are skipped rather than " +
            "falling back to Maven/Gradle if IntelliJ is not open. " +
            "Prevents false-positive compile errors from annotation processors " +
            "(e.g. Lombok @Builder/@Getter/@Setter) that Maven cannot resolve without " +
            "IntelliJ's incremental annotation-processing setup.");

        JBCheckBox rebasePrecheck = new JBCheckBox("Pre-compile rebase check (fetch and prompt if remote is ahead)");
        rebasePrecheck.setSelected(PrePushCheckerSettings.isRebasePrecheckEnabled(project));
        rebasePrecheck.setToolTipText(
            "Before compiling, run git fetch and check if any push root has incoming commits. " +
            "If so, prompt to rebase first so compilation runs against the integrated tree. " +
            "Off by default because every push performs a network fetch.");

        strictGuard.addActionListener(event -> {
            boolean selected = strictGuard.isSelected();
            PrePushCheckerSettings.setStrictSnapshotGuardEnabled(project, selected);
            stashFallback.setEnabled(selected);
        });
        stashFallback.addActionListener(event ->
            PrePushCheckerSettings.setStashSnapshotFallbackEnabled(project, stashFallback.isSelected()));
        disableFallback.addActionListener(event -> {
            PrePushCheckerSettings.setBuildToolFallbackDisabled(project, disableFallback.isSelected());
            PrePushCheckerSettings.syncSettingsFile(project);
        });
        rebasePrecheck.addActionListener(event ->
            PrePushCheckerSettings.setRebasePrecheckEnabled(project, rebasePrecheck.isSelected()));

        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(strictGuard);
        options.add(Box.createVerticalStrut(2));
        options.add(stashFallback);
        options.add(Box.createVerticalStrut(2));
        options.add(disableFallback);
        options.add(Box.createVerticalStrut(2));
        options.add(rebasePrecheck);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Settings"),
            BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        panel.add(options, BorderLayout.WEST);
        return panel;
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

    private final class ForceNextPushAction extends AnAction implements Toggleable {

        ForceNextPushAction() {
            super("Force Next Push",
                "Skip compilation check for the next push attempt (single-use, expires in 1 hour)",
                AllIcons.Debugger.MuteBreakpoints);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Presentation p = e.getPresentation();
            boolean active = PrePushCheckerSettings.isForcePushBypassActive(project);
            Toggleable.setSelected(p, active);
            p.setText(active ? "Force Next Push (Active)" : "Force Next Push");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            boolean currentlyActive = PrePushCheckerSettings.isForcePushBypassActive(project);
            if (currentlyActive) {
                PrePushCheckerSettings.clearForcePushBypass(project);
            } else {
                PrePushCheckerSettings.setForcePushBypass(project);
            }
        }
    }

    private final class RepairHooksAction extends AnAction {

        RepairHooksAction() {
            super("Recheck / Repair Git Hooks",
                "Verify and repair the terminal pre-push hook used by this plugin",
                REPAIR_HOOKS_ICON);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Rechecking Git Hooks", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        GitHookInstaller.HookRepairResult result = GitHookInstaller.repair(project);
                        String message = result.statusText();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (project.isDisposed()) return;
                            hookStatusText = message;
                            onServiceUpdate();
                            if (!result.isSuccess()) {
                                NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Pre-Push Compilation Checker")
                                    .createNotification("Git hook repair failed", message, NotificationType.WARNING)
                                    .notify(project);
                            }
                        }, ModalityState.defaultModalityState());
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

    private final class GenerateCommitMsgAction extends AnAction {

        GenerateCommitMsgAction() {
            super("Generate Commit Message (Pre-Push Checker)",
                "Pre-Push Checker: Generate a commit message from staged changes using the configured AI provider",
                AllIcons.Actions.Lightning);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            com.intellij.openapi.progress.ProgressManager.getInstance().run(
                new com.intellij.openapi.progress.Task.Backgroundable(
                        project, "Generating Commit Message with AI", false) {
                    @Override
                    public void run(
                            @NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        indicator.setText("Contacting AI provider…");
                        try {
                            String message =
                                CommitMessageGeneratorService.getInstance(project).generate();
                            String finalMessage = message;
                            com.intellij.openapi.application.ApplicationManager
                                .getApplication().invokeLater(() -> {
                                    if (project.isDisposed()) return;
                                    com.intellij.openapi.ui.Messages.showMultilineInputDialog(
                                        project,
                                        "Generated commit message (copy into your commit dialog):",
                                        "Generated Commit Message",
                                        finalMessage,
                                        AllIcons.Actions.Lightning,
                                        null);
                                }, com.intellij.openapi.application.ModalityState.defaultModalityState());
                        } catch (Exception ex) {
                            com.intellij.openapi.application.ApplicationManager
                                .getApplication().invokeLater(() -> {
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
                                }, com.intellij.openapi.application.ModalityState.defaultModalityState());
                        }
                    }
                });
        }
    }
}
