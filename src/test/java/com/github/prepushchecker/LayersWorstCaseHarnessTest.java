package com.github.prepushchecker;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.util.ui.UIUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dev-only harness: renders the Layers dialog and the tool window's Layers row with demo and
 * worst-case data (Demo / Worst case / Empty / One / Huge) to PNGs and prints measurements.
 * Data enters through {@link IdeProjectLayers.Snapshot} and the initial drafts, the same
 * boundary real IntelliJ data uses. Findings go to stdout as {@code FINDING ...} lines.
 */
public class LayersWorstCaseHarnessTest extends HeavyPlatformTestCase {
    private static final Path OUT = Path.of(System.getProperty("breakui.out",
        "/private/tmp/claude-502/-Users-aswin-17517-Documents-IntelliJ-Plugins-pre-push-compiler-plugin/"
            + "f8e2450d-a5c5-4fa6-9861-0b3d3473fc4d/scratchpad/break-ui"));

    // ── Fixtures ────────────────────────────────────────────────────────────

    private record Fixture(String name, IdeProjectLayers.Snapshot snapshot, List<LayerDraft> initial) {
    }

    private static IdeProjectLayers.Snapshot snapshot(List<IdeProjectLayers.ModuleInfo> modules, List<String> extraJars) {
        Set<String> jars = new TreeSet<>(extraJars);
        modules.forEach(m -> jars.addAll(m.jars()));
        return new IdeProjectLayers.Snapshot(modules, jars, 0);
    }

    private static Fixture demo() {
        var snapshot = snapshot(List.of(
            new IdeProjectLayers.ModuleInfo("core", List.of("mod1/src"), List.of("lib/core-lib.jar")),
            new IdeProjectLayers.ModuleInfo("crm", List.of("mod2/src"), List.of("lib/crm-lib.jar"))), List.of());
        return new Fixture("demo", snapshot, IdeProjectLayers.draftFromModules(snapshot));
    }

    private static Fixture worst() {
        String bigRoot = "zohocrm/microbase/authorization/territory/privacy/src/";
        var modules = List.of(
            new IdeProjectLayers.ModuleInfo("zohocrm-microbase-authorization-territory-privacy-parent",
                List.of(bigRoot + "main/java", bigRoot + "generated/java"),
                List.of("lib/thirdparty/org.apache.commons/commons-collections4/4.4/commons-collections4-4.4-sources-and-javadoc-bundle.jar",
                    "lib/CRM Libs (old)/zoho crm sdk-2.1.jar",
                    "lib/ライブラリ/日本語-util.jar")),
            new IdeProjectLayers.ModuleInfo("Pricing & Quotes (Legacy)",
                List.of("crm/legacy/Pricing Quotes (old)/src"), List.of("lib/C#/interop.jar")),
            new IdeProjectLayers.ModuleInfo("j", List.of("j/src"), List.of()),
            new IdeProjectLayers.ModuleInfo("aggregator-no-sources", List.of(), List.of("lib/orphan.jar")));
        var snapshot = snapshot(modules, List.of("lib/thirdparty/com.fasterxml.jackson.core/jackson-databind/2.15.2/jackson-databind-2.15.2.jar"));
        List<LayerDraft> initial = IdeProjectLayers.draftFromModules(snapshot);
        LayerDraft hash = new LayerDraft("stage#1");
        hash.includes.add("source/C#/**");
        hash.libs.add("lib/C#/*.jar");
        initial.add(hash);
        return new Fixture("worst", snapshot, initial);
    }

    private static Fixture empty() {
        return new Fixture("empty", snapshot(List.of(), List.of()), List.of());
    }

    private static Fixture one() {
        var snapshot = snapshot(List.of(
            new IdeProjectLayers.ModuleInfo("only", List.of("src"), List.of("lib/x.jar"))), List.of());
        return new Fixture("one", snapshot, IdeProjectLayers.draftFromModules(snapshot));
    }

