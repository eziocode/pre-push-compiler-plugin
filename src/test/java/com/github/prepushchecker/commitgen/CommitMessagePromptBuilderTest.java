package com.github.prepushchecker.commitgen;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collections;

public class CommitMessagePromptBuilderTest extends BasePlatformTestCase {
    public void testBuildForDiffDoesNotRequireRepositoryChanges() {
        CommitMessagePromptBuilder.Prompt prompt = CommitMessagePromptBuilder.buildForDiff(
            getProject(),
            "diff --git a/src/App.java b/src/App.java\n+class App {}"
        );

        assertTrue(prompt.system().contains("commit message writer"));
        assertTrue(prompt.user().contains("+class App {}"));
    }

    public void testBuildForDiffRejectsBlankDiff() {
        try {
            CommitMessagePromptBuilder.buildForDiff(getProject(), "   ");
            fail("Expected blank diffs to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("No diff content"));
        }
    }

    public void testBuildWithEmptySelectionFallsBackToAllChanges() {
        // Empty list → delegates to collectDiff(project). With no real git repo in
        // the test sandbox the diff is empty, so the same "No staged or uncommitted
        // changes" exception must be raised.
        try {
            CommitMessagePromptBuilder.build(getProject(), Collections.emptyList());
            fail("Expected exception when no git changes are present.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("No staged or uncommitted changes"));
        }
    }
}
