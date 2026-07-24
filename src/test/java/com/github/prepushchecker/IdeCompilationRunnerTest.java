package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class IdeCompilationRunnerTest extends BasePlatformTestCase {
    public void testAnyCompilerFailureRunsOneAuthoritativeCleanRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            new IdeCompilationRunner.AttemptResult(
                List.of("[App.java] ';' expected"), false, 1),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertTrue(outcome.rebuilt());
        assertTrue(outcome.finalResult().errors().isEmpty());
        assertEquals(1, rebuilds.get());
    }

    public void testCleanCompilerResultDoesNotRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            new IdeCompilationRunner.AttemptResult(List.of(), false, 0),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertFalse(outcome.rebuilt());
        assertEquals(0, rebuilds.get());
    }

    public void testAbortedCompilerResultDoesNotRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            new IdeCompilationRunner.AttemptResult(
                List.of("Compilation was aborted."), true, 0),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertFalse(outcome.rebuilt());
        assertEquals(0, rebuilds.get());
    }

    public void testErrorServiceNeverReusesCompletedVerdicts() {
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        service.setErrors(List.of("old error"));
        assertEquals(List.of("old error"), service.getErrors());
        service.clearErrors();
        assertTrue(service.getErrors().isEmpty());
    }

    public void testExternalServerIsProjectService() {
        assertSame(
            getProject().getService(PrePushLocalServer.class),
            getProject().getService(PrePushLocalServer.class));
    }
}
