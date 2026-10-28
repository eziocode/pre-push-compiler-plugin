package com.github.prepushchecker;

import junit.framework.TestCase;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


public class PushStatsTest extends TestCase {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final PushStats stats = new PushStats(now::get);

    private boolean blocked(String key, String branch, String... errors) {
        return stats.record("/p", "p", PushStats.Result.BLOCKED, List.of(errors), key, false, branch,
            PushStats.Source.IDE);
    }

    private boolean allowed(String key, String branch, boolean cacheHit) {
        return stats.record("/p", "p", PushStats.Result.ALLOWED, List.of(), key, cacheHit, branch,
            PushStats.Source.IDE);
    }

    public void testClassifiesByMessageText() {
        Map<PushStats.Kind, Integer> kinds = PushStats.classify(List.of(
            "[A.java (3:1)] Forbidden jar: 'x' file imports y",
            "[A.java (4:1)] Layer violation: 'x' file imports y",
            "[.prepush-layers (2:1)] Layer rules error: bad",
            "[B.java] references 'foo', defined only in unpushed local change to C.java",
            "[D.java (1:1)] cannot find symbol"));
        assertEquals(Integer.valueOf(1), kinds.get(PushStats.Kind.FORBIDDEN_JAR));
        assertEquals(Integer.valueOf(2), kinds.get(PushStats.Kind.LAYER));
        assertEquals(Integer.valueOf(1), kinds.get(PushStats.Kind.SYMBOLIC));
        assertEquals(Integer.valueOf(1), kinds.get(PushStats.Kind.COMPILE));
    }

    public void testMixedFailureCountsOnePushAndEachKind() {
        blocked("k1", "main", "cannot find symbol", "Layer violation: x");
        PushStats.Counters totals = stats.totals();
        assertEquals(1, totals.blocked);
        assertEquals(1, totals.blockedCompile);
        assertEquals(1, totals.blockedLayer);
        assertEquals(0, totals.blockedJar);
    }

    public void testBlockedWithNoParsedErrorsIsStillACompileBlock() {
        blocked("k1", "main");
        assertEquals(1, stats.totals().blockedCompile);
    }

    public void testIdenticalBlockedPushInsideWindowCountsOnce() {
        assertTrue(blocked("k1", "main", "err"));
        now.addAndGet(1_000);
        assertFalse(blocked("k1", "main", "err"));
        assertEquals(1, stats.totals().blocked);
        now.addAndGet(PushStats.BLOCKED_DEDUPE_MILLIS);
        assertTrue(blocked("k1", "main", "err"));
        assertEquals(2, stats.totals().blocked);
    }

    public void testChangedContentIsANewBlock() {
        blocked("k1", "main", "err");
        assertTrue(blocked("k2", "main", "err"));
        assertEquals(2, stats.totals().blocked);
    }

    public void testConcurrentIdenticalAllowedCountsOnce() {
        assertTrue(allowed("k1", "main", false));
        assertFalse(allowed("k1", "main", false));
        now.addAndGet(PushStats.ALLOWED_DEDUPE_MILLIS);
        assertTrue(allowed("k1", "main", false));
        assertEquals(2, stats.totals().allowed);
    }

    public void testAllowedAfterBlockOnSameBranchIsAFix() {
        blocked("k1", "feature", "err");
        allowed("k2", "feature", false);
        assertEquals(1, stats.totals().fixedAfterBlock);
        allowed("k3", "feature", false);
        assertEquals(1, stats.totals().fixedAfterBlock);
    }

    public void testAllowedOnOtherBranchIsNotAFix() {
        blocked("k1", "feature", "err");
        allowed("k2", "main", false);
        assertEquals(0, stats.totals().fixedAfterBlock);
    }

    public void testCacheHitCountsOnlyOnAllowed() {
        allowed("k1", "main", true);
        assertEquals(1, stats.totals().cacheHits);
    }

    public void testNonVerdictResultsStayOutOfBlockedAndAllowed() {
        for (PushStats.Result result : List.of(PushStats.Result.TIMEOUT, PushStats.Result.STALE,
            PushStats.Result.INFRASTRUCTURE)) {
            stats.record("/p", "p", result, List.of(), "k", false, "main", PushStats.Source.IDE);
        }
        PushStats.Counters totals = stats.totals();
        assertEquals(0, totals.checks());
        assertEquals(1, totals.timeouts);
        assertEquals(1, totals.stale);
        assertEquals(1, totals.infrastructure);
    }

    public void testExternalVerdictsAreMarked() {
        stats.record("/p", "p", PushStats.Result.BLOCKED, List.of("err"), "", false, "",
            PushStats.Source.EXTERNAL);
        assertEquals(1, stats.totals().externalVerdicts);
    }

    public void testLogStampIsClaimedOncePerWrite() {
        assertTrue(stats.claimLogStamp("/p/.idea/last-run.log", 100));
        assertFalse(stats.claimLogStamp("/p/.idea/last-run.log", 100));
        assertFalse(stats.claimLogStamp("/p/.idea/last-run.log", 90));
        assertTrue(stats.claimLogStamp("/p/.idea/last-run.log", 101));
    }

    public void testPerProjectAndTotalsStaySeparate() {
        stats.record("/a", "a", PushStats.Result.BLOCKED, List.of("e"), "", false, "", PushStats.Source.IDE);
        stats.record("/b", "b", PushStats.Result.ALLOWED, List.of(), "", false, "", PushStats.Source.IDE);
        assertEquals(1, stats.forProject("/a").blocked);
        assertEquals(0, stats.forProject("/a").allowed);
        assertEquals(1, stats.forProject("/b").allowed);
        assertEquals(2, stats.totals().checks());
    }

    public void testRecentIsNewestFirstAndCapped() {
        for (int i = 0; i < PushStats.MAX_RECENT + 20; i++) {
            now.incrementAndGet();
            allowed("", "main", false);
        }
        List<PushStats.Event> recent = stats.recent();
        assertEquals(PushStats.MAX_RECENT, recent.size());
        assertTrue(recent.get(0).at > recent.get(recent.size() - 1).at);
    }

    public void testResetOneProjectKeepsTheOthers() {
        stats.record("/a", "a", PushStats.Result.BLOCKED, List.of("e"), "", false, "", PushStats.Source.IDE);
        stats.record("/b", "b", PushStats.Result.BLOCKED, List.of("e"), "", false, "", PushStats.Source.IDE);
        stats.reset("/a");
        assertEquals(0, stats.forProject("/a").blocked);
        assertEquals(1, stats.forProject("/b").blocked);
        assertEquals(1, stats.totals().blocked);
        assertTrue(stats.recent().stream().noneMatch(event -> event.project.equals("a")));
    }

    public void testBypassActivationsAreCounted() {
        stats.recordBypassActivation("/p", "p");
        assertEquals(1, stats.totals().bypassActivations);
        assertEquals(1, stats.forProject("/p").bypassActivations);
    }
}
