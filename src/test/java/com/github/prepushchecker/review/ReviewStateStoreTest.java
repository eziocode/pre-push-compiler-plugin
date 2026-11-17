package com.github.prepushchecker.review;

import junit.framework.TestCase;

import java.util.List;

public class ReviewStateStoreTest extends TestCase {

    public void testCleanReviewApprovesThatContentOnly() {
        ReviewStateStore store = new ReviewStateStore();
        store.record("/r/A.java", "blob1", List.of());
        assertTrue(store.isApproved("/r/A.java", "blob1"));
        assertFalse(store.isApproved("/r/A.java", "blob2"));
        assertFalse(store.isApproved("/r/B.java", "blob1"));
        assertFalse(store.isApproved("/r/A.java", null));
    }

    public void testResolvingLastFindingMovesApprovalToCurrentContent() {
        ReviewStateStore store = new ReviewStateStore();
        ReviewFinding f1 = new ReviewFinding("/r/A.java", 3, "R", "m1", "");
        ReviewFinding f2 = new ReviewFinding("/r/A.java", 9, "R", "m2", "");
        store.record("/r/A.java", "blob1", List.of(f1, f2));
        assertFalse(store.isApproved("/r/A.java", "blob1"));

        long before = store.modificationCount();
        assertTrue(store.resolve(f1.id, "manual", "blob2"));
        assertTrue(store.modificationCount() > before);
        assertFalse(store.isApproved("/r/A.java", "blob2"));
        assertEquals("blob1", store.review("/r/A.java").contentId);

        assertTrue(store.resolve(f2.id, "ai", "blob2"));
        assertTrue(store.isApproved("/r/A.java", "blob2"));
        assertFalse(store.isApproved("/r/A.java", "blob1"));
        assertFalse(store.resolve(f2.id, "ai", "blob3"));
        assertEquals("ai", store.finding(f2.id).resolvedBy);
    }

    public void testNewReviewReplacesOld() {
        ReviewStateStore store = new ReviewStateStore();
        store.record("/r/A.java", "blob1", List.of(new ReviewFinding("/r/A.java", 1, "", "m", "")));
        store.record("/r/A.java", "blob2", List.of());
        assertEquals(1, store.reviews().size());
        assertTrue(store.isApproved("/r/A.java", "blob2"));
    }

    public void testCopiesDoNotLeakIntoStore() {
        ReviewStateStore store = new ReviewStateStore();
        store.record("/r/A.java", "blob1", List.of(new ReviewFinding("/r/A.java", 1, "", "m", "")));
        store.reviews().get(0).findings.get(0).resolved = true;
        assertFalse(store.isApproved("/r/A.java", "blob1"));
    }

    public void testRevertToEarlierApprovedVersionStaysApproved() {
        ReviewStateStore store = new ReviewStateStore();
        store.record("/r/A.java", "v1", List.of());
        // v2 reviewed with a finding, then the developer reverts the edit back to v1.
        store.record("/r/A.java", "v2", List.of(new ReviewFinding("/r/A.java", 1, "", "m", "")));
        assertTrue(store.isApproved("/r/A.java", "v1"));
        assertFalse(store.isApproved("/r/A.java", "v2"));
        // A version that only ever had open findings is never in the history.
        store.record("/r/A.java", "v3", List.of());
        assertFalse(store.isApproved("/r/A.java", "v2"));
        assertTrue(store.isApproved("/r/A.java", "v1"));
        assertTrue(store.isApproved("/r/A.java", "v3"));
        store.clear();
        assertFalse(store.isApproved("/r/A.java", "v1"));
    }

    public void testApprovedHistoryIsBounded() {
        ReviewStateStore store = new ReviewStateStore();
        for (int i = 0; i <= ReviewStateStore.MAX_APPROVED_HISTORY + 1; i++) store.record("/r/A.java", "v" + i, List.of());
        assertFalse(store.isApproved("/r/A.java", "v0"));
        assertTrue(store.isApproved("/r/A.java", "v2"));
    }

    public void testRetainOnlyDropsSettledFilesOfThatRootOnly() {
        ReviewStateStore store = new ReviewStateStore();
        store.record("/r/a/X.java", "1", List.of());
        store.record("/r/a/Y.java", "2", List.of());
        store.record("/r/b/Z.java", "3", List.of());
        assertEquals(1, store.retainOnly("/r/a/", java.util.Set.of("X.java")));
        assertNotNull(store.review("/r/a/X.java"));
        assertNull(store.review("/r/a/Y.java"));
        assertNotNull(store.review("/r/b/Z.java"));
        assertEquals(0, store.retainOnly("/r/a/", java.util.Set.of("X.java")));
    }
}
