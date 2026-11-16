package com.github.prepushchecker.stats;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class SharedStatsTest extends BasePlatformTestCase {
    public void testCountedVerdictsAccumulateUntilUploaded() {
        SharedStats stats = new SharedStats();
        stats.add(SharedStats.Verdict.ALLOWED, 0);
        stats.add(SharedStats.Verdict.BLOCKED, 3);
        stats.add(SharedStats.Verdict.TIMEOUT, 0);
        stats.add(SharedStats.Verdict.OTHER, 0);

        assertEquals(new SharedStats.Delta(1, 1, 3, 1), stats.pendingDelta());
    }

    public void testMarkUploadedKeepsCountsThatArrivedDuringUpload() {
        SharedStats stats = new SharedStats();
        stats.add(SharedStats.Verdict.ALLOWED, 0);
        SharedStats.Delta sent = stats.pendingDelta();
        stats.add(SharedStats.Verdict.BLOCKED, 2);
        stats.markUploaded(sent);

        assertEquals(new SharedStats.Delta(0, 1, 2, 0), stats.pendingDelta());
    }

    public void testDecliningDropsPendingAndStopsCollecting() {
        SharedStats stats = new SharedStats();
        stats.add(SharedStats.Verdict.ALLOWED, 0);
        stats.setConsent(false);
        stats.add(SharedStats.Verdict.BLOCKED, 4);

        assertTrue(stats.pendingDelta().isEmpty());
        assertEquals(SharedStats.Consent.NO, stats.getConsent());
    }

    public void testPayloadHasOnlyCountsAndIds() {
        String json = StatsUploader.payload("id-1", new SharedStats.Delta(2, 1, 5, 0));
        assertTrue(json.startsWith("{\"installId\":\"id-1\""));
        assertTrue(json.contains("\"ok\":2,\"blocked\":1,\"issues\":5,\"timeouts\":0"));
    }
}
