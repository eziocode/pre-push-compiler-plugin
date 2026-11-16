package com.github.prepushchecker.review;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One AI review finding. Mutable with public fields so it serializes with the workspace
 * state ({@link ReviewStateStore}).
 */
public final class ReviewFinding {
    public String id = UUID.randomUUID().toString();
    /** Absolute, {@code /}-separated path of the file. */
    public String path = "";
    /** 1-based line, or 0 when the AI gave none. */
    public int line;
    public String rule = "";
    public String message = "";
    public String suggestion = "";
    public boolean resolved;
    /** "manual" or "ai" once resolved. */
    public String resolvedBy = "";

    public ReviewFinding() {}

    public ReviewFinding(@NotNull String path, int line, @NotNull String rule,
                         @NotNull String message, @NotNull String suggestion) {
        this.path = path;
        this.line = Math.max(0, line);
        this.rule = rule;
        this.message = message;
        this.suggestion = suggestion;
    }

    public boolean isOpen() {
        return !resolved;
    }
}
