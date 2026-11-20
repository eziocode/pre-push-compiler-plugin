package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
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
        return run(label, command, stdin, workDir, extraEnv, timeout, authHint, null);
    }

    /**
     * As {@link #run(String, List, String, Path, Map, Duration, String)}; {@code onLine} receives
     * each stdout line as it arrives (on an I/O thread). Logs per-phase timings: binary lookup
     * (see {@link #noteResolve}), spawn, first output line and total.
     */
    public static @NotNull String run(@NotNull String label,
                                      @NotNull List<String> command,
                                      @Nullable String stdin,
                                      @NotNull Path workDir,
                                      @NotNull Map<String, String> extraEnv,
                                      @NotNull Duration timeout,
                                      @NotNull String authHint,
                                      @Nullable java.util.function.Consumer<String> onLine) throws Exception {
        Timing timing = new Timing(label, RESOLVE_NANOS.get());
        RESOLVE_NANOS.remove();
        try {
            return runTimed(label, command, stdin, workDir, extraEnv, timeout, authHint, onLine, timing);
        } finally {
            LOG.info(timing.summary());
        }
    }

    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(AiCliRunner.class);
    private static final ThreadLocal<Long> RESOLVE_NANOS = new ThreadLocal<>();

    /**
     * Records how long finding the binary took on this thread; the next {@link #run} on the same
     * thread includes it in its timing log line.
     */
    public static void noteResolve(long startedNanos) {
        RESOLVE_NANOS.set(System.nanoTime() - startedNanos);
    }

    /** Per-phase timings of one CLI call, logged as one line. */
    static final class Timing implements ProcessExecution.Observer {
        private final String label;
        private final Long resolveNanos;
        private final long created = System.nanoTime();
        private volatile long spawned;
        private volatile long firstLine;
        private volatile java.util.function.Consumer<String> onLine;

        Timing(String label, Long resolveNanos) {
            this.label = label;
            this.resolveNanos = resolveNanos;
        }

        @Override
        public void started() {
            spawned = System.nanoTime();
        }

        @Override
        public void stdoutLine(@NotNull String line) {
            if (firstLine == 0) firstLine = System.nanoTime();
            java.util.function.Consumer<String> l = onLine;
            if (l != null) l.accept(line);
        }

        String summary() {
            long now = System.nanoTime();
            return "AI CLI timing [" + label + "]"
                + (resolveNanos == null ? "" : " resolve=" + ms(resolveNanos) + "ms")
                + " spawn=" + (spawned == 0 ? "-" : ms(spawned - created) + "ms")
                + " firstOutput=" + (firstLine == 0 ? "-" : ms(firstLine - created) + "ms")
                + " total=" + ms(now - created) + "ms";
        }

        private static long ms(long nanos) {
            return nanos / 1_000_000;
        }
    }

    private static @NotNull String runTimed(@NotNull String label,
                                           @NotNull List<String> command,
                                           @Nullable String stdin,
                                           @NotNull Path workDir,
                                           @NotNull Map<String, String> extraEnv,
                                           @NotNull Duration timeout,
                                           @NotNull String authHint,
                                           @Nullable java.util.function.Consumer<String> onLine,
                                           @NotNull Timing timing) throws Exception {
        timing.onLine = onLine;
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        CliPathResolver.injectAugmentedPath(pb);
        pb.environment().put("NO_COLOR", "1");
        pb.environment().putAll(extraEnv);

        ProcessExecution.Result result;
        try {
            result = runCancellable(pb, timeout, stdin == null ? "" : stdin,
                ProgressIndicatorProvider.getGlobalProgressIndicator(), timing);
        } catch (IOException e) {
            throw new RuntimeException(
                "Cannot start " + label + " at '" + command.get(0) + "'.\n"
                    + "Set the CLI path in " + CommitMessageSettings.SETTINGS_PATH
                    + " (Choose CLI…).", e);
        }

        if (result.timedOut()) {
            throw new RuntimeException(
                label + " timed out after " + timeout.toSeconds() + " seconds.\n" + authHint);
        }
        if (result.exitCode() != 0) {
            String detail = errorSummary(result.stderr() + "\n" + result.stdout());
            throw new RuntimeException(
                label + " exited with code " + result.exitCode()
                    + (detail.isBlank() ? "" : ":\n" + detail)
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
        return runCancellable(pb, timeout, stdin, indicator, null);
    }

    static @NotNull ProcessExecution.Result runCancellable(@NotNull ProcessBuilder pb,
                                                           @NotNull Duration timeout,
                                                           @Nullable String stdin,
                                                           @Nullable ProgressIndicator indicator,
                                                           @Nullable ProcessExecution.Observer observer)
            throws IOException, InterruptedException {
        if (indicator == null) return ProcessExecution.run(pb, timeout, stdin, observer);
        Future<ProcessExecution.Result> future =
            RUNNER.submit(() -> ProcessExecution.run(pb, timeout, stdin, observer));
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

    /**
     * Final step of a CLI provider: commit messages are cleaned with {@link #requireText};
     * structured answers (review, fix) are returned raw for
     * {@link AiOutputSanitizer#cleanStructured}, after an empty-output check.
     */
    public static @NotNull String finish(@NotNull String label, @Nullable String text,
                                         @NotNull String authHint, boolean structured) {
        if (!structured) return requireText(label, text, authHint);
        if (text == null || text.isBlank()) {
            throw new RuntimeException(label + " returned an empty response.\n" + authHint);
        }
        return text;
    }

    /**
     * Request-aware {@link #finish(String, String, String, boolean)}: completions are cleaned
     * with {@link AiOutputSanitizer#cleanCompletion} (code is not treated as commit-message
     * text); every other kind behaves as before.
     */
    public static @NotNull String finish(@NotNull String label, @Nullable String text,
                                         @NotNull String authHint, @NotNull AiRequest request) {
        if (request.kind() != AiRequest.Kind.COMPLETION) {
            return finish(label, text, authHint, request.structured());
        }
        // An empty answer is the model saying "nothing to insert here": no suggestion, not a failure
        // (it must not count toward the inline auto-pause or show an error balloon).
        return AiOutputSanitizer.cleanCompletion(text);
    }

    /** A line the CLI itself marks as an error ({@code ERROR: …}, {@code Error: …}, {@code fatal: …}). */
    private static final java.util.regex.Pattern ERROR_PREFIX = java.util.regex.Pattern.compile(
        "(?i)^(?:\\[[^]]*]\\s*)?(?:error|fatal|panic)\\b\\s*[:\\-].*");
    /** Typical failure wording, trusted only near the end of the output. */
    private static final java.util.regex.Pattern ERROR_PHRASE = java.util.regex.Pattern.compile(
        "(?i).*\\b(?:not supported|not logged in|not signed in|unauthori[sz]ed|invalid api key|"
            + "rate limit|usage limit|quota|permission denied|stdin is not a terminal)\\b.*");
    private static final int PHRASE_WINDOW = 15;

    /**
     * The part of a failed CLI's output worth showing. CLIs such as codex echo the whole prompt
     * before the real error, so the CLI's own error lines win, then failure wording in the last
     * lines, then the end of the output; never the start.
     */
    static @NotNull String errorSummary(@NotNull String output) {
        String clean = AiOutputSanitizer.stripNoiseForErrors(output);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : clean.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && t.length() <= 400) lines.add(t);
        }
        java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
        for (String t : lines) if (ERROR_PREFIX.matcher(t).matches()) picked.add(t);
        if (picked.isEmpty()) {
            for (String t : lines.subList(Math.max(0, lines.size() - PHRASE_WINDOW), lines.size())) {
                if (ERROR_PHRASE.matcher(t).matches()) picked.add(t);
            }
        }
        if (!picked.isEmpty()) {
            java.util.List<String> list = new java.util.ArrayList<>(picked);
            return String.join("\n", list.subList(Math.max(0, list.size() - 3), list.size()));
        }
        String end = clean.strip();
        if (end.startsWith("{") || end.startsWith("[")) end = AiOutputSanitizer.errorDetail(end);
        return tail(end, AiOutputSanitizer.MAX_ERROR_DETAIL);
    }

    private static @NotNull String tail(@NotNull String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
