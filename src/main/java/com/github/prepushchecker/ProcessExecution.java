package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProcessExecution {
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_CAPTURED_BYTES = 8 * 1024 * 1024;
    private static final AtomicInteger IO_THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService IO_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(
            runnable,
            "PrePushChecker-ProcessIO-" + IO_THREAD_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    private ProcessExecution() {
    }

    public record Result(
        int exitCode,
        @NotNull String stdout,
        @NotNull String stderr,
        boolean timedOut
    ) {
        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }

        public @NotNull String combinedOutput() {
            if (stdout.isBlank()) return stderr;
            if (stderr.isBlank()) return stdout;
            return stdout + "\n" + stderr;
        }
    }

    public static @NotNull Result run(@NotNull ProcessBuilder processBuilder, @NotNull Duration timeout)
        throws IOException, InterruptedException {
        return run(processBuilder, timeout, null);
    }

    public static @NotNull Result run(
        @NotNull ProcessBuilder processBuilder,
        @NotNull Duration timeout,
        String stdin
    ) throws IOException, InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Timeout must not be negative.");
        long timeoutNanos = timeout.toNanos();
        Process process = processBuilder.start();
        long started = System.nanoTime();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = processBuilder.redirectErrorStream()
            ? CompletableFuture.completedFuture("")
            : readAsync(process.getErrorStream());
        CompletableFuture<Void> input = stdin == null ? CompletableFuture.completedFuture(null)
            : CompletableFuture.runAsync(() -> {
                try {
                    writeAndCloseStdin(process, stdin);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, IO_EXECUTOR);
        try {
            if (stdin == null) process.getOutputStream().close();
            boolean finished;
            try {
                input.get(remainingNanos(started, timeoutNanos), TimeUnit.NANOSECONDS);
                finished = process.waitFor(remainingNanos(started, timeoutNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                finished = false;
            } catch (ExecutionException e) {
                throw outputFailure(e);
            }
            if (!finished) {
                terminate(process);
                process.waitFor(2, TimeUnit.SECONDS);
            }
            return new Result(
                finished ? process.exitValue() : -1,
                awaitDrain(stdout).trim(),
                awaitDrain(stderr).trim(),
                !finished
            );
        } finally {
            // Also clean up on cancellation, stdin failure, or output-drain failure.
            terminate(process);
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            input.cancel(true);
            stdout.cancel(true);
            stderr.cancel(true);
        }
    }

    private static long remainingNanos(long started, long timeoutNanos) {
        return Math.max(0L, timeoutNanos - (System.nanoTime() - started));
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    private static void writeAndCloseStdin(Process process, String stdin) throws IOException {
        try (OutputStream out = process.getOutputStream()) {
            if (stdin != null) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static CompletableFuture<String> readAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = inputStream) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                // Keep draining past the cap so the child never blocks on a full pipe.
                while ((read = in.read(chunk)) != -1) {
                    int room = MAX_CAPTURED_BYTES - buffer.size();
                    if (room > 0) buffer.write(chunk, 0, Math.min(read, room));
                }
                return buffer.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, IO_EXECUTOR);
    }

    private static String awaitDrain(CompletableFuture<String> output)
        throws IOException, InterruptedException {
        try {
            return output.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Timed out while reading process output.", e);
        } catch (ExecutionException e) {
            throw outputFailure(e);
        }
    }

    private static IOException outputFailure(ExecutionException failure) {
        Throwable cause = failure.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof IOException ioException
            ? ioException : new IOException("Process I/O failed.", cause);
    }
}
