package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessExecution {
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);

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
        Process process = processBuilder.start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = processBuilder.redirectErrorStream()
            ? CompletableFuture.completedFuture("")
            : readAsync(process.getErrorStream());
        try {
            writeAndCloseStdin(process, stdin);
        } catch (IOException e) {
            process.destroyForcibly();
            throw e;
        }

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }

        return new Result(
            finished ? process.exitValue() : -1,
            awaitDrain(stdout).trim(),
            awaitDrain(stderr).trim(),
            !finished
        );
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
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static String awaitDrain(CompletableFuture<String> output)
        throws IOException, InterruptedException {
        try {
            return output.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Timed out while reading process output.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to read process output.", cause);
        }
    }
}
