package com.github.prepushchecker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Future;

/**
 * Saving from a background thread must run in a write-safe context: the old
 * {@code invokeAndWait(save, ModalityState.any())} logged "Write-unsafe context!", which the
 * test logger turns into a failure.
 */
public class WriteSafeEdtTest extends BasePlatformTestCase {

    public void testSaveAllDocumentsFromBackgroundThread() throws Exception {
        VirtualFile file = myFixture.getTempDirFixture().createFile("A.txt", "old");
        Document document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("new"));
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document));

        Future<?> save = ApplicationManager.getApplication().executeOnPooledThread(WriteSafeEdt::saveAllDocuments);
        PlatformTestUtil.waitForFuture(save, 20_000);

        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
        assertEquals("new", new String(file.contentsToByteArray(), StandardCharsets.UTF_8));
    }

    public void testInvokeAndWaitRunsDirectlyOnEdt() {
        boolean[] ran = {false};
        assertTrue(WriteSafeEdt.invokeAndWait(() -> ran[0] = true, Duration.ofSeconds(1)));
        assertTrue(ran[0]);
    }
}
