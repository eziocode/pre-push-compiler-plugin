package com.github.prepushchecker;

import junit.framework.TestCase;

import static com.github.prepushchecker.SmartBuildPolicy.Decision;

public class SmartBuildPolicyTest extends TestCase {
    private static final long NOW = 10_000_000L;
    private static final long IDLE = 90_000L;
    private static final long SHORT_IDLE = 20_000L;
    private static final long MAX_STALE = 15 * 60_000L;
    private static final long MIN_INTERVAL = 5 * 60_000L;

    private static SmartBuildPolicy.Inputs inputs(
        boolean enabled, boolean stale, boolean dumb, boolean powerSave, boolean debugging,
        boolean compiling, boolean push, boolean git,
        long lastActivity, long staleSince, long lastBuild
    ) {
        return new SmartBuildPolicy.Inputs(enabled, stale, dumb, powerSave, debugging, compiling,
            push, git, NOW, lastActivity, staleSince, lastBuild, IDLE, SHORT_IDLE, MAX_STALE, MIN_INTERVAL);
    }

    private static SmartBuildPolicy.Inputs idleStale() {
        return inputs(true, true, false, false, false, false, false, false,
            NOW - IDLE - 1, NOW - 60_000L, 0L);
    }

    public void testRunsWhenStaleAndIdle() {
        assertEquals(Decision.RUN, SmartBuildPolicy.decide(idleStale()).decision());
    }

    public void testDisabledNeverRuns() {
        assertEquals(Decision.DISABLED, SmartBuildPolicy.decide(inputs(false, true, false, false,
            false, false, false, false, 0L, NOW - 60_000L, 0L)).decision());
    }

    public void testUpToDateNeverRuns() {
        assertEquals(Decision.NOT_NEEDED, SmartBuildPolicy.decide(inputs(true, false, false, false,
            false, false, false, false, 0L, 0L, 0L)).decision());
    }

    public void testEveryBlockerPreventsBuild() {
        boolean[][] blockers = {
            {true, false, false, false, false, false},
            {false, true, false, false, false, false},
            {false, false, true, false, false, false},
            {false, false, false, true, false, false},
            {false, false, false, false, true, false},
            {false, false, false, false, false, true},
        };
        for (boolean[] b : blockers) {
            SmartBuildPolicy.Verdict verdict = SmartBuildPolicy.decide(inputs(true, true,
                b[0], b[1], b[2], b[3], b[4], b[5], NOW - IDLE - 1, NOW - 60_000L, 0L));
            assertEquals(Decision.BLOCKED, verdict.decision());
            assertEquals(NOW + SmartBuildPolicy.BLOCKED_RETRY_MS, verdict.retryAt());
        }
    }

    public void testDebuggingWinsOverOtherReasons() {
        SmartBuildPolicy.Verdict verdict = SmartBuildPolicy.decide(inputs(true, true, true, true,
            true, false, false, false, NOW - IDLE - 1, NOW - 60_000L, 0L));
        assertEquals("debugging", verdict.reason());
    }

    public void testWaitsUntilIdleThreshold() {
        long lastActivity = NOW - 30_000L;
        SmartBuildPolicy.Verdict verdict = SmartBuildPolicy.decide(inputs(true, true, false, false,
            false, false, false, false, lastActivity, NOW - 60_000L, 0L));
        assertEquals(Decision.WAIT, verdict.decision());
        assertEquals(lastActivity + IDLE, verdict.retryAt());
    }

    public void testLongEditingSessionNeedsOnlyShortPause() {
        long lastActivity = NOW - SHORT_IDLE - 1;
        SmartBuildPolicy.Verdict verdict = SmartBuildPolicy.decide(inputs(true, true, false, false,
            false, false, false, false, lastActivity, NOW - MAX_STALE, 0L));
        assertEquals(Decision.RUN, verdict.decision());
        assertEquals("editing pause", verdict.reason());
    }

    public void testRespectsMinimumIntervalBetweenBuilds() {
        long lastBuild = NOW - 60_000L;
        SmartBuildPolicy.Verdict verdict = SmartBuildPolicy.decide(inputs(true, true, false, false,
            false, false, false, false, NOW - IDLE - 1, NOW - 60_000L, lastBuild));
        assertEquals(Decision.WAIT, verdict.decision());
        assertEquals(lastBuild + MIN_INTERVAL, verdict.retryAt());
        assertEquals("recently built", verdict.reason());
    }
}
