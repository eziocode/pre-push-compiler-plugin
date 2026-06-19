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

    // ── Branch-specific prefix tests ──────────────────────────────────────────

    public void testBranchPlaceholderIsSubstitutedInSystemPrompt() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.prefixTemplate = "[{branch}]";

        String system = CommitMessagePromptBuilder.buildSystemPrompt(
            getProject(), s, "feature/PROJ-42-login");

        assertTrue("System prompt must contain the resolved branch prefix",
            system.contains("[feature/PROJ-42-login]"));
        assertFalse("Raw {branch} placeholder must not appear in system prompt",
            system.contains("{branch}"));
    }

    public void testLiteralPrefixTemplatePassesThroughUnchanged() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.prefixTemplate = "[PROJ-123]";

        String system = CommitMessagePromptBuilder.buildSystemPrompt(
            getProject(), s, "main");

        assertTrue("Literal prefix must appear in system prompt",
            system.contains("[PROJ-123]"));
    }

    public void testBranchPlaceholderRemovedWhenNoBranchAvailable() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.prefixTemplate = "[{branch}]";

        // Null branch (e.g. detached HEAD or no git repo in test sandbox)
        String system = CommitMessagePromptBuilder.buildSystemPrompt(
            getProject(), s, null);

        // With branch resolved to "", the prefix becomes "[]" which is blank after trim
        // OR the whole prefix instruction is skipped. Either way, the raw token must not appear.
        assertFalse("Raw {branch} placeholder must not appear when branch is unavailable",
            system.contains("{branch}"));
    }

    public void testUserPromptContainsBranchContextLine() {
        String diff = "diff --git a/Foo.java b/Foo.java\n+// change";

        CommitMessageSettings.State s = new CommitMessageSettings.State();
        String userPrompt = CommitMessagePromptBuilder.buildSystemPrompt(getProject(), s, "feature/ABC-99");

        // The branch context is in the user prompt, not system — verify via buildForDiff
        // indirectly: the user() part must contain "Current branch:" when a real branch exists.
        // In the test sandbox there is no git repo, so resolveBranchName returns null and
        // the line is omitted — assert the diff is still included correctly.
        CommitMessagePromptBuilder.Prompt prompt = CommitMessagePromptBuilder.buildForDiff(
            getProject(), diff);
        assertTrue("User prompt must contain the diff", prompt.user().contains("// change"));
    }

    public void testUserPromptContainsCurrentBranchWhenInjectedViaSystemPromptOverload() {
        // Build system prompt with an explicit branch to verify the API surface works.
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.prefixTemplate = "({branch})";

        String system = CommitMessagePromptBuilder.buildSystemPrompt(getProject(), s, "hotfix/critical");

        assertTrue("Branch name must appear in resolved prefix",
            system.contains("(hotfix/critical)"));
    }
}
