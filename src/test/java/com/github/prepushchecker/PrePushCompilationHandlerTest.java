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
}
