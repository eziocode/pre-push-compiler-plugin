package com.github.prepushchecker;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real IntelliJ modules and a real library jar feeding the Layers dialog. */
public class LayersEditorIdeModelTest extends HeavyPlatformTestCase {
    private Path repo;
    private Path outsideJarDir;
    private IdeProjectLayers.Snapshot snapshot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        repo = Files.createTempDirectory("layers-ide").toRealPath();
        outsideJarDir = Files.createTempDirectory("layers-outside").toRealPath();
        TestFiles.write(repo, "mod1/src/com/x/Core.java", "package com.x;\nclass Core {}\n");
        TestFiles.write(repo, "mod2/src/com/y/Api.java", "package com.y;\nclass Api {}\n");
        TestFiles.write(repo, "mod2/test/com/y/ApiTest.java", "package com.y;\nclass ApiTest {}\n");
        TestFiles.jar(repo, "lib/core-lib.jar", "org/core/Util.class");
        TestFiles.jar(repo, "lib/api-lib.jar", "org/api/Util.class");
        TestFiles.jar(outsideJarDir, "elsewhere.jar", "org/out/Util.class");

        Module mod1 = createModule("mod1");
        Module mod2 = createModule("mod2");
        addSourceRoot(mod1, repo.resolve("mod1/src"), false);
        addSourceRoot(mod2, repo.resolve("mod2/src"), false);
        addSourceRoot(mod2, repo.resolve("mod2/test"), true);
        ModuleRootModificationUtil.addDependency(mod2, mod1);
        addJar(mod1, "core-lib", repo.resolve("lib/core-lib.jar"));
        addJar(mod2, "api-lib", repo.resolve("lib/api-lib.jar"));
        addJar(mod2, "outside", outsideJarDir.resolve("elsewhere.jar"));

