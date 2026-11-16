package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;

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
                        @NotNull Duration timeout) {

    public enum Kind { COMMIT_MESSAGE, REVIEW, FIX }

    public static @NotNull AiRequest commitMessage(@NotNull String system, @NotNull String user) {
        return new AiRequest(system, user, Kind.COMMIT_MESSAGE, 300, Duration.ofSeconds(120));
    }

    public static @NotNull AiRequest review(@NotNull String system, @NotNull String user) {
        return new AiRequest(system, user, Kind.REVIEW, 4096, Duration.ofSeconds(240));
    }

    public static @NotNull AiRequest fix(@NotNull String system, @NotNull String user) {
        return new AiRequest(system, user, Kind.FIX, 4096, Duration.ofSeconds(180));
    }

    /** {@code true} when the answer must be a JSON object rather than plain text. */
    public boolean structured() {
        return kind != Kind.COMMIT_MESSAGE;
    }

    public @NotNull AiRequest withTimeout(@NotNull Duration newTimeout) {
        return new AiRequest(system, user, kind, maxOutputTokens, newTimeout);
    }
}
