package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class IgnoredCompilationFilesTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        IgnoredCompilationFiles state = IgnoredCompilationFiles.getInstance(getProject());
        for (IgnoredCompilationFiles.Entry entry : state.list()) state.remove(entry.path());
    }

    public void testExactPathEnableDisableDeduplicateAndRemove() {
        IgnoredCompilationFiles state = IgnoredCompilationFiles.getInstance(getProject());
        assertTrue(state.add("src/main/java/App.java"));
        assertTrue(state.add("./src/main/java/App.java"));
        assertEquals(1, state.list().size());
        assertTrue(state.isIgnored("src/main/java/App.java"));

        state.setEnabled("src/main/java/App.java", false);
        assertFalse(state.isIgnored("src/main/java/App.java"));
        assertFalse(state.list().get(0).enabled());

        state.setEnabled("src/main/java/App.java", true);
        state.remove("src/main/java/App.java");
        assertTrue(state.list().isEmpty());
    }

    public void testOutsideProjectAbsolutePathRejected() {
        Path outside = Path.of(System.getProperty("java.io.tmpdir"), "outside", "App.java");
        assertFalse(IgnoredCompilationFiles.getInstance(getProject()).add(outside.toString()));
        assertFalse(IgnoredCompilationFiles.getInstance(getProject()).add("build.gradle.kts"));
    }

    public void testUnrelatedSnapshotPathRejectedAndPrivateMacAliasAccepted() {
        String base = getProject().getBasePath();
        assertNotNull(base);
        assertNull(DiagnosticPathMatcher.normalizeProjectPath(getProject(),
            "/tmp/prepushchecker-snapshot.ABC/worktree/src/App.kt"));
        assertNull(DiagnosticPathMatcher.relativeToRoot("/private/Users/demo/src/App.kt", "/Users/demo"));
        if (com.intellij.openapi.util.SystemInfoRt.isMac) {
            assertEquals("src/App.kt", DiagnosticPathMatcher.relativeToRoot(
                "/private/var/work/src/App.kt", "/var/work"));
        }
    }

    public void testMixedErrorsFilterAndCheckboxRefreshRestoresRawError() {
        IgnoredCompilationFiles state = IgnoredCompilationFiles.getInstance(getProject());
        state.add("src/A.java");
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        service.setErrors(List.of(
            "[src/A.java (1, 2)] ignored",
            "[src/B.java (3, 4)] blocking",
            "Compilation was aborted."));

        assertEquals(List.of("[src/B.java (3, 4)] blocking", "Compilation was aborted."),
            service.getErrors());
        state.setEnabled("src/A.java", false);
        assertEquals(3, service.getErrors().size());
    }

    public void testIgnoredFileNamedExceptionIsSuppressed() {
        IgnoredCompilationFiles state = IgnoredCompilationFiles.getInstance(getProject());
        state.add("src/main/java/com/foo/PaymentException.java");
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        service.setErrors(List.of(
            "[src/main/java/com/foo/PaymentException.java 12:5] cannot find symbol"));
        assertTrue(service.getErrors().isEmpty());
    }

    public void testIgnoredFileWithExceptionInMessageIsSuppressed() {
        IgnoredCompilationFiles state = IgnoredCompilationFiles.getInstance(getProject());
        state.add("Foo.java");
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        service.setErrors(List.of(
            "[Foo.java 5:9] unreported exception java.io.IOException; "
                + "must be caught or declared to be thrown"));
        assertTrue(service.getErrors().isEmpty());
    }

    public void testInfrastructureFailuresAlwaysBlockEvenIfEverythingIgnored() {
        IgnoredCompilationFiles state = IgnoredCompilationFiles.getInstance(getProject());
        state.add("Foo.java");
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        service.setErrors(List.of(
            "[unknown] java.lang.OutOfMemoryError: Java heap space",
            "[Foo.java 1:1] internal compiler error: NullPointerException"));
        assertEquals(2, service.getErrors().size());
    }

    public void testCanIgnoreDiagnosticForExceptionNamedFile() {
        assertTrue(DiagnosticPathMatcher.canIgnoreDiagnostic(getProject(),
            "[src/main/java/com/foo/PaymentException.java 12:5] cannot find symbol"));
    }

    public void testCompilerCallbackAccountsForEveryErrorBeforeApplyingDisplayLimit() throws Exception {
        var path = Path.of(getProject().getBasePath(), "src", "Ignored (legacy).java");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, "class Ignored {}");
        var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        var state = IgnoredCompilationFiles.getInstance(getProject());
        assertTrue(state.add(file.getPath()));
        var message = new com.intellij.compiler.CompilerMessageImpl(getProject(),
            com.intellij.openapi.compiler.CompilerMessageCategory.ERROR, "cannot find symbol", file, 5, 2, null);
        var messages = new com.intellij.openapi.compiler.CompilerMessage[CompilationErrorService.MAX_RETAINED_ERRORS + 2];
        java.util.Arrays.fill(messages, message);
        var raw = CompilationSupport.formatCompilerMessages(getProject(), messages, messages.length);
        assertEmpty(DiagnosticPathMatcher.filterIgnored(getProject(), raw));
        state.setEnabled("src/Ignored (legacy).java", false);
        assertFalse(DiagnosticPathMatcher.filterIgnored(getProject(), raw).isEmpty());
        state.setEnabled("src/Ignored (legacy).java", true);
        assertFalse(DiagnosticPathMatcher.filterIgnored(getProject(),
            CompilationSupport.formatCompilerMessages(getProject(), messages, messages.length + 1)).isEmpty());
        messages[messages.length - 1] = new com.intellij.compiler.CompilerMessageImpl(getProject(),
            com.intellij.openapi.compiler.CompilerMessageCategory.ERROR, "compiler crashed");
        assertFalse(DiagnosticPathMatcher.filterIgnored(getProject(),
            CompilationSupport.formatCompilerMessages(getProject(), messages, messages.length)).isEmpty());
    }

    public void testIgnoredOnlyIdeResultSkipsRecoveryRebuild() throws Exception {
        IgnoredCompilationFiles.getInstance(getProject()).add("src/A.java");
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            getProject(),
            new IdeCompilationRunner.AttemptResult(
                List.of("[src/A.java (1, 1)] ignored"), false, 1),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });
        assertFalse(outcome.rebuilt());
        assertEquals(0, rebuilds.get());
    }
}
