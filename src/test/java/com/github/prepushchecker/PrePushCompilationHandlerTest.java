package com.github.prepushchecker;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class PrePushCompilationHandlerTest extends BasePlatformTestCase {
    public void testPrePushResultCacheReusesMatchingKey() {
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());

        service.recordPrePushResult("commit=a\npath=src/main/java/App.java\n", List.of("error"));

        assertEquals(List.of("error"), service.tryReusePrePushResult("commit=a\npath=src/main/java/App.java\n"));
        assertNull(service.tryReusePrePushResult("commit=b\npath=src/main/java/App.java\n"));
    }

    public void testPrePushRunningKeyIsSingleFlight() {
        CompilationErrorService service = CompilationErrorService.getInstance(getProject());

        assertTrue(service.markPrePushCheckRunning("key"));
        assertFalse(service.markPrePushCheckRunning("key"));
        assertTrue(service.isPrePushCheckRunning("key"));

        service.finishPrePushCheck("key");

        assertFalse(service.isPrePushCheckRunning("key"));
    }

    public void testExternalServerRetriesOnlyScopedCompileFailures() {
        assertTrue(PrePushLocalServer.shouldRetryProjectScopeAfterScopedFailure(false, false, 1));

        assertFalse(PrePushLocalServer.shouldRetryProjectScopeAfterScopedFailure(true, false, 1));
        assertFalse(PrePushLocalServer.shouldRetryProjectScopeAfterScopedFailure(false, true, 1));
        assertFalse(PrePushLocalServer.shouldRetryProjectScopeAfterScopedFailure(false, false, 0));
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
