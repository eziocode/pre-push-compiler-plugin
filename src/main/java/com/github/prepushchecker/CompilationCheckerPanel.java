package com.github.prepushchecker;

import com.github.prepushchecker.commitgen.CommitMessageGeneratorService;
import com.intellij.ide.util.PropertiesComponent;
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
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ide.util.gotoByName.ChooseByNamePopup;
import com.intellij.ide.util.gotoByName.GotoFileModel;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.PsiFile;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.ContextHelpLabel;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.OnOffButton;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

final class CompilationCheckerPanel extends JPanel implements Disposable {

    private static final Icon REPAIR_HOOKS_ICON =
        IconLoader.getIcon("/icons/repairHooks.svg", CompilationCheckerPanel.class);
    private static final int COUNTDOWN_REFRESH_MS = 30_000;

    private final Project project;
    private final @Nullable ToolWindow toolWindow;
    private final @Nullable Icon baseToolWindowIcon;
    private final java.util.concurrent.atomic.AtomicBoolean runCheckInProgress =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final com.intellij.ui.CollectionListModel<String> listModel = new com.intellij.ui.CollectionListModel<>();
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JBLabel updatedLabel = new JBLabel("");
    private final Runnable serviceListener = this::onServiceUpdate;
    private final Runnable bypassListener = this::onBypassUpdate;
    private final Runnable smartBuildListener = this::updateStatus;

    // Bypass bar
    private final JPanel bypassBar = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    private final JBLabel bypassTitle = new JBLabel("Bypass push check");
    private final JBLabel bypassSubtitle = new JBLabel(" ");
    private final OnOffButton bypassSwitch = new OnOffButton();
    private final Timer countdownTimer = new Timer(COUNTDOWN_REFRESH_MS, e -> onBypassUpdate());

    private String hookStatusText = "";

    CompilationCheckerPanel(@NotNull Project project, @Nullable ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project;
        this.toolWindow = toolWindow;
        this.baseToolWindowIcon = toolWindow == null ? null : toolWindow.getIcon();

        CompilationErrorService.getInstance(project).addListener(serviceListener);
        BypassController.getInstance(project).addListener(bypassListener);
        SmartBuildService.getInstance(project).addListener(smartBuildListener);

        // ── Toolbar ──────────────────────────────────────────────────────────
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RunCheckAction());
        group.add(new RebuildCachesAction());
        group.add(new ClearResultsAction());
        group.addSeparator();
        group.add(new RepairHooksAction());
        group.add(new GenerateCommitMsgAction());
        group.addSeparator();
        group.add(new PushStatsAction());
        group.add(createLayersGroup());
        group.add(new OpenSettingsAction());
        group.add(new ReportAction());
        var toolbar = ActionManager.getInstance()
            .createActionToolbar("CompilationCheckerToolbar", group, true);
        toolbar.setTargetComponent(this);

        // BorderLayout (not BoxLayout) so neither row is stretched or squashed vertically.
        JPanel header = new JPanel(new BorderLayout());
        JComponent toolbarComponent = toolbar.getComponent();
        toolbarComponent.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        header.add(toolbarComponent, BorderLayout.NORTH);
        header.add(createBypassBar(), BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        // Settings live in Settings → Tools → Pre-Push Checker; this tab shows results only.
        add(createResultsPanel(), BorderLayout.CENTER);

        onServiceUpdate();
        onBypassUpdate();
    }

