package com.github.prepushchecker.inline;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class InlineGhostTextTest extends BasePlatformTestCase {

    public void testShowAndAcceptIsOneUndoStep() {
        myFixture.configureByText("A.java", "int a = <caret>;");
        Editor e = myFixture.getEditor();
        InlineGhostText.show(e, e.getCaretModel().getOffset(), "b +\n    c");
        assertEquals("b +\n    c", InlineGhostText.current(e));
        assertEquals(1, e.getInlayModel().getInlineElementsInRange(0, e.getDocument().getTextLength()).size());
        assertEquals(1, e.getInlayModel().getBlockElementsInRange(0, e.getDocument().getTextLength()).size());
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB);
        myFixture.checkResult("int a = b +\n    c<caret>;");
        assertFalse(InlineGhostText.isShowing(e));
        myFixture.performEditorAction(IdeActions.ACTION_UNDO);
        myFixture.checkResult("int a = <caret>;");
    }

    public void testTabWithoutSuggestionStillIndents() {
        myFixture.configureByText("A.txt", "<caret>x");
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB);
        assertFalse(myFixture.getEditor().getDocument().getText().startsWith("x"));
    }

    public void testEscapeDismisses() {
        myFixture.configureByText("A.txt", "<caret>");
        InlineGhostText.show(myFixture.getEditor(), 0, "abc");
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ESCAPE);
        assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
        myFixture.checkResult("<caret>");
    }

    public void testTypeThrough() {
        myFixture.configureByText("A.txt", "<caret>");
        Editor e = myFixture.getEditor();
        InlineGhostText.show(e, 0, "abc");
        assertTrue(InlineGhostText.typeThrough(e, 0, "a"));
        assertEquals("bc", InlineGhostText.current(e));
        assertEquals(1, InlineGhostText.offset(e));
        assertFalse(InlineGhostText.typeThrough(e, 1, "x"));
        assertEquals("bc", InlineGhostText.current(e));
        assertTrue(InlineGhostText.typeThrough(e, 1, "bc"));
        assertFalse(InlineGhostText.isShowing(e));
    }

    public void testTabInOtherEditorDoesNotAccept() {          // Review Focus 3
        PsiFile a = myFixture.addFileToProject("A.txt", "");
        Document doc = FileDocumentManager.getInstance().getDocument(a.getVirtualFile());
        assertNotNull(doc);
        Editor ea = EditorFactory.getInstance().createEditor(doc, getProject());
        try {
            InlineGhostText.show(ea, 0, "abc");
            myFixture.configureByText("B.txt", "<caret>x");
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB);
            assertEquals("", ea.getDocument().getText());
            assertTrue(InlineGhostText.isShowing(ea));
        } finally {
            InlineGhostText.dismiss(ea);
            EditorFactory.getInstance().releaseEditor(ea);
        }
    }

    public void testShowBlankIsNoop() {
        myFixture.configureByText("A.txt", "<caret>");
        InlineGhostText.show(myFixture.getEditor(), 0, "  ");
        assertFalse(InlineGhostText.isShowing(myFixture.getEditor()));
    }
}
