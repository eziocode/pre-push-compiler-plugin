package com.github.prepushchecker.review;

import junit.framework.TestCase;

import java.util.List;

import static com.github.prepushchecker.review.ReviewGate.Action.ALLOW;
import static com.github.prepushchecker.review.ReviewGate.Action.BLOCK;
import static com.github.prepushchecker.review.ReviewGate.Action.REVIEW_NOW;
import static com.github.prepushchecker.review.ReviewSettings.Mode.ON_DEMAND;
import static com.github.prepushchecker.review.ReviewSettings.Mode.ON_PUSH;

public class ReviewGateTest extends TestCase {

    public void testEverythingApprovedAllows() {
        assertEquals(ALLOW, ReviewGate.decide(ON_DEMAND, true, List.of(), List.of()).action());
        assertEquals(ALLOW, ReviewGate.decide(ON_PUSH, true, List.of(), List.of()).action());
    }

    public void testOnDemandMandatoryBlocksOnlyUnreviewedFiles() {
        ReviewGate.Decision d = ReviewGate.decide(ON_DEMAND, true, List.of("B.java"), List.of());
        assertEquals(BLOCK, d.action());
        assertEquals(List.of("B.java"), d.unreviewed());
    }

    public void testOnDemandNotMandatoryAllows() {
        assertEquals(ALLOW, ReviewGate.decide(ON_DEMAND, false, List.of("B.java"), List.of("C.java")).action());
    }

    public void testOpenFindingsBlockWhenMandatory() {
        ReviewGate.Decision d = ReviewGate.decide(ON_DEMAND, true, List.of(), List.of("C.java"));
        assertEquals(BLOCK, d.action());
        assertEquals(List.of("C.java"), d.open());
    }

    public void testOnPushReviewsOnlyUnreviewed() {
        ReviewGate.Decision d = ReviewGate.decide(ON_PUSH, false, List.of("B.java"), List.of("C.java"));
        assertEquals(REVIEW_NOW, d.action());
        assertEquals(List.of("B.java"), d.toReview());
    }

    public void testAfterPushReview() {
        assertEquals(ALLOW, ReviewGate.afterPushReview(false, List.of("B.java"), List.of("C.java")).action());
        assertEquals(ALLOW, ReviewGate.afterPushReview(true, List.of(), List.of()).action());
        assertEquals(BLOCK, ReviewGate.afterPushReview(true, List.of(), List.of("C.java")).action());
        assertEquals(BLOCK, ReviewGate.afterPushReview(true, List.of("B.java"), List.of()).action());
    }
}
