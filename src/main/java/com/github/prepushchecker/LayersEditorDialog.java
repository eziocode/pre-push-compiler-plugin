package com.github.prepushchecker;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Edits the layer model the cross-layer check enforces, instead of IntelliJ's module hierarchy:
 * pick IntelliJ modules (source roots) and jars on the left, assign them to ordered layers on the
 * right. Every jar from the {@code scan} locations is listed per layer, checked (allowed) by default;
 * unchecking writes a {@code forbid} line. The result is {@link LayerRules#FILE_NAME} text; nothing
 * here changes the IDE's modules.
 */
final class LayersEditorDialog extends DialogWrapper {
    private record Item(boolean module, @NotNull String label, @Nullable IdeProjectLayers.ModuleInfo moduleInfo,
                        @Nullable String jar) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record Entry(@NotNull String kind, @NotNull String value, @NotNull List<String> owner) {
        @Override
        public String toString() {
            return kind + "  " + value;
        }
    }

    private final Project project;
    private final String repositoryRoot;
    private final IdeProjectLayers.Snapshot snapshot;

    private final DefaultListModel<LayerDraft> layerModel = new DefaultListModel<>();
    private final JBList<LayerDraft> layerList = tooltipList(layerModel);
    private final DefaultListModel<Entry> entryModel = new DefaultListModel<>();
    private final JBList<Entry> entryList = tooltipList(entryModel);
    private final DefaultListModel<Item> itemModel = new DefaultListModel<>();
    private final JBList<Item> itemList = tooltipList(itemModel);
    private final JBTextField filter = new JBTextField();
    private final JBCheckBox showModules = new JBCheckBox("Modules", true);
    private final JBCheckBox showJars = new JBCheckBox("Jars", true);
    private final JTextArea preview = new JTextArea(9, 60);
    private final JBLabel problem = new JBLabel(" ");
    private final List<String> suggestedScans;
    private final List<String> scans = new ArrayList<>();
    private final DefaultListModel<String> scanModel = new DefaultListModel<>();
    private final JBList<String> scanList = tooltipList(scanModel);
    private final CheckBoxList<String> jarList = new CheckBoxList<>();
    private final JBTextField jarFilter = new JBTextField();
    private final JPanel jarPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    private List<String> universe = List.of();
    private boolean refreshingJars;

    LayersEditorDialog(
        @NotNull Project project,
        @NotNull String repositoryRoot,
        @NotNull IdeProjectLayers.Snapshot snapshot,
        @NotNull List<LayerDraft> initial
    ) {
        this(project, repositoryRoot, snapshot, initial, List.of(), List.of());
    }

    /**
     * @param fileScans      {@code scan} lines of the existing rules file; when empty, the scans start as
     *                       IntelliJ's jar folders plus {@code suggestedScans}
     * @param suggestedScans scan locations {@link ScanDetector} found (zips with jars, unknown jar folders)
     */
    LayersEditorDialog(
        @NotNull Project project,
        @NotNull String repositoryRoot,
        @NotNull IdeProjectLayers.Snapshot snapshot,
        @NotNull List<LayerDraft> initial,
        @NotNull List<String> fileScans,
        @NotNull List<String> suggestedScans
    ) {
        super(project, true);
        this.project = project;
        this.repositoryRoot = repositoryRoot;
        this.snapshot = snapshot;
        this.suggestedScans = List.copyOf(suggestedScans);
        setTitle("Layers — order modules and jars as the build compiles them");
        initial.forEach(layerModel::addElement);
        scans.addAll(fileScans.isEmpty() ? defaultScans() : fileScans);
        init();
        refreshItems();
        if (!layerModel.isEmpty()) layerList.setSelectedIndex(0);
        relistUniverse();
    }

    /** The rules-file text for the current layers. */
    @NotNull String getRulesText() {
        return LayerRulesWriter.render(List.of(), scans, drafts(),
            List.of("Edited with the Layers dialog."));
    }

    /** Display paths of every jar the scan locations and {@code lib} lines reach, sorted. */
    @NotNull List<String> universe() {
        return universe;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel left = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel filterRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        filterRow.add(filter, BorderLayout.CENTER);
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        toggles.add(showModules);
        toggles.add(showJars);
        filterRow.add(toggles, BorderLayout.EAST);
        left.add(filterRow, BorderLayout.NORTH);
        left.add(new JBScrollPane(itemList), BorderLayout.CENTER);
        JButton add = new JButton("Add to layer →");
        add.addActionListener(event -> addSelectedItems());
        JPanel scanButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        scanButtons.add(button("Add folder or zip…", this::chooseScanLocations));
        scanButtons.add(button("Detect…", this::detectScanLocations));
        scanButtons.add(button("Remove", () -> {
            String selected = scanList.getSelectedValue();
            if (selected != null) removeScanLocation(selected);
        }));
        JPanel scanPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JBScrollPane scanScroll = new JBScrollPane(scanList);
        scanScroll.setPreferredSize(new Dimension(JBUI.scale(360), JBUI.scale(90)));
        scanPanel.add(scanScroll, BorderLayout.CENTER);
        scanPanel.add(scanButtons, BorderLayout.SOUTH);
        scanPanel.setBorder(BorderFactory.createTitledBorder("Scan locations (where jars are found)"));
        JPanel leftSouth = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        leftSouth.add(add, BorderLayout.NORTH);
        leftSouth.add(scanPanel, BorderLayout.CENTER);
        left.add(leftSouth, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(JBUI.scale(380), JBUI.scale(520)));
        left.setBorder(BorderFactory.createTitledBorder("IntelliJ modules and jars"));

        JPanel layerButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        layerButtons.add(button("New", this::newLayer));
        layerButtons.add(button("Rename", this::renameLayer));
        layerButtons.add(button("Remove", this::removeLayer));
        layerButtons.add(button("↑", () -> moveLayer(-1)));
        layerButtons.add(button("↓", () -> moveLayer(1)));

        JPanel entryButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        entryButtons.add(button("Add include folder…", () -> chooseFolders("include")));
        entryButtons.add(button("Exclude folder…", () -> chooseFolders("exclude")));
        entryButtons.add(button("Remove entry", this::removeEntry));

        JPanel jarButtons = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        jarButtons.add(jarFilter, BorderLayout.CENTER);
        JPanel checkButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        checkButtons.add(button("Check all", () -> setFilteredJarsChecked(true)));
        checkButtons.add(button("Uncheck all", () -> setFilteredJarsChecked(false)));
        jarButtons.add(checkButtons, BorderLayout.EAST);
        JBScrollPane jarScroll = new JBScrollPane(jarList);
        jarScroll.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(160)));
        jarPanel.add(jarScroll, BorderLayout.CENTER);
        jarPanel.add(jarButtons, BorderLayout.SOUTH);

        JSplitPane entrySplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wrap("Selected layer", entryList, entryButtons), jarPanel);
        entrySplit.setResizeWeight(0.4);
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wrap("Layers (compile order, first to last)", layerList, layerButtons), entrySplit);
        rightSplit.setResizeWeight(0.3);

        JPanel top = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        top.add(left, BorderLayout.WEST);
        top.add(rightSplit, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        actions.add(button("From IntelliJ modules", this::loadFromModules));
        actions.add(button("From build.xml…", this::loadFromBuildXml));
        actions.add(button("Reload file", this::reloadFromFile));
        actions.add(button("Compare jars with build.xml…", this::compareJars));

        preview.setEditable(false);
        preview.setFont(JBUI.Fonts.create(Font.MONOSPACED, preview.getFont().getSize()));
        JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(new JBScrollPane(preview), BorderLayout.CENTER);
        bottom.add(problem, BorderLayout.SOUTH);
        bottom.setBorder(BorderFactory.createTitledBorder("Resulting " + LayerRules.FILE_NAME));

        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        root.add(top, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        layerList.getEmptyText().setText("No layers yet");
        layerList.getEmptyText().appendLine("Use From IntelliJ modules or From build.xml\u2026 below");
        entryList.getEmptyText().setText("Select a layer");
        itemList.getEmptyText().setText("Nothing matches");
        layerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        layerList.addListSelectionListener(event -> {
            refreshEntries();
            refreshJars();
        });
        itemList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshItems();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshItems();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshItems();
            }
        });
        showModules.addActionListener(event -> refreshItems());
        showJars.addActionListener(event -> refreshItems());

        scanList.getEmptyText().setText("No scan locations");
        scanList.getEmptyText().appendLine("Add a folder or zip, or Detect\u2026");
        jarList.getEmptyText().setText("No jars in the scan locations");
        jarList.setCheckBoxListListener((index, checked) -> {
            if (refreshingJars) return;
            String jar = jarList.getItemAt(index);
            if (jar != null) setJarChecked(jar, checked);
        });
        jarList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowJarMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowJarMenu(e);
            }
        });
        jarFilter.getEmptyText().setText("Filter jars");
        jarFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshJars();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshJars();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshJars();
            }
        });
        entryList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowEntryMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowEntryMenu(e);
            }
        });
        return root;
    }

    /** A list whose rows show their full text as a tooltip, since long paths get clipped. */
    private static <T> JBList<T> tooltipList(DefaultListModel<T> model) {
        JBList<T> list = new JBList<>(model) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int index = locationToIndex(event.getPoint());
                if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) return null;
                return String.valueOf(getModel().getElementAt(index));
            }
        };
        ToolTipManager.sharedInstance().registerComponent(list);
        return list;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JComponent wrap(String title, JComponent list, JComponent buttons) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        // A list's preferred width is its longest row; cap it so long names scroll instead of
        // making the whole dialog wider.
        JBScrollPane scroll = new JBScrollPane(list);
        Dimension natural = scroll.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.min(natural.width, JBUI.scale(460)), natural.height));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    // ── Left pane ───────────────────────────────────────────────────────────

    private void refreshItems() {
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        itemModel.clear();
        if (showModules.isSelected()) {
            for (IdeProjectLayers.ModuleInfo module : snapshot.modules()) {
                if (module.sourceRoots().isEmpty()) continue;
                int roots = module.sourceRoots().size();
                String label = "[module] " + module.name() + "  (" + roots + (roots == 1 ? " source root)" : " source roots)");
                if (query.isEmpty() || label.toLowerCase(Locale.ROOT).contains(query)) {
                    itemModel.addElement(new Item(true, label, module, null));
                }
            }
        }
        if (showJars.isSelected()) {
            for (String jar : snapshot.jars()) {
                if (query.isEmpty() || jar.toLowerCase(Locale.ROOT).contains(query)) {
                    itemModel.addElement(new Item(false, "[jar] " + jar, null, jar));
                }
            }
        }
    }

    private void addSelectedItems() {
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) {
            Messages.showInfoMessage(getContentPane(), "Create or select a layer first.", "Layers");
            return;
        }
        for (Item item : itemList.getSelectedValuesList()) {
            if (item.module() && item.moduleInfo() != null) {
                for (String sourceRoot : item.moduleInfo().sourceRoots()) {
                    addUnique(layer.roots, sourceRoot);
                    addUnique(layer.includes, sourceRoot + "/**");
                }
                // Jars the module depends on directly go with it when no layer lists them yet.
                for (String jar : item.moduleInfo().jars()) {
                    if (!anyLayerHasLib(jar)) addUnique(layer.libs, jar);
                }
            } else if (item.jar() != null) {
                addUnique(layer.libs, item.jar());
            }
        }
        refreshAll();
    }

    private boolean anyLayerHasLib(String jar) {
        for (LayerDraft draft : drafts()) {
            if (draft.libs.contains(jar)) return true;
        }
        return false;
    }

    private static void addUnique(List<String> list, String value) {
        if (!list.contains(value)) list.add(value);
    }

    // ── Right pane ──────────────────────────────────────────────────────────

    private void newLayer() {
        String name = askName("New layer name", "");
        if (name == null) return;
        layerModel.addElement(new LayerDraft(name));
        layerList.setSelectedIndex(layerModel.size() - 1);
        refreshAll();
    }

    private void renameLayer() {
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) return;
        String name = askName("Layer name", layer.name);
        if (name == null) return;
        layer.name = name;
        layerList.repaint();
        refreshAll();
    }

    private @Nullable String askName(String message, String initial) {
        String name = Messages.showInputDialog(getContentPane(), message, "Layers", null, initial, null);
        if (name == null || name.isBlank()) return null;
        return name.trim().replaceAll("\\s+", "_");
    }

    private void removeLayer() {
        int index = layerList.getSelectedIndex();
        if (index < 0) return;
        layerModel.remove(index);
        if (!layerModel.isEmpty()) layerList.setSelectedIndex(Math.min(index, layerModel.size() - 1));
        refreshAll();
    }

    private void moveLayer(int delta) {
        int index = layerList.getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= layerModel.size()) return;
        LayerDraft layer = layerModel.remove(index);
        layerModel.add(target, layer);
        layerList.setSelectedIndex(target);
        refreshAll();
    }

    /** Free-text fallback: edit one entry's pattern in place. */
    private void editEntry(Entry entry) {
        String pattern = Messages.showInputDialog(getContentPane(), "Pattern (repo-relative Ant glob)", "Layers",
            null, entry.value(), null);
        if (pattern == null || pattern.isBlank()) return;
        int index = entry.owner().indexOf(entry.value());
        if (index >= 0) entry.owner().set(index, pattern.trim());
        refreshAll();
    }

    private void maybeShowEntryMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int index = entryList.locationToIndex(e.getPoint());
        if (index < 0) return;
        entryList.setSelectedIndex(index);
        Entry entry = entryList.getSelectedValue();
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("Edit pattern…");
        edit.addActionListener(event -> editEntry(entry));
        menu.add(edit);
        menu.show(entryList, e.getX(), e.getY());
    }

    /** Adds {@code <dir>/**} to the selected layer's includes or excludes. */
    void addFolderEntries(@NotNull String kind, @NotNull List<String> repoRelativeDirs) {
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) {
            Messages.showInfoMessage(getContentPane(), "Create or select a layer first.", "Layers");
            return;
        }
        List<String> target = kind.equals("exclude") ? layer.excludes : layer.includes;
        for (String dir : repoRelativeDirs) {
            String value = dir.replace('\\', '/');
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            addUnique(target, value.isEmpty() ? "**" : value + "/**");
        }
        refreshAll();
    }

    private void chooseFolders(String kind) {
        if (layerList.getSelectedValue() == null) {
            Messages.showInfoMessage(getContentPane(), "Create or select a layer first.", "Layers");
            return;
        }
        List<String> dirs = chooseInRepository(FileChooserDescriptorFactory.createMultipleFoldersDescriptor()
            .withTitle(kind.equals("exclude") ? "Exclude Folders" : "Include Folders"), "Pick folders inside the repository.");
        if (!dirs.isEmpty()) addFolderEntries(kind, dirs);
    }

    /** Repo-relative paths of the chosen files; anything outside the repository is refused with {@code outsideMessage}. */
    private List<String> chooseInRepository(FileChooserDescriptor descriptor, String outsideMessage) {
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(repositoryRoot);
        List<String> chosen = new ArrayList<>();
        boolean outside = false;
        for (VirtualFile file : FileChooser.chooseFiles(descriptor, project, root)) {
            String relative = DiagnosticPathMatcher.relativeToRoot(file.getPath(), repositoryRoot);
            if (relative == null) outside = true;
            else chosen.add(relative);
        }
        if (outside) Messages.showWarningDialog(getContentPane(), outsideMessage, "Layers");
        return chosen;
    }

    // ── Jars ────────────────────────────────────────────────────────────────

    /** Allows ({@code checked}) or forbids a jar for the selected layer. */
    void setJarChecked(@NotNull String displayPath, boolean checked) {
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) return;
        List<String> matching = forbidsMatching(layer, displayPath);
        if (checked) {
            boolean wildcard = matching.stream().anyMatch(p -> p.indexOf('*') >= 0 || p.indexOf('?') >= 0);
            if (wildcard && Messages.showYesNoDialog(getContentPane(),
                    "This also re-allows other jars matching '" + String.join("', '", matching) + "'. Continue?",
                    "Layers", null) != Messages.YES) {
                refreshJars();
                return;
            }
            layer.forbids.removeAll(matching);
        } else if (matching.isEmpty()) {
            layer.forbids.add(displayPath);
        }
        refreshAll();
    }

    /** Forbids every version of the jar for the selected layer. */
    void uncheckAllVersions(@NotNull String displayPath) {
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) return;
        addUnique(layer.forbids, JarPatterns.anyVersion(displayPath));
        refreshAll();
    }

    private void setFilteredJarsChecked(boolean checked) {
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) return;
        for (String jar : filteredUniverse()) {
            List<String> matching = forbidsMatching(layer, jar);
            if (checked) layer.forbids.removeAll(matching);
            else if (matching.isEmpty()) layer.forbids.add(jar);
        }
        refreshAll();
    }

    private void maybeShowJarMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int index = jarList.locationToIndex(e.getPoint());
        String jar = index < 0 ? null : jarList.getItemAt(index);
        if (jar == null) return;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem all = new JMenuItem("Uncheck all versions");
        all.addActionListener(event -> uncheckAllVersions(jar));
        menu.add(all);
        menu.show(jarList, e.getX(), e.getY());
    }

    private static List<String> forbidsMatching(LayerDraft layer, String displayPath) {
        List<String> matching = new ArrayList<>();
        for (String forbid : layer.forbids) {
            if (AntPathPattern.compile(forbid).matches(displayPath)) matching.add(forbid);
        }
        return matching;
    }

    private List<String> filteredUniverse() {
        String query = jarFilter.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return universe;
        List<String> result = new ArrayList<>();
        for (String jar : universe) {
            if (jar.toLowerCase(Locale.ROOT).contains(query)) result.add(jar);
        }
        return result;
    }

    private void refreshJars() {
        LayerDraft layer = layerList.getSelectedValue();
        refreshingJars = true;
        try {
            jarList.clear();
            if (layer == null) {
                jarPanel.setBorder(BorderFactory.createTitledBorder("Jars for selected layer"));
                return;
            }
            int allowed = 0;
            for (String jar : universe) {
                if (forbidsMatching(layer, jar).isEmpty()) allowed++;
            }
            for (String jar : filteredUniverse()) {
                jarList.addItem(jar, jarLabel(jar), forbidsMatching(layer, jar).isEmpty());
            }
            jarPanel.setBorder(BorderFactory.createTitledBorder("Jars for '" + shortName(layer.name) + "' \u2014 "
                + allowed + " of " + universe.size() + " allowed (uncheck to forbid)"));
        } finally {
            refreshingJars = false;
        }
    }

    /** {@code TP.zip › lib/x.jar} for a jar inside a zip; the plain path otherwise. */
    private static String jarLabel(String displayPath) {
        int nested = displayPath.indexOf(ScanJars.NESTED_SEPARATOR);
        if (nested < 0) return displayPath;
        String zip = displayPath.substring(0, nested);
        return zip.substring(zip.lastIndexOf('/') + 1) + " \u203a "
            + displayPath.substring(nested + ScanJars.NESTED_SEPARATOR.length());
    }

    // ── Scan locations ──────────────────────────────────────────────────────

    void addScanLocations(@NotNull List<String> patterns) {
        for (String pattern : patterns) addUnique(scans, pattern);
        relistUniverse();
    }

    void removeScanLocation(@NotNull String pattern) {
        scans.remove(pattern);
        relistUniverse();
    }

    private List<String> defaultScans() {
        Set<String> result = new LinkedHashSet<>(JarPatterns.defaultScans(snapshot.jars()));
        result.addAll(suggestedScans);
        return new ArrayList<>(result);
    }

    private void chooseScanLocations() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, true, true, true, false, true)
            .withTitle("Add Scan Locations")
            .withFileFilter(file -> file.isDirectory() || LayerJars.isJar(file.getName()) || ScanJars.isZip(file.getName()));
        List<String> chosen = new ArrayList<>();
        for (String relative : chooseInRepository(descriptor, "Pick a folder, .zip or .jar inside the repository.")) {
            Path path = Path.of(repositoryRoot, relative);
            chosen.add(Files.isDirectory(path) ? (relative.isEmpty() ? "**" : relative + "/**") : relative);
        }
        if (!chosen.isEmpty()) addScanLocations(chosen);
    }

    private void detectScanLocations() {
        List<ScanDetector.Suggestion> found = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            () -> ScanDetector.detect(Path.of(repositoryRoot), snapshot.jars()), "Finding Jars", true, project);
        List<ScanDetector.Suggestion> fresh = new ArrayList<>();
        for (ScanDetector.Suggestion suggestion : found) {
            if (!scans.contains(suggestion.pattern())) fresh.add(suggestion);
        }
        if (fresh.isEmpty()) {
            Messages.showInfoMessage(getContentPane(), "No new jar locations found.", "Layers");
            return;
        }
        CheckBoxList<ScanDetector.Suggestion> choices = new CheckBoxList<>();
        for (ScanDetector.Suggestion suggestion : fresh) {
            choices.addItem(suggestion, suggestion.pattern() + "  \u2014 " + suggestion.reason(), true);
        }
        DialogWrapper dialog = new DialogWrapper(project, true) {
            {
                setTitle("Add Scan Locations");
                init();
            }

            @Override
            protected JComponent createCenterPanel() {
                JBScrollPane scroll = new JBScrollPane(choices);
                scroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(300)));
                return scroll;
            }
        };
        if (!dialog.showAndGet()) return;
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < choices.getItemsCount(); i++) {
            if (choices.isItemSelected(i)) picked.add(choices.getItemAt(i).pattern());
        }
        addScanLocations(picked);
    }

    /** Re-reads which jars the scan locations and {@code lib} lines reach; zips are listed, not unpacked. */
    private void relistUniverse() {
        List<String> patterns = List.copyOf(scans);
        List<String> libs = new ArrayList<>();
        for (LayerDraft draft : drafts()) libs.addAll(draft.libs);
        Path root = Path.of(repositoryRoot);
        universe = ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            Set<String> jars = new TreeSet<>();
            for (ScanJars.ScannedJar jar : ScanJars.resolve(root, patterns)) jars.add(jar.displayPath());
            jars.addAll(LayerJars.expand(root, libs));
            return List.copyOf(jars);
        }, "Listing Jars", false, project);
        refreshAll();
    }

    private void removeEntry() {
        Entry entry = entryList.getSelectedValue();
        if (entry == null) return;
        entry.owner().remove(entry.value());
        refreshAll();
    }

    private void refreshEntries() {
        entryModel.clear();
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) return;
        for (String value : layer.includes) entryModel.addElement(new Entry("include", value, layer.includes));
        for (String value : layer.excludes) entryModel.addElement(new Entry("exclude", value, layer.excludes));
        for (String value : layer.libs) entryModel.addElement(new Entry("lib    ", value, layer.libs));
        for (String value : layer.forbids) entryModel.addElement(new Entry("forbid ", value, layer.forbids));
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    private void loadFromModules() {
        replaceLayers(IdeProjectLayers.draftFromModules(snapshot));
        addScanLocations(defaultScans());
    }

    private void loadFromBuildXml() {
        Path buildFile = chooseBuildXml();
        if (buildFile == null) return;
        try {
            replaceLayers(AntLayerImporter.toDrafts(AntLayerImporter.importFile(buildFile, Path.of(repositoryRoot))));
            addScanLocations(defaultScans());
        } catch (IOException e) {
            Messages.showErrorDialog(getContentPane(), e.getMessage(), "Layers");
        }
    }

    private void reloadFromFile() {
        Path file = Path.of(repositoryRoot, LayerRules.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            Messages.showInfoMessage(getContentPane(), "No " + LayerRules.FILE_NAME + " yet.", "Layers");
            return;
        }
        try {
            LayerRulesParser.Result parsed = LayerRulesParser.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.rules() == null) {
                Messages.showErrorDialog(getContentPane(), parsed.problems().toString(), "Layers");
                return;
            }
            replaceLayers(LayerDraft.fromRules(parsed.rules()));
            scans.clear();
            parsed.rules().scans().forEach(pattern -> scans.add(pattern.source()));
            relistUniverse();
        } catch (IOException e) {
            Messages.showErrorDialog(getContentPane(), e.getMessage(), "Layers");
        }
    }

    private void replaceLayers(List<LayerDraft> drafts) {
        layerModel.clear();
        drafts.forEach(layerModel::addElement);
        if (!drafts.isEmpty()) layerList.setSelectedIndex(0);
        refreshAll();
    }

    private void compareJars() {
        Path buildFile = chooseBuildXml();
        if (buildFile == null) return;
        try {
            AntLayerImporter.Result ant = AntLayerImporter.importFile(buildFile, Path.of(repositoryRoot));
            AntLayerImporter.JarDiff diff = AntLayerImporter.diffJars(Path.of(repositoryRoot), snapshot.jars(), ant);
            StringBuilder text = new StringBuilder();
            text.append("Jars IntelliJ has but the Ant classpaths do not (").append(diff.ideOnly().size()).append("):\n");
            diff.ideOnly().forEach(jar -> text.append("  ").append(jar).append('\n'));
            text.append("\nJars on the Ant classpaths but unknown to IntelliJ (").append(diff.antOnly().size()).append("):\n");
            diff.antOnly().forEach(jar -> text.append("  ").append(jar).append('\n'));
            if (snapshot.jarsOutsideRepository() > 0) {
                text.append("\nNot compared: ").append(snapshot.jarsOutsideRepository())
                    .append(" IntelliJ library roots outside the repository.\n");
            }
            showText("Jar comparison", text.toString());
        } catch (IOException e) {
            Messages.showErrorDialog(getContentPane(), e.getMessage(), "Layers");
        }
    }

    private @Nullable Path chooseBuildXml() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
            .withTitle("Select Ant Build File");
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(repositoryRoot);
        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, root);
        return chosen == null ? null : Path.of(chosen.getPath());
    }

    private void showText(String title, String text) {
        JTextArea area = new JTextArea(text, 24, 90);
        area.setEditable(false);
        area.setFont(JBUI.Fonts.create(Font.MONOSPACED, area.getFont().getSize()));
        DialogWrapper dialog = new DialogWrapper(project, true) {
            {
                setTitle(title);
                init();
            }

            @Override
            protected JComponent createCenterPanel() {
                return new JBScrollPane(area);
            }

            @Override
            protected Action @NotNull [] createActions() {
                return new Action[]{getOKAction()};
            }
        };
        dialog.show();
    }

    // ── State ───────────────────────────────────────────────────────────────

    private List<LayerDraft> drafts() {
        List<LayerDraft> drafts = new ArrayList<>();
        for (int i = 0; i < layerModel.size(); i++) drafts.add(layerModel.get(i));
        return drafts;
    }

    private void refreshAll() {
        scanModel.clear();
        scans.forEach(scanModel::addElement);
        refreshEntries();
        refreshJars();
        layerList.repaint();
        preview.setText(getRulesText());
        preview.setCaretPosition(0);
        String message = validationMessage();
        problem.setText(message == null ? " " : message);
        problem.setToolTipText(message);
        setOKActionEnabled(message == null);
    }

    /** A layer name short enough for the one-line message; the full text is the label's tooltip. */
    private static String shortName(String name) {
        return StringUtil.shortenTextWithEllipsis(name, 40, 0);
    }

    /** {@code null} when the layers form a rules file the check accepts. */
    private @Nullable String validationMessage() {
        List<LayerDraft> drafts = drafts();
        if (drafts.size() < 2) return "At least two layers are needed.";
        Set<String> names = new HashSet<>();
        for (LayerDraft draft : drafts) {
            if (!names.add(draft.name)) return "Duplicate layer name '" + shortName(draft.name) + "'.";
            if (draft.includes.isEmpty()) return "Layer '" + shortName(draft.name) + "' has no include or module yet.";
        }
        return roundTripProblem(drafts);
    }

    /** What is saved must read back as the same layers, so nothing is silently cut or changed. */
    private @Nullable String roundTripProblem(List<LayerDraft> drafts) {
        LayerRulesParser.Result parsed = LayerRulesParser.parse(getRulesText());
        if (parsed.rules() == null) {
            LayerRulesParser.Problem first = parsed.problems().get(0);
            if (first.message().contains("no 'root'")) {
                return "No source root yet: add a module to a layer (layers need a source root).";
            }
            return "Would not save as valid rules: " + shortName(first.message()) + " (line " + first.line() + ").";
        }
        List<String> parsedScans = new ArrayList<>();
        parsed.rules().scans().forEach(pattern -> parsedScans.add(pattern.source()));
        if (!same(new ArrayList<>(new LinkedHashSet<>(scans)), parsedScans)) {
            return "A scan location would not read back the same after saving (a ' #' starts a comment).";
        }
        List<LayerDraft> back = LayerDraft.fromRules(parsed.rules());
        for (int i = 0; i < drafts.size(); i++) {
            LayerDraft want = drafts.get(i);
            LayerDraft got = i < back.size() ? back.get(i) : null;
            if (got == null || !want.name.equals(got.name) || !same(want.includes, got.includes)
                    || !same(want.excludes, got.excludes) || !same(want.libs, got.libs)
                    || !same(want.forbids, got.forbids)) {
                return "Layer '" + shortName(want.name) + "' would not read back the same after saving"
                    + " (a ' #' starts a comment).";
            }
        }
        return null;
    }

    private static boolean same(List<String> drafted, List<String> parsed) {
        if (drafted.size() != parsed.size()) return false;
        for (int i = 0; i < drafted.size(); i++) {
            if (!AntPathPattern.compile(drafted.get(i)).source().equals(parsed.get(i))) return false;
        }
        return true;
    }
}
