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

    public void testSnapshotAndPrivateMacPathsNormalizeToSameExactFile() {
        String base = getProject().getBasePath();
        assertNotNull(base);
        assertEquals("src/App.kt", DiagnosticPathMatcher.normalizeProjectPath(getProject(),
            "/tmp/prepushchecker-snapshot.ABC/worktree/src/App.kt"));
        String privateAlias = base.startsWith("/private/") ? base : "/private" + base;
        assertEquals("src/App.kt", DiagnosticPathMatcher.normalizeProjectPath(getProject(),
            privateAlias + "/src/App.kt"));
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
