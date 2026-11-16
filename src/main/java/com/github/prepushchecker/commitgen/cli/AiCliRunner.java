package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Runs a local AI CLI non-interactively and returns its stdout.
 *
 * <p>Uses {@link ProcessExecution} so the prompt is streamed on stdin, both
 * output streams are drained concurrently, and the whole process tree is
 * killed on timeout — the failure mode that made earlier subprocess-based
 * providers hang. The process runs in an empty temp directory so agentic
 * CLIs do not index or modify the user's repository.</p>
 *
 * <p>When called under a progress indicator (a cancellable background task),
 * cancelling the task kills the CLI process tree right away.</p>
 */
public final class AiCliRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final long CANCEL_POLL_MILLIS = 100;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService RUNNER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PrePushChecker-AiCli-" + THREAD_COUNTER.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    private AiCliRunner() {}

    /** Creates the isolated working directory for one CLI invocation. */
    public static @NotNull Path createWorkDir() throws IOException {
        return Files.createTempDirectory("prepushchecker-aicli");
    }

    /** Best-effort recursive delete of a directory created by {@link #createWorkDir()}. */
    public static void deleteQuietly(@Nullable Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /**
     * Runs {@code command} and returns trimmed stdout.
     *
     * @param label     human-readable CLI name for error messages
     * @param command   full argv, first element is the resolved binary
     * @param stdin     text written to stdin, or {@code null} for none
     * @param workDir   working directory
     * @param extraEnv  environment variables to add (may be empty)
     * @param timeout   hard timeout
     * @param authHint  appended to failure messages (e.g. "Run: claude")
     */
    public static @NotNull String run(@NotNull String label,
                                      @NotNull List<String> command,
                                      @Nullable String stdin,
                                      @NotNull Path workDir,
                                      @NotNull Map<String, String> extraEnv,
                                      @NotNull Duration timeout,
                                      @NotNull String authHint) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        CliPathResolver.injectAugmentedPath(pb);
        pb.environment().put("NO_COLOR", "1");
        pb.environment().putAll(extraEnv);

        ProcessExecution.Result result;
        try {
            result = runCancellable(pb, timeout, stdin == null ? "" : stdin,
                ProgressIndicatorProvider.getGlobalProgressIndicator());
        } catch (IOException e) {
            throw new RuntimeException(
                "Cannot start " + label + " at '" + command.get(0) + "'.\n"
                    + "Set the CLI path in Settings → Tools → "
                    + "Pre-Push Checker — AI Commit Message Generator, or use Auto-detect.", e);
        }

        if (result.timedOut()) {
            throw new RuntimeException(
                label + " timed out after " + timeout.toSeconds() + " seconds.\n" + authHint);
        }
        if (result.exitCode() != 0) {
            String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
            throw new RuntimeException(
                label + " exited with code " + result.exitCode()
                    + (detail.isBlank() ? "" : ":\n" + AiOutputSanitizer.errorDetail(tail(detail.trim(), 2000)))
                    + "\n\n" + authHint);
        }
        return result.stdout().trim();
    }

    /**
     * Runs the process on a helper thread and waits on this one, so a cancelled
     * {@code indicator} interrupts the helper; {@link ProcessExecution} then
     * kills the process tree in its cleanup.
     */
    static @NotNull ProcessExecution.Result runCancellable(@NotNull ProcessBuilder pb,
                                                           @NotNull Duration timeout,
                                                           @Nullable String stdin,
                                                           @Nullable ProgressIndicator indicator)
            throws IOException, InterruptedException {
        if (indicator == null) return ProcessExecution.run(pb, timeout, stdin);
        Future<ProcessExecution.Result> future = RUNNER.submit(() -> ProcessExecution.run(pb, timeout, stdin));
        try {
            while (true) {
                if (indicator.isCanceled()) {
                    future.cancel(true);
                    throw new ProcessCanceledException();
                }
                try {
                    return future.get(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // poll again
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException ie) throw ie;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException(cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * Cleans CLI output (thinking, tool calls, transcript lines, JSON events …)
     * with {@link AiOutputSanitizer} and throws when no usable text is left.
     */
    public static @NotNull String requireText(@NotNull String label, @Nullable String text,
                                              @NotNull String authHint) {
        String cleaned = AiOutputSanitizer.clean(text);
        if (cleaned.isBlank()) {
            boolean hadOutput = text != null && !text.isBlank();
            throw new RuntimeException(label + (hadOutput
                ? ": " + AiOutputSanitizer.EMPTY_RESULT_MESSAGE
                : " returned an empty response.") + "\n" + authHint);
        }
        return cleaned;
    }

    private static @NotNull String tail(@NotNull String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
