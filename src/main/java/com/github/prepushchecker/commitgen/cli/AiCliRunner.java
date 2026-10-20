package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.CliPathResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs a local AI CLI non-interactively and returns its stdout.
 *
 * <p>Uses {@link ProcessExecution} so the prompt is streamed on stdin, both
 * output streams are drained concurrently, and the whole process tree is
 * killed on timeout — the failure mode that made earlier subprocess-based
 * providers hang. The process runs in an empty temp directory so agentic
 * CLIs do not index or modify the user's repository.</p>
 */
public final class AiCliRunner {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

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
            result = ProcessExecution.run(pb, timeout, stdin == null ? "" : stdin);
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
                    + (detail.isBlank() ? "" : ":\n" + tail(detail.trim(), 2000))
                    + "\n\n" + authHint);
        }
        return result.stdout().trim();
    }

    /** Throws when the CLI produced no usable text. */
    public static @NotNull String requireText(@NotNull String label, @Nullable String text,
                                              @NotNull String authHint) {
        String cleaned = stripCodeFence(text == null ? "" : text.trim());
        if (cleaned.isBlank()) {
            throw new RuntimeException(label + " returned an empty response.\n" + authHint);
        }
        return cleaned;
    }

    /** Removes a single wrapping ``` fence, which chatty CLIs sometimes add. */
    static @NotNull String stripCodeFence(@NotNull String text) {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length() < 6) return text;
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) return text;
        return text.substring(firstNewline + 1, text.length() - 3).trim();
    }

    private static @NotNull String tail(@NotNull String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