        snapshot = IdeProjectLayers.read(getProject(), repo.toString());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            TestFiles.delete(repo);
            TestFiles.delete(outsideJarDir);
        } finally {
            super.tearDown();
        }
    }

    private void addSourceRoot(Module module, Path dir, boolean test) {
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
        assertNotNull(dir.toString(), root);
        PsiTestUtil.addSourceRoot(module, root, test);
    }

    private void addJar(Module module, String name, Path jar) {
        String url = "jar://" + jar.toString().replace('\\', '/') + "!/";
        WriteAction.runAndWait(() -> ModuleRootModificationUtil.addModuleLibrary(module, name, List.of(url), List.of()));
    }

    public void testReadsModulesInDependencyOrderWithRepoRelativePaths() {
        List<String> names = snapshot.modules().stream().map(IdeProjectLayers.ModuleInfo::name)
            .filter(n -> n.equals("mod1") || n.equals("mod2")).toList();
        assertEquals(List.of("mod1", "mod2"), names);

        IdeProjectLayers.ModuleInfo mod1 = module("mod1");
        IdeProjectLayers.ModuleInfo mod2 = module("mod2");
        assertEquals(List.of("mod1/src"), mod1.sourceRoots());
        assertEquals("test roots are left out", List.of("mod2/src"), mod2.sourceRoots());
        assertEquals(List.of("lib/core-lib.jar"), mod1.jars());
        assertEquals(List.of("lib/api-lib.jar"), mod2.jars());
        assertTrue(snapshot.jars().containsAll(List.of("lib/core-lib.jar", "lib/api-lib.jar")));
        assertEquals("outside-repo jar counted, not listed", 1, snapshot.jarsOutsideRepository());
        assertFalse(snapshot.jars().toString(), snapshot.jars().toString().contains("elsewhere"));
    }

    public void testDraftFromModulesOrdersLayersAndAssignsJarsToFirstModule() {
        List<LayerDraft> drafts = IdeProjectLayers.draftFromModules(snapshot).stream()
            .filter(d -> d.name.equals("mod1") || d.name.equals("mod2")).toList();
        assertEquals(2, drafts.size());
        assertEquals(List.of("mod1/src/**"), drafts.get(0).includes);
        assertEquals(List.of("lib/core-lib.jar"), drafts.get(0).libs);
        assertEquals(List.of("mod2/src/**"), drafts.get(1).includes);
        assertEquals(List.of("lib/api-lib.jar"), drafts.get(1).libs);
    }

    public void testDialogListsAssignsPreviewsAndProducesValidRules() throws Exception {
        LayersEditorDialog dialog = new LayersEditorDialog(getProject(), repo.toString(), snapshot, List.of());
        try {
            JComponent panel = centerPanel(dialog);
            JList<?> items = field(dialog, "itemList");
            JList<?> layers = field(dialog, "layerList");
            JTextArea preview = field(dialog, "preview");
            JLabel problem = field(dialog, "problem");

            // Lists: both modules and both repo jars, nothing from outside the repo.
            String listed = listContents(items);
            assertTrue(listed, listed.contains("[module] mod1") && listed.contains("[module] mod2"));
            assertTrue(listed, listed.contains("[jar] lib/core-lib.jar") && listed.contains("[jar] lib/api-lib.jar"));
            assertFalse(listed, listed.contains("elsewhere"));

            // Empty start: not valid yet, OK disabled.
            assertEquals(0, layers.getModel().getSize());
            assertTrue(problem.getText(), problem.getText().contains("At least two layers"));
            assertFalse(dialog.isOKActionEnabled());

            // Load from IntelliJ modules, then the preview and OK reflect two valid layers.
            click(panel, "From IntelliJ modules");
            assertTrue(listContents(layers), layers.getModel().getSize() >= 2);
            assertTrue(preview.getText(), preview.getText().contains("layer mod1")
                && preview.getText().contains("include mod1/src/**")
                && preview.getText().contains("lib lib/core-lib.jar"));
            assertEquals(" ", problem.getText());
            assertTrue(dialog.isOKActionEnabled());

            // Assignment: add a jar item to the selected layer; it shows in the preview once.
            int layerIndex = indexOf(layers, "mod1");
            layers.setSelectedIndex(layerIndex);
            items.setSelectedIndex(indexOf(items, "[jar] lib/api-lib.jar"));
            click(panel, "Add to layer →");
            click(panel, "Add to layer →");
            String text = dialog.getRulesText();
            assertEquals(text, 2, count(text, "lib lib/api-lib.jar"));

            // Written text is a rules file the check accepts, with jars enabled.
            LayerRulesParser.Result parsed = LayerRulesParser.parse(text);
            assertTrue(parsed.problems() + "\n" + text, parsed.ok());
            assertTrue(parsed.rules().hasLibs());
            assertEquals("mod1", parsed.rules().layers().get(0).name());
            assertEquals("mod2", parsed.rules().layers().get(1).name());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    public void testWriteCreatesThenReplacesFileEvenWithUnsavedEditorEdits() throws Exception {
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repo);
        assertNotNull(root);
        Path file = repo.resolve(LayerRules.FILE_NAME);

        LayerRulesFile.write(getProject(), root, "root a\nlayer x\n include a/**\n");
        assertEquals("root a\nlayer x\n include a/**\n", Files.readString(file));

        // Open document with unsaved edits, as when the user has the rules file open in a tab.
        VirtualFile virtualFile = root.findChild(LayerRules.FILE_NAME);
        assertNotNull(virtualFile);
        com.intellij.openapi.editor.Document document =
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(virtualFile);
        assertNotNull(document);
        WriteAction.runAndWait(() -> com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
            getProject(), () -> document.setText("unsaved edit")));
        assertTrue(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document));

        String text = LayerRulesWriter.render(List.of(), IdeProjectLayers.draftFromModules(snapshot), List.of());
        LayerRulesFile.write(getProject(), root, text);
        assertEquals(text, Files.readString(file));
        assertEquals(text, document.getText());
        assertFalse(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document));
        assertTrue(LayerRulesParser.parse(Files.readString(file)).problems().toString(),
            LayerRulesParser.parse(Files.readString(file)).ok());
    }

    private IdeProjectLayers.ModuleInfo module(String name) {
        return snapshot.modules().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    /** Headless dialogs have no window, so read the center panel the dialog builds. */
    private static JComponent centerPanel(LayersEditorDialog dialog) throws Exception {
        java.lang.reflect.Method method = LayersEditorDialog.class.getDeclaredMethod("createCenterPanel");
        method.setAccessible(true);
        return (JComponent) method.invoke(dialog);
    }

    private static void click(JComponent panel, String text) {
        for (JButton button : UIUtil.findComponentsOfType(panel, JButton.class)) {
            if (text.equals(button.getText())) {
                button.doClick();
                return;
            }
        }
        fail("no button '" + text + "'");
    }

    private static String listContents(JList<?> list) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.getModel().getSize(); i++) out.append(list.getModel().getElementAt(i)).append('\n');
        return out.toString();
    }

    private static int indexOf(JList<?> list, String contains) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            if (String.valueOf(list.getModel().getElementAt(i)).contains(contains)) return i;
        }
        fail("'" + contains + "' not in:\n" + listContents(list));
        return -1;
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int from = text.indexOf(needle); from >= 0; from = text.indexOf(needle, from + 1)) count++;
        return count;
    }
}