    private static Fixture huge() {
        List<IdeProjectLayers.ModuleInfo> modules = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            modules.add(new IdeProjectLayers.ModuleInfo("service-" + i, List.of("services/service-" + i + "/src/main/java"),
                List.of("lib/vendor-" + i + "/a-" + i + ".jar", "lib/vendor-" + i + "/b-" + i + ".jar", "lib/vendor-" + i + "/c-" + i + ".jar")));
        }
        var snapshot = snapshot(modules, List.of());
        return new Fixture("huge", snapshot, IdeProjectLayers.draftFromModules(snapshot));
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) layoutAll(child);
        }
    }

    private static void render(JComponent c, int w, int h, String file) throws Exception {
        Files.createDirectories(OUT);
        c.setSize(w, h);
        layoutAll(c);
        layoutAll(c);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        c.paint(g);
        g.dispose();
        ImageIO.write(image, "png", OUT.resolve(file).toFile());
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static JComponent centerPanel(LayersEditorDialog dialog) throws Exception {
        var method = LayersEditorDialog.class.getDeclaredMethod("createCenterPanel");
        method.setAccessible(true);
        return (JComponent) method.invoke(dialog);
    }

    private static void finding(String text) {
        System.out.println("FINDING " + text);
    }

    // ── The run ─────────────────────────────────────────────────────────────

    public void testDialogAcrossFixtures() throws Exception {
        for (Fixture f : List.of(demo(), worst(), empty(), one(), huge())) {
            long start = System.nanoTime();
            LayersEditorDialog dialog = new LayersEditorDialog(getProject(), "/repo", f.snapshot(), f.initial());
            long ms = (System.nanoTime() - start) / 1_000_000;
            try {
                JComponent panel = centerPanel(dialog);
                Dimension pref = panel.getPreferredSize();
                JList<?> items = field(dialog, "itemList");
                JTextArea preview = field(dialog, "preview");
                JLabel problem = field(dialog, "problem");
                finding(f.name() + ": build=" + ms + "ms preferred=" + pref.width + "x" + pref.height
                    + " items=" + items.getModel().getSize() + " previewLines=" + preview.getLineCount()
                    + " ok=" + dialog.isOKActionEnabled() + " problem='" + problem.getText().trim() + "'");
                for (int w : new int[]{Math.min(pref.width, 1800), 1000}) {
                    render(panel, w, Math.min(Math.max(pref.height, 600), 900), "dialog-" + f.name() + "-" + w + ".png");
                }
                if (f.name().equals("demo") || f.name().equals("one")) {
                    assertFalse(items.getModel().getElementAt(0).toString(),
                        items.getModel().getElementAt(0).toString().contains("1 source roots"));
                }
                if (f.name().equals("empty")) {
                    JList<?> layers = field(dialog, "layerList");
                    assertEquals("No layers yet", ((com.intellij.ui.components.JBList<?>) layers).getEmptyText().getText());
                    assertTrue(problem.getText(), problem.getText().contains("At least two layers"));
                    assertFalse(dialog.isOKActionEnabled());
                }
                if (f.name().equals("huge")) {
                    assertTrue("build took " + ms + "ms", ms < 2000);
                }
                if (f.name().equals("worst")) {
                    StringBuilder labels = new StringBuilder();
                    for (int i = 0; i < items.getModel().getSize(); i++) labels.append(items.getModel().getElementAt(i)).append(" | ");
                    finding("worst item labels: " + labels);
                    finding("worst rules text:\n" + dialog.getRulesText());
                    LayerRulesParser.Result parsed = LayerRulesParser.parse(dialog.getRulesText());
                    finding("worst round trip ok=" + parsed.ok() + " problems=" + parsed.problems());
                    assertTrue(parsed.problems().toString(), parsed.ok());
                    List<LayerDraft> backAll = LayerDraft.fromRules(parsed.rules());
                    assertEquals(f.initial().size(), backAll.size());
                    for (int i = 0; i < backAll.size(); i++) {
                        assertEquals(f.initial().get(i).name, backAll.get(i).name);
                        assertEquals(f.initial().get(i).includes, backAll.get(i).includes);
                        assertEquals(f.initial().get(i).libs, backAll.get(i).libs);
                    }
                    assertTrue(labels.toString(), labels.toString().contains("(1 source root)"));
                    assertFalse(labels.toString(), labels.toString().contains("1 source roots"));
                    assertTrue(labels.toString(), labels.toString().contains("(2 source roots)"));
                    assertEquals(" ", problem.getText());
                    assertTrue("worst-case dialog wider than demo: " + pref.width + " vs "
                        + centerPanel(new LayersEditorDialog(getProject(), "/repo", demo().snapshot(), demo().initial())).getPreferredSize().width,
                        pref.width <= 839 + 40);
                    // Long clipped rows still show their full text as a tooltip.
                    Rectangle cell = items.getCellBounds(0, 0);
                    assertNotNull(cell);
                    java.awt.event.MouseEvent hover = new java.awt.event.MouseEvent(items, java.awt.event.MouseEvent.MOUSE_MOVED,
                        0, 0, cell.x + 2, cell.y + 2, 0, false);
                    assertEquals(String.valueOf(items.getModel().getElementAt(0)),
                        ((javax.swing.JComponent) items).getToolTipText(hover));
                    if (parsed.rules() != null) {
                        for (LayerDraft back : LayerDraft.fromRules(parsed.rules())) {
                            finding("worst round trip layer '" + back.name + "' includes=" + back.includes + " libs=" + back.libs);
                        }
                    }
                }
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        }
    }

    public void testLongDuplicateLayerNameWidensDialog() throws Exception {
        String longName = "compilesrc_crmcore_authorization_territory_privacy_microbase_constants_and_enums_shared_layer";
        List<LayerDraft> layers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            LayerDraft draft = new LayerDraft(longName);
            draft.includes.add("source/**");
            layers.add(draft);
        }
        LayersEditorDialog normal = new LayersEditorDialog(getProject(), "/repo", demo().snapshot(), demo().initial());
        LayersEditorDialog broken = new LayersEditorDialog(getProject(), "/repo", demo().snapshot(), layers);
        try {
            int normalWidth = centerPanel(normal).getPreferredSize().width;
            JComponent brokenPanel = centerPanel(broken);
            JLabel problem = field(broken, "problem");
            finding("duplicate-name: problem='" + problem.getText() + "' labelWidth=" + problem.getPreferredSize().width
                + " panelPreferredWidth normal=" + normalWidth + " broken=" + brokenPanel.getPreferredSize().width);
            render(brokenPanel, Math.min(brokenPanel.getPreferredSize().width, 2200), 600, "dialog-duplicate-name.png");
            assertTrue("dialog widened by a long name: " + brokenPanel.getPreferredSize().width + " vs " + normalWidth,
                brokenPanel.getPreferredSize().width <= normalWidth + 40);
            assertTrue(problem.getText(), problem.getText().length() < 80);
            assertEquals(problem.getText().length() > 0, problem.getToolTipText() != null);
            assertTrue(problem.getToolTipText(), problem.getToolTipText().contains("Duplicate layer name"));
        } finally {
            Disposer.dispose(normal.getDisposable());
            Disposer.dispose(broken.getDisposable());
        }
    }

    public void testToolWindowLayersRowAtNarrowWidths() throws Exception {
        CompilationCheckerPanel panel = new CompilationCheckerPanel(getProject(), null);
        try {
            for (int w : new int[]{300, 380, 480, 640}) {
                render(panel, w, 900, "toolwindow-" + w + ".png");
                List<JButton> buttons = UIUtil.findComponentsOfType(panel, JButton.class);
                StringBuilder row = new StringBuilder();
                for (JButton b : buttons) {
                    if (!(b.getText().startsWith("Edit Layers") || b.getText().equals("Run Now") || b.getText().startsWith("Generate"))) continue;
                    Rectangle bounds = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), panel);
                    Container scrollParent = b;
                    while (scrollParent != null && !(scrollParent instanceof JViewport)) scrollParent = scrollParent.getParent();
                    int viewportWidth = scrollParent == null ? -1 : scrollParent.getWidth();
                    row.append(b.getText()).append("[x=").append(bounds.x).append(" w=").append(bounds.width)
                        .append(" right=").append(bounds.x + bounds.width).append(" viewport=").append(viewportWidth).append("] ");
                }
                finding("toolwindow width " + w + ": " + row);
                for (JButton b : buttons) {
                    if (!(b.getText().startsWith("Edit Layers") || b.getText().equals("Run Now") || b.getText().startsWith("Generate"))) continue;
                    Rectangle bounds = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), panel);
                    assertTrue(b.getText() + " clipped at width " + w + ": right=" + (bounds.x + bounds.width),
                        bounds.x + bounds.width <= w);
                }
            }
        } finally {
            Disposer.dispose(panel);
        }
    }

    public void testNamesAndPatternsThatCannotBeSavedAreRejectedBeforeWriting() throws Exception {
        LayerDraft bad = new LayerDraft("bad #name");
        bad.roots.add("source");
        bad.includes.add("source/a/**");
        LayerDraft ok = new LayerDraft("ok");
        ok.roots.add("source");
        ok.includes.add("source/**");
        LayersEditorDialog dialog = new LayersEditorDialog(getProject(), "/repo", demo().snapshot(), List.of(bad, ok));
        try {
            JLabel problem = field(dialog, "problem");
            assertTrue(problem.getText(), problem.getText().contains("would not read back the same"));
            assertFalse(dialog.isOKActionEnabled());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }

        LayerDraft noRoot1 = new LayerDraft("a");
        noRoot1.includes.add("source/a/**");
        LayerDraft noRoot2 = new LayerDraft("b");
        noRoot2.includes.add("source/**");
        LayersEditorDialog rootless = new LayersEditorDialog(getProject(), "/repo", demo().snapshot(), List.of(noRoot1, noRoot2));
        try {
            JLabel problem = field(rootless, "problem");
            assertTrue(problem.getText(), problem.getText().contains("No source root yet"));
            assertFalse(rootless.isOKActionEnabled());
        } finally {
            Disposer.dispose(rootless.getDisposable());
        }
    }
}
