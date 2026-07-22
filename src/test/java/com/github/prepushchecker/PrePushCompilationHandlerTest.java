package com.github.prepushchecker;

import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class PrePushCompilationHandlerTest extends BasePlatformTestCase {
    public void testPrePushResultCacheReusesOnlyCleanMatchingKeyWithinTtl() {
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        String key = "commit=a\npath=src/main/java/App.java\n";
        long recordedAt = 1_000L;

        service.recordPrePushResult(key, List.of(), recordedAt);

        assertEquals(
            List.of(),
            service.tryReusePrePushResult(
                key, recordedAt + CompilationErrorService.PRE_PUSH_SUCCESS_TTL_MILLIS - 1));
        assertNull(service.tryReusePrePushResult("commit=b\npath=src/main/java/App.java\n"));
        assertNull(service.tryReusePrePushResult(
            key, recordedAt + CompilationErrorService.PRE_PUSH_SUCCESS_TTL_MILLIS));
    }

    public void testPrePushResultCacheDoesNotReuseFailures() {
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        String key = "commit=a\npath=src/main/java/App.java\n";

        service.recordPrePushResult(key, List.of("error"), 1_000L);

        assertNull(service.tryReusePrePushResult(key, 1_001L));
        assertEquals(List.of("error"), service.getErrors());
    }

    public void testManualCompileCompletionInvalidatesPrePushSuccess() {
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        String key = "commit=a\npath=src/main/java/App.java\n";

        service.recordPrePushResult(key, List.of(), 1_000L);
        service.recordCompletion(true, java.util.Collections.emptyMap(), List.of());

        assertNull(service.tryReusePrePushResult(key, 1_001L));
    }

    public void testGeneralFreshnessCacheDoesNotReuseFailures() {
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());

        service.recordCompletion(true, java.util.Collections.emptyMap(), List.of("error"));
        assertNull(service.tryReuse(List.of()));

        service.recordCompletion(true, java.util.Collections.emptyMap(), List.of());
        assertEquals(List.of(), service.tryReuse(List.of()));
    }

    public void testStaleFailureClassifierRecognizesAnnotationClasspathCollapse() {
        CompilationFailureClassifier.RecoveryDecision decision =
            CompilationFailureClassifier.classify(List.of(
                "[CanvasCSCriptCases.java] Cannot find annotation method 'value()' in type "
                    + "'org.springframework.stereotype.Component': class file for "
                    + "org.springframework.stereotype.Component not found",
                "[InventoryTestCases.java] Cannot find annotation method 'name()' in type "
                    + "'javax.persistence.Table': class file for javax.persistence.Table not found",
                "[CrmProductInfo.java] unknown enum constant javax.persistence.GenerationType.IDENTITY"
            ));

        assertTrue(decision.shouldRecover());
        assertFalse(decision.reason().isBlank());
    }

    public void testStaleFailureClassifierRecognizesWidespreadPackageFailures() {
        CompilationFailureClassifier.RecoveryDecision decision =
            CompilationFailureClassifier.classify(List.of(
                "[src/main/java/One.java (1, 1)] package com.example.shared does not exist",
                "[src/main/java/Two.java (1, 1)] cannot find symbol class SharedType",
                "[src/main/java/Three.java (1, 1)] package com.example.shared does not exist",
                "[src/main/java/Four.java (1, 1)] cannot find symbol class SharedType"
            ));

        assertTrue(decision.shouldRecover());
    }

    public void testStaleFailureClassifierRecognizesRawMavenClasspathCollapse() {
        CompilationFailureClassifier.RecoveryDecision decision =
            CompilationFailureClassifier.classify(List.of(
                "[ERROR] /project/src/main/java/One.java:[1,1] package shared does not exist",
                "[ERROR] /project/src/main/java/Two.java:[1,1] cannot find symbol",
                "[ERROR] /project/src/main/java/Three.java:[1,1] package shared does not exist",
                "[ERROR] /project/src/main/java/Four.java:[1,1] cannot find symbol"
            ));

        assertTrue(decision.shouldRecover());
    }

    public void testStaleFailureClassifierRejectsOrdinaryCompileErrors() {
        assertFalse(CompilationFailureClassifier.classify(List.of(
            "[src/main/java/App.java (10, 5)] cannot find symbol class MissingType"
        )).shouldRecover());
        assertFalse(CompilationFailureClassifier.classify(List.of(
            "[src/main/java/App.java (10, 5)] ';' expected"
        )).shouldRecover());
    }

    public void testStaleCompilerRecoveryRunsOneCleanRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.AttemptResult initial = new IdeCompilationRunner.AttemptResult(
            List.of("[App.java] class file for javax.persistence.Table not found"), false, 1);

        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            initial,
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertTrue(outcome.rebuilt());
        assertTrue(outcome.finalResult().errors().isEmpty());
        assertEquals(1, rebuilds.get());
    }

    public void testOrdinaryCompilerFailureDoesNotRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            new IdeCompilationRunner.AttemptResult(
                List.of("[App.java] cannot find symbol class MissingType"), false, 1),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertFalse(outcome.rebuilt());
        assertEquals(0, rebuilds.get());
    }

    public void testAbortedCompilerRunDoesNotRebuild() throws Exception {
        AtomicInteger rebuilds = new AtomicInteger();
        IdeCompilationRunner.RecoveryOutcome outcome = IdeCompilationRunner.recover(
            new IdeCompilationRunner.AttemptResult(List.of("Compilation was aborted."), true, 0),
            () -> {
                rebuilds.incrementAndGet();
                return new IdeCompilationRunner.AttemptResult(List.of(), false, 0);
            });

        assertFalse(outcome.rebuilt());
        assertEquals(0, rebuilds.get());
    }

    public void testIdeCompilationTimeoutReleasesWaitingThread() throws Exception {
        CompilerManager compiler = CompilerManager.getInstance(getProject());
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());
        service.recordCompletion(true, java.util.Collections.emptyMap(), List.of());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<List<String>> result = worker.submit(() ->
                IdeCompilationRunner.runWithRecovery(
                    getProject(),
                    new EmptyProgressIndicator(),
                    true,
                    java.util.Collections.emptyMap(),
                    compiler,
                    notification -> {
                        // Simulate a compiler invocation that never calls back.
                    },
                    20,
                    TimeUnit.MILLISECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents();
                Thread.sleep(10L);
            }
            assertTrue("Compilation timeout future did not complete", result.isDone());
            try {
                result.get();
                fail("Expected compiler timeout");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof TimeoutException);
            }
            assertNull(service.tryReuse(List.of()));
        } finally {
            worker.shutdownNow();
        }
    }

    public void testExternalServerIsProjectServiceForStartupDeduplication() {
        assertNotNull(getProject().getService(PrePushLocalServer.class));
        assertSame(
            getProject().getService(PrePushLocalServer.class),
            getProject().getService(PrePushLocalServer.class));
    }

    // ── Pushed-tip SHA selection (regression: wrong commit id copied) ──────────

    private static final String A = "1111111111111111111111111111111111111111";
    private static final String B = "2222222222222222222222222222222222222222";
    private static final String C = "3333333333333333333333333333333333333333";

    public void testSelectTipReturnsTopologicalHeadNotHighestTimestamp() {
        // Chain A <- B <- C (C is the real tip) but B has the LATEST commit time,
        // as happens after a rebase/amend. The old highest-timestamp heuristic would
        // wrongly return B; topological selection must return C.
        List<PrePushCompilationHandler.CommitNode> nodes = List.of(
            new PrePushCompilationHandler.CommitNode(A, List.of(), 100L),
            new PrePushCompilationHandler.CommitNode(B, List.of(A), 300L),   // latest time, but not the tip
            new PrePushCompilationHandler.CommitNode(C, List.of(B), 200L)    // real tip
        );
        assertEquals(C, PrePushCompilationHandler.selectTipSha(nodes));
    }

    public void testSelectTipSingleCommit() {
        List<PrePushCompilationHandler.CommitNode> nodes = List.of(
            new PrePushCompilationHandler.CommitNode(A, List.of(), 100L));
        assertEquals(A, PrePushCompilationHandler.selectTipSha(nodes));
    }

    public void testSelectTipFallsBackToHighestTimestampWhenNoUniqueTip() {
        // Two disjoint heads (both are tips) → cannot single out one → fall back to
        // the highest-timestamp commit.
        List<PrePushCompilationHandler.CommitNode> nodes = List.of(
            new PrePushCompilationHandler.CommitNode(A, List.of(), 100L),
            new PrePushCompilationHandler.CommitNode(B, List.of(), 500L));
        assertEquals(B, PrePushCompilationHandler.selectTipSha(nodes));
    }

    public void testSelectTipEmptyReturnsNull() {
        assertNull(PrePushCompilationHandler.selectTipSha(List.of()));
    }
}
