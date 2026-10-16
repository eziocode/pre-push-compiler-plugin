package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;

/**
 * Pure decision logic for {@link SmartBuildService}: whether a background build may start
 * now, or when to look again. Kept free of platform calls so every gate is unit-testable.
 */
final class SmartBuildPolicy {
    /** How long to wait before re-evaluating while something (debugger, indexing...) blocks. */
    static final long BLOCKED_RETRY_MS = 120_000L;

    enum Decision { RUN, WAIT, BLOCKED, NOT_NEEDED, DISABLED }

    record Inputs(
        boolean enabled,
        boolean stale,
        boolean dumb,
        boolean powerSave,
        boolean debugging,
        boolean compiling,
        boolean pushInFlight,
        boolean gitBusy,
        long now,
        long lastActivityAt,
        long staleSince,
        long lastSmartBuildAt,
        long idleMs,
        long shortIdleMs,
        long maxStaleMs,
        long minIntervalMs
    ) {
    }

    record Verdict(@NotNull Decision decision, long retryAt, @NotNull String reason) {
    }

    private SmartBuildPolicy() {
    }

    static @NotNull Verdict decide(@NotNull Inputs in) {
        if (!in.enabled()) return new Verdict(Decision.DISABLED, 0L, "smart build off");
        if (!in.stale()) return new Verdict(Decision.NOT_NEEDED, 0L, "up to date");

        String blocker = blocker(in);
        if (blocker != null) {
            return new Verdict(Decision.BLOCKED, in.now() + BLOCKED_RETRY_MS, blocker);
        }

        // After a long stretch of continuous editing, a short typing pause is enough.
        boolean longStale = in.staleSince() > 0L && in.now() - in.staleSince() >= in.maxStaleMs();
        long requiredIdle = longStale ? in.shortIdleMs() : in.idleMs();
        long idleReadyAt = in.lastActivityAt() + requiredIdle;
        long intervalReadyAt = in.lastSmartBuildAt() > 0L
            ? in.lastSmartBuildAt() + in.minIntervalMs()
            : 0L;
        long readyAt = Math.max(idleReadyAt, intervalReadyAt);
        if (in.now() >= readyAt) {
            return new Verdict(Decision.RUN, 0L, longStale ? "editing pause" : "IDE idle");
        }
        return new Verdict(Decision.WAIT, readyAt,
            readyAt == intervalReadyAt && intervalReadyAt > idleReadyAt
                ? "recently built"
                : "waiting for idle");
    }

    private static String blocker(@NotNull Inputs in) {
        if (in.debugging()) return "debugging";
        if (in.dumb()) return "indexing";
        if (in.powerSave()) return "power save mode";
        if (in.compiling()) return "build running";
        if (in.pushInFlight()) return "push check running";
        if (in.gitBusy()) return "git operation in progress";
        return null;
    }
}