    // ── Disposable ────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        countdownTimer.stop();
        CompilationErrorService.getInstance(project).removeListener(serviceListener);
        BypassController.getInstance(project).removeListener(bypassListener);
        SmartBuildService.getInstance(project).removeListener(smartBuildListener);
    }

    // ── Bypass switch ─────────────────────────────────────────────────────────

    private JComponent createBypassBar() {
        bypassTitle.setFont(JBUI.Fonts.label().asBold());
        bypassSubtitle.setFont(JBUI.Fonts.smallFont());
        bypassSubtitle.setForeground(UIUtil.getContextHelpForeground());

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(bypassTitle);
        text.add(bypassSubtitle);

        bypassSwitch.setToolTipText("Skip the compilation check for every push until turned off. "
            + "Turns itself off after 1 hour and whenever the IDE restarts.");
        bypassSwitch.addActionListener(e -> {
            BypassController controller = BypassController.getInstance(project);
            if (bypassSwitch.isSelected()) {
                controller.enable();
            } else {
                controller.disable();
            }
        });
        JPanel switchHolder = new JPanel(new GridBagLayout());
        switchHolder.setOpaque(false);
        switchHolder.add(bypassSwitch);

        // Let the text shrink/clip instead of pushing the switch out of the bar.
        text.setMinimumSize(new Dimension(0, 0));
        bypassTitle.setMinimumSize(new Dimension(0, 0));
        bypassSubtitle.setMinimumSize(new Dimension(0, 0));
        bypassBar.add(text, BorderLayout.CENTER);
        bypassBar.add(switchHolder, BorderLayout.EAST);
        return bypassBar;
    }

    private void onBypassUpdate() {
        BypassController controller = BypassController.getInstance(project);
        boolean active = controller.isActive();
        bypassSwitch.setSelected(active);
        bypassBar.setOpaque(active);
        if (active) {
            bypassBar.setBackground(JBUI.CurrentTheme.Banner.WARNING_BACKGROUND);
            bypassBar.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR),
                JBUI.Borders.empty(6, 10)));
            bypassTitle.setIcon(AllIcons.General.Warning);
            bypassSubtitle.setText("Pushes skip compilation \u00b7 auto-off in "
                + formatRemaining(controller.remainingMillis()));
            if (!countdownTimer.isRunning()) countdownTimer.start();
        } else {
            bypassBar.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(6, 10)));
            bypassTitle.setIcon(null);
            bypassSubtitle.setText("Off \u00b7 every push is compiled first");
            countdownTimer.stop();
        }
        if (toolWindow != null && baseToolWindowIcon != null) {
            // Corner badge on top of the original icon; a full-size overlay would hide it.
            toolWindow.setIcon(active
                ? LayeredIcon.layeredIcon(new Icon[] {baseToolWindowIcon, AllIcons.General.WarningDecorator})
                : baseToolWindowIcon);
        }
        bypassBar.revalidate();
        bypassBar.repaint();
        updateStatus();
    }

    static @NotNull String formatRemaining(long millis) {
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        return minutes + " min";
    }

    // ── Results ───────────────────────────────────────────────────────────────

    private JComponent createResultsPanel() {
        JBList<String> errorList = new JBList<>(listModel);
        errorList.setCellRenderer(new CompilationEntryRenderer());
        errorList.getEmptyText().setText("No compilation errors");
        errorList.getEmptyText().appendLine("Run a check or push to populate this list");

        // Double-click or Enter → navigate to file
        errorList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    CompilationEntryRenderer.navigateTo(project, errorList.getSelectedValue());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) { maybeShowErrorMenu(e); }

            @Override
            public void mouseReleased(MouseEvent e) { maybeShowErrorMenu(e); }

            private void maybeShowErrorMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int index = errorList.locationToIndex(e.getPoint());
                if (index < 0) return;
                errorList.setSelectedIndex(index);
                String diagnostic = errorList.getSelectedValue();
                JPopupMenu menu = new JPopupMenu();
                if (DiagnosticPathMatcher.canIgnoreDiagnostic(project, diagnostic)) {
                    JMenuItem ignore = new JMenuItem("Ignore File");
                    ignore.addActionListener(event -> ignoreDiagnosticFile(diagnostic));
                    menu.add(ignore);
                }
                addLayerFixes(menu, diagnostic);
                if (menu.getComponentCount() > 0) menu.show(errorList, e.getX(), e.getY());
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

        JBScrollPane scroll = new JBScrollPane(errorList);
        scroll.setBorder(JBUI.Borders.empty());

        updatedLabel.setForeground(UIUtil.getContextHelpForeground());
        updatedLabel.setFont(JBUI.Fonts.smallFont());
        JPanel footer = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        footer.setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(3, 8)));
        statusLabel.setMinimumSize(new Dimension(0, 0)); // clip rather than overlap the timestamp
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(updatedLabel, BorderLayout.EAST);

        JPanel results = new JPanel(new BorderLayout());
        results.add(scroll, BorderLayout.CENTER);
        results.add(footer, BorderLayout.SOUTH);
        return results;
    }

    private void onServiceUpdate() {
        List<String> errors = CompilationErrorService.getInstance(project).getErrors();
        // One replace event instead of a clear plus one add event per row.
        listModel.replaceAll(errors);
        updateStatus(errors);
    }

    private void updateStatus() {
        updateStatus(CompilationErrorService.getInstance(project).getErrors());
    }

    private void updateStatus(List<String> errors) {
        CompilationErrorService service = CompilationErrorService.getInstance(project);
        long infra = errors.stream().filter(CompilationEntryRenderer::isInfrastructureMessage).count();
        String text;
        Icon icon;
        if (errors.isEmpty()) {
            text = "No errors";
            icon = AllIcons.General.InspectionsOK;
        } else if (infra == errors.size()) {
            text = "Check could not complete";
            icon = AllIcons.General.Warning;
        } else {
            long count = errors.size() - infra;
            text = count + (count == 1 ? " error" : " errors") + " from last check";
            icon = AllIcons.General.Error;
        }
        if (BypassController.getInstance(project).isActive()) {
            text += " \u00b7 bypass on";
        }
        String smartText = smartBuildStatusText(SmartBuildService.getInstance(project).getStatus());
        if (!smartText.isBlank()) {
            text += " \u00b7 " + smartText;
        }
        if (!hookStatusText.isBlank()) {
            text += " \u00b7 " + hookStatusText;
        }
        statusLabel.setIcon(icon);
        statusLabel.setText(text);
        statusLabel.setToolTipText(hookStatusText.isBlank() ? null : hookStatusText);

        long changed = service.getLastChangedMillis();
        updatedLabel.setText(changed == 0 ? ""
            : "Updated " + new SimpleDateFormat("HH:mm").format(new Date(changed)));
    }

    static @NotNull String smartBuildStatusText(@NotNull SmartBuildService.Status status) {
        String branch = status.branch().isBlank() ? "" : " (" + status.branch() + ")";
        return switch (status.phase()) {
            case DISABLED, IDLE -> "";
            case UP_TO_DATE -> "build up to date" + branch;
            case STALE -> "sources changed";
            case WAITING -> "smart build " + status.detail();
            case BUILDING -> "smart build running" + branch;
        };
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    /** Repository root holding the rules file, else the first project root. */
    private @Nullable String layerRepositoryRoot() {
        java.util.Set<String> roots = DiagnosticPathMatcher.roots(project);
        for (String root : roots) {
            if (CrossLayerImportCheck.hasRulesFile(root)) return root;
        }
        return roots.isEmpty() ? null : roots.iterator().next();
    }

    /** Quick fixes for jar diagnostics of the cross-layer check: they rewrite {@code .prepush-layers}. */
    private void addLayerFixes(JPopupMenu menu, @Nullable String diagnostic) {
        LayerDiagnostic parsed = diagnostic == null ? null : LayerDiagnostic.parse(diagnostic);
        String root = parsed == null ? null : layerRepositoryRoot();
        if (root == null || !CrossLayerImportCheck.hasRulesFile(root)) return;
        if (menu.getComponentCount() > 0) menu.addSeparator();
        for (String jar : parsed.jars()) {
            boolean forbidden = parsed.kind() == LayerDiagnostic.Kind.FORBIDDEN_JAR;
            JMenuItem fix = new JMenuItem((forbidden ? "Check " + jar + " for '" : "Allow " + jar + " in '")
                + parsed.layer() + "'");
            fix.addActionListener(event -> applyLayerFix(root, text -> {
                if (!forbidden) return LayerRulesEdit.addLib(text, parsed.layer(), jar);
                List<String> wide = LayerRulesEdit.wildcardForbidsMatching(text, parsed.layer(), jar);
                if (!wide.isEmpty() && Messages.showYesNoDialog(project,
                        "This also re-allows other jars matching '" + String.join("', '", wide) + "'. Continue?",
                        "Layer Rules", null) != Messages.YES) {
                    return null;
                }
                return LayerRulesEdit.allowJar(text, parsed.layer(), jar);
            }));
            menu.add(fix);
        }
        JMenuItem open = new JMenuItem("Open Layers…");
        open.addActionListener(event -> editLayerRules());
        menu.add(open);
    }

    /** {@code edit} returns the new rules text, or {@code null} when the user backed out. */
    private void applyLayerFix(String root, java.util.function.UnaryOperator<String> edit) {
        VirtualFile rootFile = LocalFileSystem.getInstance().findFileByPath(root);
        VirtualFile rules = rootFile == null ? null : rootFile.findChild(LayerRules.FILE_NAME);
        if (rules == null) return;
        String text;
        try {
            com.intellij.openapi.editor.Document document =
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(rules);
            text = document != null ? document.getText() : com.intellij.openapi.vfs.VfsUtilCore.loadText(rules);
        } catch (java.io.IOException e) {
            Messages.showErrorDialog(project, e.getMessage(), "Layer Rules");
            return;
        }
        if (LayerRulesEdit.hasOwnComments(text) && Messages.showYesNoDialog(project,
                "Comments in " + LayerRules.FILE_NAME + " will be removed. Continue?", "Layer Rules", null)
                != Messages.YES) {
            return;
        }
        try {
            String edited = edit.apply(text);
            if (edited != null) writeRulesFile(rootFile, edited);
        } catch (IllegalArgumentException e) {
            Messages.showErrorDialog(project, e.getMessage(), "Layer Rules");
        }
    }

    private void runLayerCheckNow() {
        String root = layerRepositoryRoot();
        if (root == null || !CrossLayerImportCheck.hasRulesFile(root)) {
            Messages.showInfoMessage(project,
                "No " + LayerRules.FILE_NAME + " found at the repository root. "
                    + "Use \"Generate from build.xml…\" or write one by hand.",
                "Cross-Layer Import Check");
            return;
        }
        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "Checking Cross-Layer Imports", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    List<String> raw = CrossLayerImportCheck.scanAll(project, root);
                    List<String> errors = DiagnosticPathMatcher.filterIgnored(project, raw);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        // Replace earlier layer results (a clean run clears them); keep compile errors.
                        CompilationErrorService service = CompilationErrorService.getInstance(project);
                        List<String> merged = new java.util.ArrayList<>();
                        for (String entry : service.getErrors()) {
                            if (!CrossLayerImportCheck.isLayerDiagnostic(entry)) merged.add(entry);
                        }
                        merged.addAll(raw);
                        service.setErrors(merged);
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Pre-Push Compilation Checker")
                            .createNotification("Cross-layer import check",
                                errors.isEmpty() ? "No layer violations at HEAD."
                                    : errors.size() + " layer violation(s) at HEAD.",
                                errors.isEmpty() ? NotificationType.INFORMATION : NotificationType.WARNING)
                            .notify(project);
                    }, ModalityState.defaultModalityState());
                }
            });
    }

    private void generateLayerRules() {
        String root = layerRepositoryRoot();
        if (root == null) return;
        VirtualFile rootFile = LocalFileSystem.getInstance().findFileByPath(root);
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory
            .createSingleFileDescriptor("xml")
            .withTitle("Select Ant Build File")
            .withDescription("One layer is generated per target containing <javac>, in depends order");
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, rootFile);
        if (chosen == null) return;

        String text;
        try {
            AntLayerImporter.Result result = AntLayerImporter.importFile(
                java.nio.file.Path.of(chosen.getPath()), java.nio.file.Path.of(root));
            text = AntLayerImporter.render(result);
        } catch (java.io.IOException e) {
            Messages.showErrorDialog(project, e.getMessage(), "Generate Layer Rules");
            return;
        }

        if (rootFile == null) return;
        JTextArea preview = new JTextArea(text, 24, 80);
        preview.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, preview.getFont().getSize()));
        boolean exists = rootFile.findChild(LayerRules.FILE_NAME) != null;
        DialogWrapper dialog = new DialogWrapper(project) {
            {
                setTitle((exists ? "Overwrite " : "Write ") + LayerRules.FILE_NAME);
                init();
            }

            @Override
            protected JComponent createCenterPanel() {
                return new JBScrollPane(preview);
            }
        };
        if (!dialog.showAndGet()) return;
        writeRulesFile(rootFile, preview.getText());
    }

    /**
     * Opens the Layers dialog over IntelliJ's modules and jars; OK writes the rules file.
     * Modules and jars are read in the background: a large project has thousands of library roots.
     */
    private void editLayerRules() {
        String root = layerRepositoryRoot();
        if (root == null) return;
        VirtualFile rootFile = LocalFileSystem.getInstance().findFileByPath(root);
        if (rootFile == null) return;
        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "Reading Modules and Jars", true) {
                private IdeProjectLayers.Snapshot snapshot;
                private List<LayerDraft> initial = new java.util.ArrayList<>();
                private final List<String> fileScans = new java.util.ArrayList<>();
                private final List<String> suggestedScans = new java.util.ArrayList<>();

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    snapshot = IdeProjectLayers.read(project, root);
                    java.nio.file.Path existing = java.nio.file.Path.of(root, LayerRules.FILE_NAME);
                    if (java.nio.file.Files.isRegularFile(existing)) {
                        try {
                            LayerRules rules = LayerRulesParser.parse(
                                java.nio.file.Files.readString(existing, StandardCharsets.UTF_8)).rules();
                            if (rules != null) {
                                initial = LayerDraft.fromRules(rules);
                                rules.scans().forEach(pattern -> fileScans.add(pattern.source()));
                            }
                        } catch (java.io.IOException ignored) {
                            // Start empty; "Reload file" in the dialog reports the problem.
                        }
                    }
                    if (fileScans.isEmpty()) {
                        indicator.setText("Finding jar locations");
                        for (ScanDetector.Suggestion suggestion
                                : ScanDetector.detect(java.nio.file.Path.of(root), snapshot.jars())) {
                            suggestedScans.add(suggestion.pattern());
                        }
                    }
                }

                @Override
                public void onSuccess() {
                    if (project.isDisposed() || snapshot == null) return;
                    LayersEditorDialog dialog = new LayersEditorDialog(project, root, snapshot, initial,
                        fileScans, suggestedScans);
                    if (dialog.showAndGet()) writeRulesFile(rootFile, dialog.getRulesText());
                }
            });
    }

    private void writeRulesFile(VirtualFile rootFile, String content) {
        try {
            LayerRulesFile.write(project, rootFile, content);
        } catch (java.io.IOException e) {
            Messages.showErrorDialog(project, e.getMessage(), "Layer Rules");
        }
    }

    private void ignoreDiagnosticFile(String diagnostic) {
        String path = CompilationEntryRenderer.extractPath(diagnostic);
        if (path != null) IgnoredFilesEditor.addOrWarn(project, path);
    }

    // ── Toolbar actions ───────────────────────────────────────────────────────

    /** Opens the Push Stats dialog. */
    private final class PushStatsAction extends AnAction {
        PushStatsAction() {
            super("Push Stats\u2026", "Show how many pushes were checked and blocked",
                AllIcons.Vcs.History);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            new PushStatsDialog(project).show();
        }
    }

    private final class ClearResultsAction extends AnAction {

        ClearResultsAction() {
            super("Clear Results", "Clear the error list", AllIcons.Actions.GC);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(
                !CompilationErrorService.getInstance(project).getRawErrors().isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            CompilationErrorService.getInstance(project).clearErrors();
        }
    }

    /** Layers menu: the cross-layer check's operations (its on/off switch is in Settings). */
    private DefaultActionGroup createLayersGroup() {
        DefaultActionGroup layers = new DefaultActionGroup("Layers", true);
        layers.getTemplatePresentation().setIcon(AllIcons.Actions.GroupByModule);
        layers.getTemplatePresentation().setDescription("Cross-layer import check: edit layers, run, generate");
        layers.add(new SimpleAction("Edit Layers\u2026", "Order modules and jars as the build compiles them",
            this::editLayerRules));
        layers.add(new SimpleAction("Run Layer Check", "Check every file at HEAD against the layer rules",
            this::runLayerCheckNow) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(PrePushCheckerSettings.isLayerCheckEnabled(project));
            }
        });
        layers.add(new SimpleAction("Generate from build.xml\u2026", "One layer per Ant target with <javac>",
            this::generateLayerRules));
        return layers;
    }

    private static class SimpleAction extends AnAction {
        private final Runnable run;

        SimpleAction(String text, String description, Runnable run) {
            super(text, description, null);
            this.run = run;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            run.run();
        }
    }

    private final class OpenSettingsAction extends AnAction {
        OpenSettingsAction() {
            super("Settings\u2026", "Open Pre-Push Checker settings", AllIcons.General.Settings);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "Pre-Push Checker");
        }
    }

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
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!runCheckInProgress.get());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (!runCheckInProgress.compareAndSet(false, true)) return;
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

                    @Override
                    public void onFinished() {
                        runCheckInProgress.set(false);
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
            "https://github.com/eziocode/pre-push-compiler-plugin/issues/new";

        ReportAction() {
            // Not a warning glyph: it sat next to the bypass warning and read as a status icon.
            super("Report Issue", "Open a new GitHub issue for this plugin",
                AllIcons.Vcs.Vendors.Github);
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
                        project, "Generating Commit Message with AI", true) {
                    @Override
                    public void run(
                            @NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        indicator.setText("Contacting AI provider…");
                        try {
                            String message =
                                CommitMessageGeneratorService.getInstance(project).generate();
                            indicator.checkCanceled();
                            String finalMessage = message;
                            com.intellij.openapi.application.ApplicationManager
                                .getApplication().invokeLater(() -> {
                                    if (project.isDisposed() || indicator.isCanceled()) return;
                                    com.intellij.openapi.ui.Messages.showMultilineInputDialog(
                                        project,
                                        "Generated commit message (copy into your commit dialog):",
                                        "Generated Commit Message",
                                        finalMessage,
                                        AllIcons.Actions.Lightning,
                                        null);
                                }, com.intellij.openapi.application.ModalityState.defaultModalityState());
                        } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) {
                            throw cancelled;
                        } catch (Exception ex) {
                            if (indicator.isCanceled()) return;
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
