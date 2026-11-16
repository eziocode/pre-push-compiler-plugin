package com.github.prepushchecker.inline;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;
import java.util.concurrent.Future;

public class InlineContextCollectorTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String CARET = "<caret>";

    private InlineSuggestSettings.State defaults() {
        return new InlineSuggestSettings.State();
    }

    private boolean fixturesAdded;

    private void addFixtures() {
        if (fixturesAdded) return;
        fixturesAdded = true;
        myFixture.addFileToProject("p/Helper.java",
            "package p;\npublic class Helper { private int hidden() {return 0;} "
                + "public String greet(String n) { return n; } }");
        myFixture.addFileToProject("q/Other.java", "package q;\npublic class Other { public int x; }");
    }

    private InlineContext collectAtCaret(String name, InlineSuggestSettings.State s) throws Exception {
        addFixtures();
        String text = "package p;\nimport q.Other;\nimport java.util.List;\n"
            + "public class Main { void run() { Helper h = null; Other o = null; List<String> l = null; <caret> } }";
        return collectText("p/" + name, text, s, true);
    }

    private InlineContext collectText(String name, String text, InlineSuggestSettings.State s) throws Exception {
        return collectText(name, text, s, false);
    }

    private InlineContext collectText(String name, String text, InlineSuggestSettings.State s, boolean ignored)
            throws Exception {
        int caret = text.indexOf(CARET);
        String clean = text.replace(CARET, "");
        VirtualFile vf = myFixture.findFileInTempDir(name);
        if (vf == null) {
            vf = myFixture.addFileToProject(name, clean).getVirtualFile();
        }
        final VirtualFile file = vf;
        myFixture.configureFromExistingVirtualFile(file);
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        Future<InlineContext> f = ApplicationManager.getApplication().executeOnPooledThread(
            () -> InlineContextCollector.collect(getProject(), file, doc, caret, s));
        return PlatformTestUtil.waitForFuture(f, 30_000);
    }

    private static String textOf(InlineContext c, String title) {
        return c.related().stream().filter(s -> s.title().equals(title)).findFirst().orElseThrow().text();
    }

    public void testJavaSignaturesIncludeSamePackageAndSkipLibraries() throws Exception {
        InlineContext c = collectAtCaret("Main.java", defaults());
        List<String> titles = c.related().stream().map(InlineContext.Snippet::title).toList();
        assertTrue(titles.toString(), titles.contains("p.Helper"));
        assertTrue(titles.contains("q.Other"));
        assertFalse(titles.contains("java.util.List"));
        String helper = textOf(c, "p.Helper");
        assertTrue(helper.contains("String greet(String n)"));
        assertFalse(helper.contains("hidden"));
        assertFalse(helper.contains("return n"));
    }

    public void testPrefixSuffixLimitsAndEdges() throws Exception {
        String big = "a".repeat(6000) + CARET + "b".repeat(3000);
        InlineContext c = collectText("Big.txt", big, defaults());
        assertEquals(4000, c.prefix().length());
        assertEquals(1000, c.suffix().length());
        InlineContext empty = collectText("Empty.txt", CARET, defaults());
        assertEquals("", empty.prefix());
        assertEquals("", empty.suffix());
    }

    public void testNonJavaAndDumbModeGiveNoSignatures() throws Exception {
        assertTrue(collectText("notes.txt", "hello " + CARET, defaults()).related().isEmpty());
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            try {
                InlineContext c = collectAtCaret("Main.java", defaults());
                assertNotNull(c);
                assertTrue(c.related().stream().noneMatch(s -> s.title().equals("p.Helper")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void testBudgetAndSwitch() throws Exception {
        InlineSuggestSettings.State s = defaults();
        s.contextChars = 0;
        assertTrue(collectAtCaret("Main.java", s).related().isEmpty());
        s = defaults();
        s.useProjectContext = false;
        assertTrue(collectAtCaret("Main.java", s).related().isEmpty());
    }

    public void testSensitiveFileIsRejected() throws Exception {
        assertNull(collectText(".env", "KEY=" + CARET, defaults()));
    }

    public void testEnumAndRecordSnippets() throws Exception {
        myFixture.addFileToProject("p/Color.java", "package p;\npublic enum Color { RED, GREEN; int code() { return 1; } }");
        myFixture.addFileToProject("p/Point.java", "package p;\npublic record Point(int x, int y) {}");
        String text = "package p;\npublic class Main { Color c; Point pt; <caret> }";
        InlineContext c = collectText("p/Main.java", text, defaults());
        assertNotNull(c);
        assertTrue(textOf(c, "p.Point"), textOf(c, "p.Point").contains("record Point(int x, int y)"));
        assertEquals(1, textOf(c, "p.Point").split("int x", -1).length - 1);
        assertTrue(textOf(c, "p.Color").contains("int code()"));
    }

    public void testPrivateFieldsIncluded() throws Exception {
        myFixture.addFileToProject("p/Holder.java", "package p;\npublic class Holder { private int secret; }");
        InlineContext c = collectText("p/Main.java", "package p;\npublic class Main { Holder h; <caret> }", defaults());
        assertTrue(textOf(c, "p.Holder").contains("int secret"));
    }
}
