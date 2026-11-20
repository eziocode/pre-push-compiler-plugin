package com.github.prepushchecker.review;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.xmlb.XmlSerializer;

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

    public void testPushAfterResolutionRequiresOnlyTheFixCommit() throws Exception {
        Path repo = com.intellij.openapi.util.io.FileUtil.createTempDirectory("review-retry", "", true).toPath().toRealPath();
        Path file = repo.resolve("Example.java");
        Files.writeString(file, "class Example { int old; }\n");
        Files.writeString(repo.resolve(ReviewRules.DEFAULT_FILE_NAME), "---\nmandatory: true\n---\nReview Java.\n");
        git(repo, "init", "-q");
        git(repo, "config", "user.name", "Tester");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "add", ".");
        git(repo, "commit", "-qm", "initial");
        ReviewStateStore store = ReviewStateStore.getInstance(getProject());
        ReviewService service = ReviewService.getInstance(getProject());
        try {
            String old = ReviewGit.blobsAt(repo, "HEAD", List.of("Example.java")).get("Example.java");
            ReviewFinding finding = new ReviewFinding(file.toString(), 1, "R", "Fix old", "");
            ReviewFinding remaining = new ReviewFinding(file.toString(), 1, "R", "Second fix", "");
            store.record(file.toString(), old, List.of(finding, remaining));
            Files.writeString(file, "class Example { int partial; }\n");
            String partial = ReviewGit.hashObjects(repo, List.of("Example.java")).get("Example.java");
            assertTrue(store.resolve(finding.id, "manual", partial));
            git(repo, "commit", "-qam", "first fix");
            var stillOpen = service.checkPush(repo, List.of(file), null, null, null, Duration.ZERO);
            assertEquals(remaining.id, stillOpen.firstBlockingFindingId());
            assertEquals(1, stillOpen.lines().size());
            Files.writeString(file, "class Example { int fixed; }\n");
            String fixed = ReviewGit.hashObjects(repo, List.of("Example.java")).get("Example.java");
            assertTrue(store.resolve(remaining.id, "ai", fixed));
            var saved = XmlSerializer.serialize(store.getState());
            store.loadState(XmlSerializer.deserialize(saved, ReviewStateStore.StateBean.class));
            assertFalse(store.isApproved(file.toString(), old));
            assertFalse(store.isApproved(file.toString(), partial));
            for (var mode : ReviewSettings.Mode.values()) {
                ReviewSettings.setMode(getProject(), mode);
                var retry = service.checkPush(repo, List.of(file), null, null, null, Duration.ZERO);
                assertNull(retry.firstBlockingFindingId());
                assertTrue(retry.lines().toString(), retry.lines().stream().anyMatch(s -> s.contains("Commit") && s.contains("fix")));
            }
            git(repo, "commit", "-qam", "include fix");
            Files.writeString(repo.resolve("Other.java"), "class Other {}\n");
            git(repo, "add", "Other.java");
            git(repo, "commit", "-qm", "unrelated");
            assertEquals(ReviewService.PushReview.CLEAR,
                service.checkPush(repo, List.of(file), null, null, null, Duration.ZERO));
            Files.writeString(file, "class Example { int later; }\n");
            git(repo, "commit", "-qam", "edit again");
            ReviewSettings.setMode(getProject(), ReviewSettings.Mode.ON_DEMAND);
            var changed = service.checkPush(repo, List.of(file), null, null, null, Duration.ZERO);
            assertTrue(changed.lines().toString(), changed.lines().stream().anyMatch(s -> s.contains("review required")));
        } finally {
            ReviewSettings.setMode(getProject(), ReviewSettings.Mode.ON_DEMAND);
            store.clear();
        }
    }

    public void testResolvedVersionIsNotReReviewedAfterLaterEdits() throws Exception {
        for (boolean mandatory : new boolean[]{true, false}) {
            Path repo = com.intellij.openapi.util.io.FileUtil.createTempDirectory("review-loop", "", true).toPath().toRealPath();
            Path file = repo.resolve("Example.java");
            Files.writeString(file, "class Example { int old; }\n");
            Files.writeString(repo.resolve(ReviewRules.DEFAULT_FILE_NAME),
                (mandatory ? "---\nmandatory: true\n---\n" : "") + "Review Java.\n");
            initRepo(repo);
            ReviewStateStore store = ReviewStateStore.getInstance(getProject());
            ReviewService service = ReviewService.getInstance(getProject());
            try {
                String old = ReviewGit.blobsAt(repo, "HEAD", List.of("Example.java")).get("Example.java");
                ReviewFinding finding = new ReviewFinding(file.toString(), 1, "R", "Fix old", "");
                store.record(file.toString(), old, List.of(finding));
                Files.writeString(file, "class Example { int fixed; }\n");
                String fixed = ReviewGit.hashObjects(repo, List.of("Example.java"), true).get("Example.java");
                assertTrue(store.resolve(finding.id, "ai", fixed));
                // Edited again after the fix (reformat on save, more work): the flagged commit must
                // not be sent to the AI again, and no finding may re-open.
                Files.writeString(file, "class Example { int fixed; int more; }\n");
                ReviewSettings.setMode(getProject(), ReviewSettings.Mode.ON_PUSH);
                var retry = service.checkPush(repo, List.of(file), null, null, null, Duration.ZERO);
                if (mandatory) {
                    assertNull(retry.firstBlockingFindingId());
                    assertTrue(retry.lines().toString(), retry.lines().stream().anyMatch(s -> s.contains("Commit the resolved fix")));
                } else {
                    assertEquals(ReviewService.PushReview.CLEAR, retry);
                }
                assertFalse(store.review(file.toString()).hasOpenFindings());
            } finally {
                ReviewSettings.setMode(getProject(), ReviewSettings.Mode.ON_DEMAND);
                store.clear();
            }
        }
    }

    public void testReformattedFixCommitIsApprovedWithoutAiCall() throws Exception {
        Path repo = com.intellij.openapi.util.io.FileUtil.createTempDirectory("review-reformat", "", true).toPath().toRealPath();
        Path file = repo.resolve("Example.java");
        Files.writeString(file, "class Example { int old; }\n");
        Files.writeString(repo.resolve(ReviewRules.DEFAULT_FILE_NAME), "---\nmandatory: true\n---\nReview Java.\n");
        initRepo(repo);
        ReviewStateStore store = ReviewStateStore.getInstance(getProject());
        ReviewService service = ReviewService.getInstance(getProject());
        try {
            String old = ReviewGit.blobsAt(repo, "HEAD", List.of("Example.java")).get("Example.java");
            ReviewFinding finding = new ReviewFinding(file.toString(), 1, "R", "Fix old", "");
            store.record(file.toString(), old, List.of(finding));
            Files.writeString(file, "class Example { int fixed; }\n");
            String fixed = ReviewGit.hashObjects(repo, List.of("Example.java"), true).get("Example.java");
            assertTrue(store.resolve(finding.id, "ai", fixed));
            // Commit-time reformat and import optimisation: the committed blob is not the approved one.
            Files.writeString(file, "import java.util.List;\n\nclass Example {\n    int fixed;\n}\n");
            git(repo, "commit", "-qam", "fix, reformatted");
            for (var mode : ReviewSettings.Mode.values()) {
                ReviewSettings.setMode(getProject(), mode);
                assertEquals(ReviewService.PushReview.CLEAR,
                    service.checkPush(repo, List.of(file), null, null, null, Duration.ZERO));
            }
            String committed = ReviewGit.blobsAt(repo, "HEAD", List.of("Example.java")).get("Example.java");
            assertTrue(store.isApproved(file.toString(), committed));
            assertTrue(store.resolvedNotes(file.toString()).toString(),
                store.resolvedNotes(file.toString()).contains("R: Fix old"));
            // A real change on top is still reviewed.
            Files.writeString(file, "import java.util.List;\n\nclass Example {\n    int fixed;\n    int added;\n}\n");
            git(repo, "commit", "-qam", "real change");
            ReviewSettings.setMode(getProject(), ReviewSettings.Mode.ON_DEMAND);
            var changed = service.checkPush(repo, List.of(file), null, null, null, Duration.ZERO);
            assertTrue(changed.lines().toString(), changed.lines().stream().anyMatch(s -> s.contains("review required")));
        } finally {
            ReviewSettings.setMode(getProject(), ReviewSettings.Mode.ON_DEMAND);
            store.clear();
        }
    }

    public void testCheckedFindingsSurviveReloadAndDropWhenResolved() {
        ReviewStateStore store = ReviewStateStore.getInstance(getProject());
        ReviewPanel panel = new ReviewPanel(getProject());
        try {
            ReviewFinding a = new ReviewFinding("/r/A.java", 1, "", "A", "");
            ReviewFinding b = new ReviewFinding("/r/A.java", 2, "", "B", "");
            ReviewFinding c = new ReviewFinding("/r/C.java", 3, "", "C", "");
            store.record("/r/A.java", "a1", List.of(a, b));
            store.record("/r/C.java", "c1", List.of(c));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEmpty(panel.checkedFindings());
            panel.setAllChecked(true);
            assertEquals(3, panel.checkedFindings().size());
            store.resolve(b.id, "manual", null);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of(a.id, c.id).stream().sorted().toList(),
                panel.checkedFindings().stream().map(f -> f.id).sorted().toList());
            panel.setAllChecked(false);
            assertEmpty(panel.checkedFindings());
        } finally {
            Disposer.dispose(panel);
            store.clear();
        }
    }

    public void testOnlyImportChanges() {
        assertTrue(ReviewService.onlyImportChanges("@@ -1,2 +1,3 @@\n+import java.util.List;\n class A {}\n"));
        assertFalse(ReviewService.onlyImportChanges("@@ -1,2 +1,3 @@\n+import java.util.List;\n+int x;\n"));
        assertFalse(ReviewService.onlyImportChanges("@@ -1 +1 @@\n class A {}\n"));
    }

    private static void initRepo(Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.name", "Tester");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "add", ".");
        git(repo, "commit", "-qm", "initial");
    }

    public void testReviewReusesManualResolutionForIdenticalTarget() throws Exception {
        Path repo = com.intellij.openapi.util.io.FileUtil.createTempDirectory("review-reuse", "", true).toPath().toRealPath();
        Files.writeString(repo.resolve(ReviewRules.DEFAULT_FILE_NAME), "Review Java.\n");
        var target = new ReviewService.Target(repo, "Example.java", "fixed", "1: class Example {}");
        ReviewStateStore store = ReviewStateStore.getInstance(getProject());
        try {
            ReviewFinding finding = new ReviewFinding(target.absolutePath(), 1, "", "First", "");
            ReviewFinding remaining = new ReviewFinding(target.absolutePath(), 2, "", "Second", "");
            store.record(target.absolutePath(), "original", List.of(finding, remaining));
            assertTrue(store.resolve(finding.id, "manual", target.contentId()));
            var partial = ReviewService.getInstance(getProject()).review(List.of(target), null, Duration.ZERO);
            assertEmpty(partial.errors());
            assertFalse(store.isApproved(target.absolutePath(), target.contentId()));
            assertTrue(store.finding(remaining.id).isOpen());
            assertTrue(store.resolve(remaining.id, "manual", target.contentId()));
            var result = ReviewService.getInstance(getProject()).review(List.of(target), null, Duration.ZERO);
            assertEmpty(result.errors());
            assertEquals(0, result.reviewed());
        } finally {
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
