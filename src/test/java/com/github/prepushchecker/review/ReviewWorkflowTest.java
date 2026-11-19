package com.github.prepushchecker.review;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class ReviewWorkflowTest extends BasePlatformTestCase {
    public void testPreviewFormatsChangedLinesAndAppliesAsOneUndo() {
        String source = "package demo;\n\nclass Example {\n    int   untouched;\n"
            + "    void test() {\n        int n = 1;\n    }\n}\n";
        var file = myFixture.configureByText("Example.java", source).getVirtualFile();
        var fix = new ReviewPrompt.Fix(6, 6, "if(true){\nint n=2;\n}", List.of("import java.util.List;"));
        String preview = ReviewFixFormatter.format(getProject(), file, source, ReviewPrompt.apply(source, fix));
        assertTrue(preview, preview.contains("        if (true) {\n            int n = 2;\n        }"));
        assertTrue(preview, preview.contains("    int   untouched;"));
        assertTrue(preview, preview.contains("import java.util.List;"));
        Document document = myFixture.getEditor().getDocument();
        assertEquals(source, document.getText());
        assertFalse(ReviewFixer.applyPreview(getProject(), document, "stale", preview));
        assertTrue(ReviewFixer.applyPreview(getProject(), document, source, preview));
        assertEquals(preview, document.getText());
        myFixture.performEditorAction(IdeActions.ACTION_UNDO);
        assertEquals(source, document.getText());
    }

    public void testFindingsBlockThenDisappearAndApprovedContentPasses() throws Exception {
        Path repo = com.intellij.openapi.util.io.FileUtil.createTempDirectory("review-workflow", "", true).toPath().toRealPath();
        Path file = repo.resolve("Example.java");
        Files.writeString(file, "class Example {}\n");
        Files.writeString(repo.resolve(ReviewRules.DEFAULT_FILE_NAME), "---\nmandatory: true\n---\nReview Java.\n");
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=Tester", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
            "commit", "-qm", "initial");
        ReviewStateStore store = ReviewStateStore.getInstance(getProject());
        ReviewService service = ReviewService.getInstance(getProject());
        ReviewPanel panel = new ReviewPanel(getProject());
        try {
            JTree tree = UIUtil.findComponentOfType(panel, JTree.class);
            assertNotNull(tree);
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            String blob = ReviewGit.blobsAt(repo, "HEAD", List.of("Example.java")).get("Example.java");
            ReviewFinding first = new ReviewFinding(file.toString(), 1, "", "First", "");
            ReviewFinding last = new ReviewFinding(file.toString(), 1, "", "Second", "");
            store.record(file.toString(), blob, List.of(first, last));
            store.record(repo.resolve("Clean.java").toString(), "clean", List.of());
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(1, root.getChildCount());
            var block = service.checkPush(repo, List.of(file), null, null, null, Duration.ofSeconds(5));
            assertEquals(first.id, block.firstBlockingFindingId());
            assertFalse(block.lines().isEmpty());
            panel.select(first.id);
            store.resolve(first.id, "manual", blob);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            var selected = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            assertEquals(last.id, ((ReviewFinding) selected.getUserObject()).id);
            store.resolve(last.id, "ai", blob);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(0, root.getChildCount());
            assertNull(tree.getSelectionPath());
            assertTrue(store.isApproved(file.toString(), blob));
            assertEquals(ReviewService.PushReview.CLEAR,
                service.checkPush(repo, List.of(file), null, null, null, Duration.ofSeconds(5)));
        } finally {
            Disposer.dispose(panel);
            store.clear();
        }
    }

    private static void git(Path repo, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output, 0, p.waitFor());
    }
}
