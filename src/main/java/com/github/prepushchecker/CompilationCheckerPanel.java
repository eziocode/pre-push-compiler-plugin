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
import com.intellij.openapi.compiler.CompilerManager;
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
import com.intellij.ui.BadgeIconSupplier;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.ContextHelpLabel;
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
    private static final String SETTINGS_VISIBLE_KEY = "prepushchecker.ui.settingsVisible";
    private static final String SPLITTER_PROPORTION_KEY = "prepushchecker.ui.splitter";
    private static final int COUNTDOWN_REFRESH_MS = 30_000;

    private final Project project;
    private final @Nullable ToolWindow toolWindow;
    private final @Nullable Icon baseToolWindowIcon;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JBLabel updatedLabel = new JBLabel("");
    private final CheckBoxList<String> ignoredFilesList = new IgnoredFilesList();
    private final Runnable serviceListener = this::onServiceUpdate;
    private final Runnable bypassListener = this::onBypassUpdate;
    private final OnePixelSplitter splitter =
        new OnePixelSplitter(true, SPLITTER_PROPORTION_KEY, 0.45f);
    private final JComponent settingsComponent;

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

        // ── Toolbar ──────────────────────────────────────────────────────────
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RunCheckAction());
        group.add(new RebuildCachesAction());
        group.add(new ClearResultsAction());
        group.addSeparator();
        group.add(new RepairHooksAction());
        group.add(new GenerateCommitMsgAction());
        group.addSeparator();
        group.add(new ToggleSettingsAction());
        group.add(new ReportAction());
        var toolbar = ActionManager.getInstance()
            .createActionToolbar("CompilationCheckerToolbar", group, true);
        toolbar.setTargetComponent(this);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JComponent toolbarComponent = toolbar.getComponent();
        toolbarComponent.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        toolbarComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(toolbarComponent);
        JComponent bar = createBypassBar();
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(bar);
        add(header, BorderLayout.NORTH);

        // ── Settings / results split ─────────────────────────────────────────
        settingsComponent = createSettingsPanel();
        splitter.setFirstComponent(settingsComponent);
        splitter.setSecondComponent(createResultsPanel());
        add(splitter, BorderLayout.CENTER);
        applySettingsVisibility();

        onServiceUpdate();
        onBypassUpdate();
    }

    // ── Disposable ────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        countdownTimer.stop();
        CompilationErrorService.getInstance(project).removeListener(serviceListener);
        BypassController.getInstance(project).removeListener(bypassListener);
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

        bypassBar.add(text, BorderLayout.CENTER);
        bypassBar.add(switchHolder, BorderLayout.EAST);
        bypassBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
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
            toolWindow.setIcon(active
                ? new BadgeIconSupplier(baseToolWindowIcon).getWarningIcon()
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
                if (!DiagnosticPathMatcher.canIgnoreDiagnostic(project, diagnostic)) return;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem ignore = new JMenuItem("Ignore File");
                ignore.addActionListener(event -> ignoreDiagnosticFile(diagnostic));
                menu.add(ignore);
                menu.show(errorList, e.getX(), e.getY());
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
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(updatedLabel, BorderLayout.EAST);

        JPanel results = new JPanel(new BorderLayout());
        results.add(scroll, BorderLayout.CENTER);
        results.add(footer, BorderLayout.SOUTH);
        return results;
    }

    private void onServiceUpdate() {
        List<String> errors = CompilationErrorService.getInstance(project).getErrors();
        listModel.clear();
        errors.forEach(listModel::addElement);
        updateStatus();
    }

    private void updateStatus() {
        CompilationErrorService service = CompilationErrorService.getInstance(project);
        List<String> errors = service.getErrors();
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

    // ── Settings ──────────────────────────────────────────────────────────────

    private void applySettingsVisibility() {
        boolean visible = PropertiesComponent.getInstance().getBoolean(SETTINGS_VISIBLE_KEY, true);
        settingsComponent.setVisible(visible);
        splitter.revalidate();
        splitter.repaint();
    }

    private JComponent createSettingsPanel() {
        JBCheckBox strictGuard = new JBCheckBox("Strict A/B dependency guard");
        strictGuard.setSelected(PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project));

        JBCheckBox stashFallback = new JBCheckBox("Allow stash fallback");
        stashFallback.setSelected(PrePushCheckerSettings.isStashSnapshotFallbackEnabled(project));
        stashFallback.setEnabled(strictGuard.isSelected());

        JBCheckBox disableFallback = new JBCheckBox("Skip check when IDE is closed");
        disableFallback.setSelected(PrePushCheckerSettings.isBuildToolFallbackDisabled(project));

        // ── Clipboard SHA settings ────────────────────────────────────────────
        JBCheckBox copySha = new JBCheckBox("Copy commit SHA automatically");
        copySha.setSelected(PrePushCheckerSettings.isCopyCommitShaEnabled(project));

        JRadioButton shaFull  = new JRadioButton("Full");
        JRadioButton shaShort = new JRadioButton("Short (7)");
        shaFull.setToolTipText("Full 40-character SHA");
        shaShort.setToolTipText("Abbreviated 7-character SHA");
        ButtonGroup shaFormatGroup = new ButtonGroup();
        shaFormatGroup.add(shaFull);
        shaFormatGroup.add(shaShort);
        boolean isShort = PrePushCheckerSettings.getCopyCommitShaFormat(project)
            == PrePushCheckerSettings.ShaFormat.SHORT;
        shaFull.setSelected(!isShort);
        shaShort.setSelected(isShort);

        JRadioButton triggerPush   = new JRadioButton("Push");
        JRadioButton triggerCommit = new JRadioButton("Commit");
        ButtonGroup triggerGroup = new ButtonGroup();
        triggerGroup.add(triggerPush);
        triggerGroup.add(triggerCommit);
        boolean isAfterCommit = PrePushCheckerSettings.getCopyCommitShaTrigger(project)
            == PrePushCheckerSettings.ShaTrigger.AFTER_COMMIT;
        triggerPush.setSelected(!isAfterCommit);
        triggerCommit.setSelected(isAfterCommit);

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

        JPanel options = new WidthTrackingPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setBorder(JBUI.Borders.empty(4, 10, 8, 10));

        addRow(options, new TitledSeparator("Validation"), 0);
        addRow(options, withHelp(strictGuard,
            "Validates committed HEAD in a clean temporary worktree when local source or "
                + "build changes could hide failures in the pushed snapshot."), 0);
        addRow(options, withHelp(stashFallback,
            "Used only when a snapshot worktree cannot be created: stashes local changes, "
                + "compiles HEAD, then restores them. Off by default because worktree "
                + "validation is safer."), 20);
        addRow(options, withHelp(disableFallback,
            "Pushes from a terminal or another Git client are allowed without a check when "
                + "the IDE is not running, instead of falling back to Maven/Gradle. Avoids "
                + "false errors from annotation processors (e.g. Lombok) that the build tool "
                + "cannot resolve without the IDE's setup."), 0);

        addRow(options, new TitledSeparator("Commit SHA"), 0);
        addRow(options, withHelp(copySha,
            "Copies the HEAD commit SHA to the clipboard after the chosen event."), 0);
        addRow(options, labeledRow("Format:", shaFull, shaShort), 20);
        addRow(options, labeledRow("Copy after:", triggerPush, triggerCommit), 20);

        addRow(options, new TitledSeparator("Ignored Files"), 0);
        JBLabel ignoredHint = new JBLabel(
            "Checked files still compile, but their errors don't block pushes.");
        ignoredHint.setFont(JBUI.Fonts.smallFont());
        ignoredHint.setForeground(UIUtil.getContextHelpForeground());
        ignoredHint.setBorder(JBUI.Borders.emptyBottom(4));
        addRow(options, ignoredHint, 0);
        addRow(options, createIgnoredFilesPanel(), 0);

        JBScrollPane scroll = new JBScrollPane(options,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    private static void addRow(JPanel container, JComponent row, int indent) {
        JComponent wrapped = row;
        if (indent > 0) {
            JPanel holder = new JPanel(new BorderLayout());
            holder.setBorder(JBUI.Borders.emptyLeft(indent));
            holder.add(row, BorderLayout.CENTER);
            wrapped = holder;
        }
        wrapped.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!(row instanceof JPanel && ((JPanel) row).getClientProperty("grow") != null)) {
            wrapped.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrapped.getPreferredSize().height));
        }
        container.add(wrapped);
    }

    private static JComponent withHelp(JComponent component, String help) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(component);
        row.add(Box.createHorizontalStrut(JBUI.scale(4)));
        row.add(ContextHelpLabel.create(help));
        return row;
    }

    private static JComponent labeledRow(String label, JComponent... controls) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        JBLabel title = new JBLabel(label);
        title.setForeground(UIUtil.getContextHelpForeground());
        row.add(title);
        for (JComponent control : controls) row.add(control);
        return row;
    }

    private JComponent createIgnoredFilesPanel() {
        ignoredFilesList.getEmptyText().setText("No ignored files");
        ignoredFilesList.getEmptyText().appendLine("Add a file, or right-click an error \u2192 Ignore File");
        ignoredFilesList.setCheckBoxListListener((index, value) -> {
            String path = ignoredFilesList.getItemAt(index);
            if (path != null) IgnoredCompilationFiles.getInstance(project).setEnabled(path, value);
        });
        refreshIgnoredFilesRows();

        AnAction search = new AnAction("Search Project Files\u2026",
                "Find a project file by name to ignore", AllIcons.Actions.Search) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                openSearchPopup();
            }
        };

        JPanel decorated = ToolbarDecorator.createDecorator(ignoredFilesList)
            .setAddAction(button -> FileChooser.chooseFiles(
                projectScopedDescriptor(), project, projectRootForDialog(),
                files -> {
                    for (var file : files) addIgnoredFile(file.getPath());
                }))
            .setAddActionName("Add Files\u2026")
            .setRemoveAction(button -> {
                for (int index : ignoredFilesList.getSelectedIndices()) {
                    String path = ignoredFilesList.getItemAt(index);
                    if (path != null) IgnoredCompilationFiles.getInstance(project).remove(path);
                }
                refreshIgnoredFilesRows();
            })
            .addExtraAction(search)
            .disableUpDownActions()
            .setPreferredSize(new Dimension(0, JBUI.scale(130)))
            .createPanel();
        decorated.putClientProperty("grow", Boolean.TRUE);
        return decorated;
    }

    private FileChooserDescriptor projectScopedDescriptor() {
        List<VirtualFile> roots = new java.util.ArrayList<>();
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        for (String root : DiagnosticPathMatcher.roots(project)) {
            VirtualFile vf = lfs.findFileByPath(root);
            if (vf != null) roots.add(vf);
        }
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory
            .createMultipleFilesNoJarsDescriptor()
            .withTreeRootVisible(false)
            .withTitle("Select Project Files to Ignore")
            .withDescription("Compiler errors attributed to these files will not block pushes")
            .withFileFilter(vf -> vf.isDirectory() || PushValidationPaths.isCompilableSource(vf.getPath()));
        return roots.isEmpty() ? descriptor : descriptor.withRoots(roots);
    }

    private VirtualFile projectRootForDialog() {
        String basePath = project.getBasePath();
        return basePath == null ? null : LocalFileSystem.getInstance().findFileByPath(basePath);
    }

    private void openSearchPopup() {
        ChooseByNamePopup.createPopup(
            project, new GotoFileModel(project), (com.intellij.psi.PsiElement) null).invoke(
            new ChooseByNamePopup.Callback() {
                @Override
                public void onClose() {}

                @Override
                public void elementChosen(Object element) {
                    VirtualFile file = element instanceof PsiFile psiFile
                        ? psiFile.getVirtualFile()
                        : element instanceof VirtualFile vf ? vf : null;
                    if (file != null) addIgnoredFile(file.getPath());
                }
            },
            ModalityState.current(), false);
    }

    private void addIgnoredFile(String path) {
        if (IgnoredCompilationFiles.getInstance(project).add(path)) {
            refreshIgnoredFilesRows();
        } else {
            Messages.showWarningDialog(project,
                "\"" + path + "\" cannot be ignored \u2014 it must be a .java/.kt/.kts/.groovy/.scala "
                    + "file inside the project.",
                "Cannot Ignore File");
        }
    }

    private void refreshIgnoredFilesRows() {
        ignoredFilesList.clear();
        for (IgnoredCompilationFiles.Entry entry
                : IgnoredCompilationFiles.getInstance(project).list()) {
            ignoredFilesList.addItem(entry.path(), fileName(entry.path()), entry.enabled());
        }
    }

    private static String fileName(String path) {
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String parentDir(String path) {
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : null;
    }

    private void ignoreDiagnosticFile(String diagnostic) {
        String path = CompilationEntryRenderer.extractPath(diagnostic);
        if (path != null) addIgnoredFile(path);
    }

    /** Follows the viewport width so rows shrink with the tool window instead of clipping. */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Ignored-files list: file name as the label, parent folder dimmed, full path on hover. */
    private static final class IgnoredFilesList extends CheckBoxList<String> {
        @Override
        protected @Nullable String getSecondaryText(int index) {
            String path = getItemAt(index);
            return path == null ? null : parentDir(path);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int index = locationToIndex(event.getPoint());
            String path = index >= 0 ? getItemAt(index) : null;
            return path == null ? null
                : path + " \u2014 checked means compiler errors from this file are ignored";
        }
    }

    // ── Toolbar actions ───────────────────────────────────────────────────────

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

    private final class ToggleSettingsAction extends ToggleAction {

        ToggleSettingsAction() {
            super("Show Settings", "Show or hide the settings section", AllIcons.General.GearPlain);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return PropertiesComponent.getInstance().getBoolean(SETTINGS_VISIBLE_KEY, true);
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            PropertiesComponent.getInstance().setValue(SETTINGS_VISIBLE_KEY, state, true);
            applySettingsVisibility();
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
