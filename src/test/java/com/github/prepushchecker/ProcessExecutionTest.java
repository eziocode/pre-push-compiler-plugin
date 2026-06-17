package com.github.prepushchecker;

import junit.framework.TestCase;

import java.time.Duration;

public class ProcessExecutionTest extends TestCase {
    public void testTimeoutDoesNotWaitForProcessExitBeforeDrainingOutput() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "printf out; printf err >&2; sleep 5");
        ProcessExecution.Result result = ProcessExecution.run(pb, Duration.ofMillis(200));

        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertEquals("out", result.stdout());
        assertEquals("err", result.stderr());
    }
}
