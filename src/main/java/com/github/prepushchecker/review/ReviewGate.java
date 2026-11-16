package com.github.prepushchecker.review;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Push decision for the AI review, as a pure function so every combination is testable.
 *
 * <p>Inputs are the pushed files that the rule file puts in scope, split by their state:</p>
 * <ul>
 *   <li><b>approved</b>: this exact content was reviewed and every finding is resolved;</li>
 *   <li><b>open</b>: this content was reviewed and still has open findings;</li>
 *   <li><b>unreviewed</b>: no review of this content exists.</li>
 * </ul>
 */
public final class ReviewGate {

    public enum Action {
        /** Push goes on to the compile check. */
        ALLOW,
        /** Review {@link Decision#toReview()} inside the push, then call {@link #afterPushReview}. */
        REVIEW_NOW,
        /** Push is blocked: {@link Decision#unreviewed()} need a review, {@link Decision#open()} need fixing. */
        BLOCK
    }

    public record Decision(@NotNull Action action,
                           @NotNull List<String> toReview,
                           @NotNull List<String> unreviewed,
                           @NotNull List<String> open) {
        static Decision allow() {
            return new Decision(Action.ALLOW, List.of(), List.of(), List.of());
        }
    }

    private ReviewGate() {}

    public static @NotNull Decision decide(@NotNull ReviewSettings.Mode mode, boolean mandatory,
                                           @NotNull List<String> unreviewed, @NotNull List<String> open) {
        if (mode == ReviewSettings.Mode.ON_PUSH && !unreviewed.isEmpty()) {
            return new Decision(Action.REVIEW_NOW, List.copyOf(unreviewed), List.of(), List.copyOf(open));
        }
        if (!mandatory || (unreviewed.isEmpty() && open.isEmpty())) return Decision.allow();
        return new Decision(Action.BLOCK, List.of(), List.copyOf(unreviewed), List.copyOf(open));
    }

    /**
     * After an on-push review: block only when mandatory and some file still has open findings
     * (from this review or an earlier one) or could not be reviewed.
     */
    public static @NotNull Decision afterPushReview(boolean mandatory, @NotNull List<String> stillUnreviewed,
                                                    @NotNull List<String> open) {
        if (!mandatory || (stillUnreviewed.isEmpty() && open.isEmpty())) return Decision.allow();
        return new Decision(Action.BLOCK, List.of(), List.copyOf(stillUnreviewed), List.copyOf(open));
    }
}
