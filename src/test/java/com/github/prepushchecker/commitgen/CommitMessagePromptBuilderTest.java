package com.github.prepushchecker.commitgen;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

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
}
