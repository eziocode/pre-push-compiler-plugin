package com.github.prepushchecker;

import junit.framework.TestCase;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ProcessExecutionTest extends TestCase {
    public void testLargeStdinRespectsTimeoutWhenChildDoesNotRead() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> ProcessExecution.run(
                new ProcessBuilder("sh", "-c", "printf started; exec sleep 30"),
                Duration.ofMillis(200), "x".repeat(1024 * 1024)
            )).get(5, TimeUnit.SECONDS);
            assertTrue(result.timedOut());
            assertEquals("started", result.stdout());
        } finally {
            executor.shutdownNow();
        }
    }

    public void testStdinIsDeliveredAndClosed() throws Exception {
        var result = ProcessExecution.run(new ProcessBuilder("sh", "-c", "cat"),
            Duration.ofSeconds(5), "hello\nworld");
        assertTrue(result.isSuccess());
        assertEquals("hello\nworld", result.stdout());
    }

    public void testInterruptionTerminatesProcessAndItsChild() throws Exception {
        Path directory = Files.createTempDirectory("prepush-process-test");
        Path parent = directory.resolve("parent.pid");
        Path child = directory.resolve("child.pid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ProcessExecution.run(new ProcessBuilder("sh", "-c",
                    "echo $$ > \"$1\"; sleep 30 & echo $! > \"$2\"; wait",
                    "test", parent.toString(), child.toString()), Duration.ofSeconds(60));
                failure.set(new AssertionError("Expected interruption"));
            } catch (InterruptedException expected) {
                // Cancellation must stop both processes before it returns.
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        });
        try {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(child) || Files.size(child) == 0) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue("Child did not start", Files.exists(child) && Files.size(child) > 0);
            long parentPid = Long.parseLong(Files.readString(parent).trim());
            long childPid = Long.parseLong(Files.readString(child).trim());
            worker.interrupt();
            worker.join(5000);
            assertFalse("Worker did not stop", worker.isAlive());
            assertNull(failure.get());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((isAlive(parentPid) || isAlive(childPid)) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse("Parent survived cancellation", isAlive(parentPid));
            assertFalse("Child survived cancellation", isAlive(childPid));
        } finally {
            worker.interrupt();
            worker.join(5000);
            Files.deleteIfExists(parent);
            Files.deleteIfExists(child);
            Files.deleteIfExists(directory);
        }
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public void testTimeoutDoesNotWaitForProcessExitBeforeDrainingOutput() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "printf out; printf err >&2; sleep 5");
        ProcessExecution.Result result = ProcessExecution.run(pb, Duration.ofMillis(200));

        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertEquals("out", result.stdout());
        assertEquals("err", result.stderr());
    }

    public void testStdoutAndStderrAreDrainedConcurrently() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "sh", "-c",
            "i=0; while [ $i -lt 20000 ]; do printf 'out-%s\\n' \"$i\"; "
                + "printf 'err-%s\\n' \"$i\" >&2; i=$((i+1)); done"
        );

        ProcessExecution.Result result = ProcessExecution.run(pb, Duration.ofSeconds(10));

        assertTrue(result.isSuccess());
        assertTrue(result.stdout().contains("out-19999"));
        assertTrue(result.stderr().contains("err-19999"));
    }
}
