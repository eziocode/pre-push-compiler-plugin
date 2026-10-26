package com.github.prepushchecker;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edits the layer model the cross-layer check enforces, instead of IntelliJ's module hierarchy:
 * pick IntelliJ modules (source roots) and jars on the left, assign them to ordered layers on the
 * right. The result is {@link LayerRules#FILE_NAME} text; nothing here changes the IDE's modules.
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

    LayersEditorDialog(
        @NotNull Project project,
        @NotNull String repositoryRoot,
        @NotNull IdeProjectLayers.Snapshot snapshot,
        @NotNull List<LayerDraft> initial
    ) {
        super(project, true);
        this.project = project;
        this.repositoryRoot = repositoryRoot;
        this.snapshot = snapshot;
        setTitle("Layers — order modules and jars as the build compiles them");
        initial.forEach(layerModel::addElement);
        init();
        refreshItems();
        if (!layerModel.isEmpty()) layerList.setSelectedIndex(0);
        refreshAll();
    }

    /** The rules-file text for the current layers. */
    @NotNull String getRulesText() {
        return LayerRulesWriter.render(List.of(), List.of(), drafts(),
            List.of("Edited with the Layers dialog."));
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
        left.add(add, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(JBUI.scale(380), JBUI.scale(380)));
        left.setBorder(BorderFactory.createTitledBorder("IntelliJ modules and jars"));

        JPanel layerButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        layerButtons.add(button("New", this::newLayer));
        layerButtons.add(button("Rename", this::renameLayer));
        layerButtons.add(button("Remove", this::removeLayer));
        layerButtons.add(button("↑", () -> moveLayer(-1)));
        layerButtons.add(button("↓", () -> moveLayer(1)));

        JPanel entryButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        entryButtons.add(button("Add jar pattern…", () -> addPattern("lib", "Jar pattern (repo-relative, e.g. lib/core/*.jar)")));
        entryButtons.add(button("Add include…", () -> addPattern("include", "Include pattern (repo-relative, e.g. source/com/x/**)")));
        entryButtons.add(button("Remove entry", this::removeEntry));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            wrap("Layers (compile order, first to last)", layerList, layerButtons),
            wrap("Selected layer", entryList, entryButtons));
        rightSplit.setResizeWeight(0.4);

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
        layerList.addListSelectionListener(event -> refreshEntries());
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

    private void addPattern(String kind, String prompt) {
        LayerDraft layer = layerList.getSelectedValue();
        if (layer == null) {
            Messages.showInfoMessage(getContentPane(), "Create or select a layer first.", "Layers");
            return;
        }
        String pattern = Messages.showInputDialog(getContentPane(), prompt, "Layers", null);
        if (pattern == null || pattern.isBlank()) return;
        addUnique(kind.equals("lib") ? layer.libs : layer.includes, pattern.trim());
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
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    private void loadFromModules() {
        replaceLayers(IdeProjectLayers.draftFromModules(snapshot));
    }

    private void loadFromBuildXml() {
        Path buildFile = chooseBuildXml();
        if (buildFile == null) return;
        try {
            replaceLayers(AntLayerImporter.toDrafts(AntLayerImporter.importFile(buildFile, Path.of(repositoryRoot))));
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
        refreshEntries();
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
        List<LayerDraft> back = LayerDraft.fromRules(parsed.rules());
        for (int i = 0; i < drafts.size(); i++) {
            LayerDraft want = drafts.get(i);
            LayerDraft got = i < back.size() ? back.get(i) : null;
            if (got == null || !want.name.equals(got.name) || !same(want.includes, got.includes)
                    || !same(want.excludes, got.excludes) || !same(want.libs, got.libs)) {
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
