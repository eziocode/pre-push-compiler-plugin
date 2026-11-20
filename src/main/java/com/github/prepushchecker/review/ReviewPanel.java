package com.github.prepushchecker.review;

import com.github.prepushchecker.WriteSafeEdt;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckboxTreeListener;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Code Review" tab: findings grouped by file, with Go to code / Resolve / Resolve with AI. Each
 * open finding and file has a checkbox; "Resolve Checked with AI" fixes all checked ones in one go.
 */
public final class ReviewPanel extends JPanel implements Disposable {

    private final Project project;
    private final ReviewStateStore store;
    private final CheckedTreeNode rootNode = new CheckedTreeNode();
    // Explicit policy: the 2-argument constructor is deprecated (its default policy is defective).
    // A file's box ticks all its findings; ticking every finding ticks the file.
    private final CheckboxTree tree = new CheckboxTree(new Renderer(), rootNode,
        new com.intellij.ui.CheckboxTreeBase.CheckPolicy(true, true, true, true));
    private final DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
    /** Checked finding ids; survive {@link #reload()}, which rebuilds every node. */
    private final Set<String> checked = new LinkedHashSet<>();
    private final JBLabel status = new JBLabel();
    private final JBLabel detailTitle = new JBLabel();
    private final JBTextArea detailText = new JBTextArea();
    private final JPanel detailButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0));
    private final JPanel details = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    private final Runnable listener = this::reload;

    public ReviewPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.store = ReviewStateStore.getInstance(project);

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ReviewLocalChangesAction());
        group.add(new FixCheckedAction());
        group.add(new CheckAllAction());
        group.add(new ClearAction());
        group.addSeparator();
        group.add(new OpenSettingsAction());
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("PrePushReviewToolbar", group, true);
        toolbar.setTargetComponent(this);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(toolbar.getComponent());
        status.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
        status.setForeground(UIUtil.getContextHelpForeground());
        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(status, BorderLayout.CENTER);
        header.add(statusRow);
        add(header, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addCheckboxTreeListener(new CheckboxTreeListener() {
            @Override
            public void nodeStateChanged(@NotNull CheckedTreeNode node) {
                syncChecked();
            }
        });
        tree.getEmptyText().setText("No open review findings");
        tree.getEmptyText().appendSecondaryText("Review changes from the commit panel",
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, e -> new ReviewLocalChangesAction().run());
        tree.addTreeSelectionListener(e -> showDetails());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) navigate(selectedFinding());
            }
        });
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) navigate(selectedFinding());
            }
        });

        detailTitle.setBorder(JBUI.Borders.empty(6, 8, 0, 8));
        detailText.setEditable(false);
        detailText.setLineWrap(true);
        detailText.setWrapStyleWord(true);
        detailText.setOpaque(false);
        detailText.setBorder(JBUI.Borders.empty(0, 8));
        detailButtons.setBorder(JBUI.Borders.empty(2, 4, 6, 8));
        detailButtons.add(new ActionLink("Go to code", (java.awt.event.ActionListener) e -> navigate(selectedFinding())));
        detailButtons.add(new ActionLink("Resolve", (java.awt.event.ActionListener) e -> resolveSelected()));
        ActionLink aiLink = new ActionLink("Resolve with AI", (java.awt.event.ActionListener) e -> fixSelected());
        aiLink.setIcon(AllIcons.Actions.IntentionBulb, false);
        detailButtons.add(aiLink);
        details.add(detailTitle, BorderLayout.NORTH);
        details.add(ScrollPaneFactory.createScrollPane(detailText, true), BorderLayout.CENTER);
        details.add(detailButtons, BorderLayout.SOUTH);

        OnePixelSplitter splitter = new OnePixelSplitter(true, "prepushchecker.review.splitter", 0.65f);
        splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(tree, true));
        splitter.setSecondComponent(details);
        add(splitter, BorderLayout.CENTER);

        store.addListener(listener);
        reload();
    }

    @Override
    public void dispose() {
        store.removeListener(listener);
    }

    // ── Model ────────────────────────────────────────────────────────────────

    private void reload() {
        ReviewFinding previous = selectedFinding();
        String selectedId = previous != null ? previous.id : null;
        boolean wasOpen = previous != null && previous.isOpen();
        List<String> oldOrder = findingIds();
        rootNode.removeAllChildren();
        int open = 0;
        int resolved = 0;
        int files = 0;
        for (ReviewStateStore.FileReview review : store.reviews()) {
            if (!review.hasOpenFindings()) continue;
            files++;
            CheckedTreeNode fileNode = new CheckedTreeNode(review);
            boolean allChecked = true;
            for (ReviewFinding f : review.findings) {
                CheckedTreeNode node = new CheckedTreeNode(f);
                node.setChecked(f.isOpen() && checked.contains(f.id));
                node.setEnabled(f.isOpen());
                if (f.isOpen() && !node.isChecked()) allChecked = false;
                fileNode.add(node);
                if (f.isOpen()) open++; else resolved++;
            }
            fileNode.setChecked(allChecked);
            rootNode.add(fileNode);
        }
        rootNode.setChecked(false);
        // Forget checks of findings that are gone or resolved.
        Set<String> live = new LinkedHashSet<>();
        for (ReviewFinding f : checkedFindings()) live.add(f.id);
        checked.retainAll(live);
        model.reload();
        TreeUtil.expandAll(tree);
        if (selectedId != null) restoreSelection(selectedId, wasOpen, oldOrder);
        status.setText(files == 0 ? ""
            : open + " open · " + resolved + " resolved · " + files + " file(s) reviewed");
        status.setForeground(open > 0 ? JBColor.namedColor("Label.errorForeground", JBColor.RED)
            : UIUtil.getContextHelpForeground());
        showDetails();
    }

    private void syncChecked() {
        checked.clear();
        for (ReviewFinding f : checkedFindings()) checked.add(f.id);
    }

    /** Open findings whose box is ticked, in tree order. */
    @NotNull List<ReviewFinding> checkedFindings() {
        List<ReviewFinding> out = new ArrayList<>();
        TreeUtil.treeNodeTraverser(rootNode).traverse().forEach(n -> {
            if (n instanceof CheckedTreeNode c && c.isChecked() && c.getUserObject() instanceof ReviewFinding f && f.isOpen()) {
                out.add(f);
            }
        });
        return out;
    }

    /** Ticks or clears every open finding. */
    void setAllChecked(boolean value) {
        TreeUtil.treeNodeTraverser(rootNode).traverse().forEach(n -> {
            if (n instanceof CheckedTreeNode c) {
                boolean open = !(c.getUserObject() instanceof ReviewFinding f) || f.isOpen();
                c.setChecked(value && open);
            }
        });
        syncChecked();
        tree.repaint();
    }

    private List<String> findingIds() {
        List<String> ids = new ArrayList<>();
        TreeUtil.treeNodeTraverser(rootNode).traverse().forEach(n -> {
            if (n instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof ReviewFinding f) ids.add(f.id);
        });
        return ids;
    }

    /**
     * Keeps the selection on the same finding. A finding that was open and has just been resolved
     * is not kept (that left a stale "(resolved)" pane up): selection moves to the next open finding,
     * or clears so the detail pane hides. A resolved finding the user opened themselves stays.
     */
    private void restoreSelection(String id, boolean wasOpen, List<String> oldOrder) {
        ReviewFinding current = findFinding(id);
        if (current != null && (!wasOpen || current.isOpen())) {
            select(id);
            return;
        }
        List<String> newOrder = findingIds();
        int from = Math.max(0, oldOrder.indexOf(id));
        String next = null;
        for (int i = 0; i < newOrder.size() && next == null; i++) {
            String candidate = newOrder.get((from + i) % newOrder.size());
            ReviewFinding f = findFinding(candidate);
            if (f != null && f.isOpen()) next = candidate;
        }
        if (next != null) select(next); else tree.clearSelection();
    }

    private @Nullable ReviewFinding findFinding(String id) {
        for (var it = TreeUtil.treeNodeTraverser(rootNode).traverse().iterator(); it.hasNext(); ) {
            Object n = it.next();
            if (n instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof ReviewFinding f && f.id.equals(id)) return f;
        }
        return null;
    }

    void select(String findingId) {
        TreeUtil.treeNodeTraverser(rootNode).traverse().forEach(n -> {
            if (n instanceof DefaultMutableTreeNode d && d.getUserObject() instanceof ReviewFinding f && f.id.equals(findingId)) {
                tree.setSelectionPath(new TreePath(d.getPath()));
            }
        });
    }

    private @Nullable ReviewFinding selectedFinding() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        Object o = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return o instanceof ReviewFinding f ? f : null;
    }

    private void showDetails() {
        ReviewFinding f = selectedFinding();
        if (f == null) {
            details.setVisible(false);
            return;
        }
        details.setVisible(true);
        detailTitle.setText((f.rule.isBlank() ? "Finding" : f.rule) + (f.resolved ? "  (resolved)" : ""));
        detailTitle.setIcon(f.resolved ? AllIcons.RunConfigurations.TestPassed : AllIcons.General.Warning);
        String text = f.message + (f.suggestion.isBlank() ? "" : "\n\nSuggested fix: " + f.suggestion);
        detailText.setText(text);
        detailText.setCaretPosition(0);
        for (int i = 1; i < detailButtons.getComponentCount(); i++) detailButtons.getComponent(i).setVisible(!f.resolved);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void navigate(@Nullable ReviewFinding f) {
        if (f == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(f.path);
        if (vf == null) return;
        new OpenFileDescriptor(project, vf, Math.max(0, f.line - 1), 0).navigate(true);
    }

    private void resolveSelected() {
        ReviewFinding f = selectedFinding();
        if (f == null || f.resolved) return;
        String id = f.id;
        // Link clicks run without the write-intent lock that saving needs (asserted since 2026.3):
        // save from a write-safe EDT event, so approval is hashed off what is on disk.
        WriteSafeEdt.invokeLater(() -> {
            if (project.isDisposed()) return;
            FileDocumentManager.getInstance().saveAllDocuments();
            resolveInBackground(project, id, "manual");
        });
    }

    private void fixSelected() {
        ReviewFinding f = selectedFinding();
        if (f == null || f.resolved) return;
        ReviewFixer.fixWithAi(project, f);
    }

    /** Marks a finding resolved; approval moves to the file's current content (hashed off the EDT). */
    static void resolveInBackground(@NotNull Project project, @NotNull String findingId, @NotNull String by) {
        ReviewStateStore store = ReviewStateStore.getInstance(project);
        ReviewFinding f = store.finding(findingId);
        if (f == null) return;
        String path = f.path;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Path file = Path.of(path);
            Path root = ReviewGit.repoRoot(file);
            if (root != null) root = ReviewGit.canonical(root);
            String id = null;
            if (root != null) {
                Map<String, String> ids = ReviewGit.hashObjects(root, List.of(ReviewGit.relative(root, file)), true);
                id = ids.values().stream().findFirst().orElse(null);
            }
            store.resolve(findingId, by, id);
        });
    }

    private final class ReviewLocalChangesAction extends AnAction {
        ReviewLocalChangesAction() {
            super("Review Local Changes", "Review every changed and new file against the review rules",
                AllIcons.Actions.Execute);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!ReviewService.getInstance(project).isRunning());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            saveAndReview();
        }

        void run() {
            // Also called from the empty-tree link, whose click has no write-intent lock: save from a
            // write-safe EDT event instead of directly.
            WriteSafeEdt.invokeLater(this::saveAndReview);
        }

        private void saveAndReview() {
            if (project.isDisposed()) return;
            // Save first; git status (in the background task) then sees edits the change list has not caught up with.
            FileDocumentManager.getInstance().saveAllDocuments();
            ChangeListManager clm = ChangeListManager.getInstance(project);
            List<Path> files = new ArrayList<>();
            ReviewChangesAction.addChanges(files, clm.getAllChanges());
            for (FilePath fp : clm.getUnversionedFilesPaths()) files.add(Path.of(fp.getPath()));
            ReviewService.getInstance(project).reviewLocalChangesInBackground(files);
        }
    }

    private final class FixCheckedAction extends AnAction {
        FixCheckedAction() {
            super("Resolve Checked with AI", "Ask the AI to fix every checked finding; review all fixes before applying",
                AllIcons.Actions.IntentionBulb);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            int n = checked.size();
            e.getPresentation().setText(n == 0 ? "Resolve Checked with AI" : "Resolve " + n + " Checked with AI");
            e.getPresentation().setEnabled(n > 0 && !ReviewFixer.isBatchRunning(project));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            List<ReviewFinding> picked = checkedFindings();
            if (picked.isEmpty()) return;
            ReviewFixer.fixManyWithAi(project, picked);
        }
    }

    private final class CheckAllAction extends AnAction {
        CheckAllAction() {
            super("Check All / None", "Tick or clear every open finding", AllIcons.Actions.Selectall);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(store.hasOpenFindings());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            setAllChecked(checked.isEmpty());
        }
    }

    private final class ClearAction extends AnAction {
        ClearAction() {
            super("Clear Reviews", "Forget every review; files must be reviewed again before a mandatory push",
                AllIcons.Actions.GC);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!store.isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (Messages.showOkCancelDialog(project, "Forget all reviews? Files must be reviewed again before "
                    + "a push when review is mandatory.", "Clear Reviews", "Clear", Messages.getCancelButton(),
                Messages.getQuestionIcon()) == Messages.OK) {
                store.clear();
            }
        }
    }

    private final class OpenSettingsAction extends AnAction {
        OpenSettingsAction() {
            super("Review Settings…", "Rule file, review mode and mandate", AllIcons.General.Settings);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Code Review");
        }
    }

    private final class Renderer extends CheckboxTree.CheckboxTreeCellRenderer {
        @Override
        public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                                      boolean leaf, int row, boolean hasFocus) {
            ColoredTreeCellRenderer r0 = getTextRenderer();
            Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
            if (o instanceof ReviewStateStore.FileReview r) {
                Path p = Path.of(r.path);
                r0.append(p.getFileName().toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                String base = project.getBasePath();
                String dir = p.getParent() == null ? "" : p.getParent().toString().replace('\\', '/');
                if (base != null && dir.startsWith(base)) dir = dir.substring(base.length()).replaceFirst("^/", "");
                if (!dir.isEmpty()) r0.append("  " + dir, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                long open = r.findings.stream().filter(ReviewFinding::isOpen).count();
                if (open > 0) {
                    r0.setIcon(AllIcons.General.Warning);
                    r0.append("  " + open + " open", SimpleTextAttributes.ERROR_ATTRIBUTES);
                } else {
                    r0.setIcon(AllIcons.RunConfigurations.TestPassed);
                    r0.append(r.findings.isEmpty() ? "  no issues" : "  all resolved", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            } else if (o instanceof ReviewFinding f) {
                r0.setIcon(f.resolved ? AllIcons.Actions.Checked : AllIcons.General.BalloonWarning);
                if (f.line > 0) r0.append(f.line + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                String msg = f.message.length() > 160 ? f.message.substring(0, 160) + "…" : f.message;
                r0.append(msg, f.resolved
                    ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getInactiveTextColor())
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }
    }
}
