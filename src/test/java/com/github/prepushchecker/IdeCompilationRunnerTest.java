package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class IdeCompilationRunnerTest extends BasePlatformTestCase {
    public void testFileLocatedErrorsAreReturnedWithoutRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            new IdeCompilationRunner.AttemptResult(
                List.of("[App.java (12:5)] ';' expected"), false, 1),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertFalse(outcome.rebuilt());
        assertEquals(List.of("[App.java (12:5)] ';' expected"), outcome.finalResult().errors());
        assertEquals(0, rebuilds.get());
    }

    public void testInfrastructureFailureRunsOneCleanRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            new IdeCompilationRunner.AttemptResult(
                List.of("Compilation failed with an unknown compiler error."), false, 1),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertTrue(outcome.rebuilt());
        assertTrue(outcome.finalResult().errors().isEmpty());
        assertEquals(1, rebuilds.get());
    }

    public void testUnknownLocationErrorCountsAsInfrastructure() {
        assertTrue(IdeCompilationRunner.looksLikeInfrastructureFailure(
            List.of("[unknown] Internal error: java.lang.NullPointerException")));
        assertFalse(IdeCompilationRunner.looksLikeInfrastructureFailure(
            List.of("[src/A.java (3:1)] cannot find symbol")));
        assertFalse(IdeCompilationRunner.looksLikeInfrastructureFailure(
            List.of("[src/A.java (3:1)] incompatible types: java.lang.String cannot be converted")));
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
