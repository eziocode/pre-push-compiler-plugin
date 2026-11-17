package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * One request to an AI provider.
 *
 * <p>Commit messages are short free text. Reviews and fixes are longer and must come back as
 * a JSON object, so they get a larger output budget, a longer timeout and no commit-message
 * cleanup: the caller parses them with {@link AiOutputSanitizer#cleanStructured}.</p>
 */
public record AiRequest(@NotNull String system,
                        @NotNull String user,
                        @NotNull Kind kind,
                        int maxOutputTokens,
                        @NotNull Duration timeout,
                        @Nullable String model) {

    public enum Kind { COMMIT_MESSAGE, REVIEW, FIX, COMPLETION }

    public static final Duration COMPLETION_FAST_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration COMPLETION_SLOW_TIMEOUT = Duration.ofSeconds(60);

    public static @NotNull AiRequest commitMessage(@NotNull String system, @NotNull String user) {
        return new AiRequest(system, user, Kind.COMMIT_MESSAGE, 300, Duration.ofSeconds(120), null);
    }

    public static @NotNull AiRequest review(@NotNull String system, @NotNull String user) {
        return new AiRequest(system, user, Kind.REVIEW, 4096, Duration.ofSeconds(240), null);
    }

    public static @NotNull AiRequest fix(@NotNull String system, @NotNull String user) {
        return new AiRequest(system, user, Kind.FIX, 4096, Duration.ofSeconds(180), null);
    }

    /** Short inline code completion; {@code model} overrides the provider's configured model. */
    public static @NotNull AiRequest completion(@NotNull String system, @NotNull String user,
                                                @Nullable String model, @NotNull Duration timeout) {
        return new AiRequest(system, user, Kind.COMPLETION, 256, timeout, model);
    }

    /** {@code true} when the answer must be a JSON object rather than plain text. */
    public boolean structured() {
        return kind == Kind.REVIEW || kind == Kind.FIX;
    }

    /** {@code true} for as-you-type completions, where a CLI should trade depth for latency. */
    public boolean lowLatency() {
        return kind == Kind.COMPLETION;
    }

        public @NotNull AiRequest withTimeout(@NotNull Duration newTimeout) {
        return new AiRequest(system, user, kind, maxOutputTokens, newTimeout, model);
    }

    /** The per-request model when set, otherwise the provider's configured {@code fallback}. */
    public @NotNull String modelOr(@NotNull String fallback) {
        return model != null && !model.isBlank() ? model : fallback;
    }
}
