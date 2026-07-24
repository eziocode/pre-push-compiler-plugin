package com.github.prepushchecker;

import com.github.prepushchecker.commitgen.CommitMessageGeneratorService;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.vcs.changes.ChangeListManager;
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

        // ── Clipboard SHA settings ────────────────────────────────────────────
        JBCheckBox copySha = new JBCheckBox("Copy commit SHA to clipboard automatically");
        copySha.setSelected(PrePushCheckerSettings.isCopyCommitShaEnabled(project));
        copySha.setToolTipText("Copies the HEAD commit SHA to the clipboard after the chosen event.");

        JRadioButton shaFull  = new JRadioButton("Full SHA (40 chars)");
        JRadioButton shaShort = new JRadioButton("Short SHA (7 chars)");
        ButtonGroup shaFormatGroup = new ButtonGroup();
        shaFormatGroup.add(shaFull);
        shaFormatGroup.add(shaShort);
        boolean isShort = PrePushCheckerSettings.getCopyCommitShaFormat(project)
            == PrePushCheckerSettings.ShaFormat.SHORT;
        shaFull.setSelected(!isShort);
        shaShort.setSelected(isShort);

        JRadioButton triggerPush   = new JRadioButton("After push");
        JRadioButton triggerCommit = new JRadioButton("After commit");
        ButtonGroup triggerGroup = new ButtonGroup();
        triggerGroup.add(triggerPush);
        triggerGroup.add(triggerCommit);
        boolean isAfterCommit = PrePushCheckerSettings.getCopyCommitShaTrigger(project)
            == PrePushCheckerSettings.ShaTrigger.AFTER_COMMIT;
        triggerPush.setSelected(!isAfterCommit);
        triggerCommit.setSelected(isAfterCommit);

        // Build two indented sub-rows:
        //   Row 1: "Format: ● Full  ○ Short"
        //   Row 2: "Copy on: ● After push  ○ After commit"
        JPanel shaFormatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        shaFormatRow.add(new JBLabel("Format:"));
        shaFormatRow.add(shaFull);
        shaFormatRow.add(shaShort);

        JPanel shaCopyOnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        shaCopyOnRow.add(new JBLabel("Copy on:"));
        shaCopyOnRow.add(triggerPush);
        shaCopyOnRow.add(triggerCommit);

        JPanel shaSubPanel = new JPanel();
        shaSubPanel.setLayout(new BoxLayout(shaSubPanel, BoxLayout.Y_AXIS));
        shaSubPanel.add(shaFormatRow);
        shaSubPanel.add(shaCopyOnRow);
        shaSubPanel.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
        shaSubPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        // Wire up enable/disable
        Runnable updateShaSubPanel = () -> {
            boolean enabled = copySha.isSelected();
            shaFull.setEnabled(enabled);
            shaShort.setEnabled(enabled);
            triggerPush.setEnabled(enabled);
            triggerCommit.setEnabled(enabled);
        };
        updateShaSubPanel.run();

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
        copySha.addActionListener(event -> {
            PrePushCheckerSettings.setCopyCommitShaEnabled(project, copySha.isSelected());
            updateShaSubPanel.run();
        });
        shaFull.addActionListener(event ->
            PrePushCheckerSettings.setCopyCommitShaFormat(project, PrePushCheckerSettings.ShaFormat.FULL));
        shaShort.addActionListener(event ->
            PrePushCheckerSettings.setCopyCommitShaFormat(project, PrePushCheckerSettings.ShaFormat.SHORT));
        triggerPush.addActionListener(event ->
            PrePushCheckerSettings.setCopyCommitShaTrigger(project, PrePushCheckerSettings.ShaTrigger.AFTER_PUSH));
        triggerCommit.addActionListener(event ->
            PrePushCheckerSettings.setCopyCommitShaTrigger(project, PrePushCheckerSettings.ShaTrigger.AFTER_COMMIT));

        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(strictGuard);
        options.add(Box.createVerticalStrut(2));
        options.add(stashFallback);
        options.add(Box.createVerticalStrut(2));
        options.add(disableFallback);
        options.add(Box.createVerticalStrut(2));
        options.add(Box.createVerticalStrut(4));
        options.add(copySha);
        options.add(shaSubPanel);

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
                        IdeCompilationRunner.runWithRecovery(
                            project,
                            indicator,
                            compiler,
                            notification -> compiler.make(
                                compiler.createProjectCompileScope(project), notification));
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
                        IdeCompilationRunner.runOnce(
                            project,
                            indicator,
                            compiler::rebuild);
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
        public void update(@NotNull AnActionEvent e) {
            boolean hasChanges = !ChangeListManager.getInstance(project).getAllChanges().isEmpty();
            e.getPresentation().setEnabled(hasChanges);
            e.getPresentation().setDescription(hasChanges
                ? "Pre-Push Checker: Generate a commit message from staged changes using the configured AI provider"
                : "Pre-Push Checker: No changes detected — make or stage some changes first");
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
