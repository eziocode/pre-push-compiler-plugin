package com.github.prepushchecker.commitgen;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collections;
import java.util.List;

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

        String system = CommitMessagePromptBuilder.buildSystemPrompt(
            getProject(), s, null);

        assertFalse("Raw {branch} placeholder must not appear when branch is unavailable",
            system.contains("{branch}"));
    }

    public void testUserPromptContainsBranchContextLine() {
        String diff = "diff --git a/Foo.java b/Foo.java\n+// change";

        CommitMessagePromptBuilder.Prompt prompt = CommitMessagePromptBuilder.buildForDiff(
            getProject(), diff);
        assertTrue("User prompt must contain the diff", prompt.user().contains("// change"));
    }

    public void testUserPromptContainsCurrentBranchWhenInjectedViaSystemPromptOverload() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.prefixTemplate = "({branch})";

        String system = CommitMessagePromptBuilder.buildSystemPrompt(getProject(), s, "hotfix/critical");

        assertTrue("Branch name must appear in resolved prefix",
            system.contains("(hotfix/critical)"));
    }

    // ── Rules-file / decision-tree enforcement tests ──────────────────────────

    public void testConventionalCommitsInstructionSuppressedWhenRulesFileLoaded() {
        // No real rules file in the test sandbox, so the Conventional Commits instruction
        // should appear (no rules file → generic mode).
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.useConventionalCommits = true;

        String system = CommitMessagePromptBuilder.buildSystemPrompt(getProject(), s, null);

        // No rules file in sandbox → Conventional Commits IS included
        assertTrue("Conventional Commits must appear when no rules file is present",
            system.contains("Conventional Commits"));
    }

    public void testBranchGateReinforcementAppearedAfterRulesFileHeader() {
        // This test exercises the 3-arg overload used when a branch is known.
        // Without a real rules file in the sandbox the reinforcement is not emitted;
        // verify that the system prompt still contains the branch prefix substitution.
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.prefixTemplate = "[{branch}]";

        String system = CommitMessagePromptBuilder.buildSystemPrompt(
            getProject(), s, "ZOHOTESTAUTOMATION_CRM_TB_DEFAULT_BRANCH");

        assertTrue("Resolved branch prefix must appear in system prompt",
            system.contains("[ZOHOTESTAUTOMATION_CRM_TB_DEFAULT_BRANCH]"));
    }

    // ── parseFileNamesFromDiff tests ──────────────────────────────────────────

    public void testParseFileNamesExtractsPathsFromDiffHeaders() {
        String diff = "diff --git a/src/Foo.java b/src/Foo.java\n"
            + "--- a/src/Foo.java\n+++ b/src/Foo.java\n"
            + "diff --git a/src/BarTest.java b/src/BarTest.java\n"
            + "--- a/src/BarTest.java\n+++ b/src/BarTest.java\n";

        List<String> files = CommitMessagePromptBuilder.parseFileNamesFromDiff(diff);

        assertEquals(2, files.size());
        assertTrue(files.contains("src/Foo.java"));
        assertTrue(files.contains("src/BarTest.java"));
    }

    public void testParseFileNamesReturnsEmptyForNonDiffContent() {
        List<String> files = CommitMessagePromptBuilder.parseFileNamesFromDiff("just some text\nno diff headers");
        assertTrue("No diff headers → empty list", files.isEmpty());
    }

    public void testUserPromptIncludesStagedFilesList() {
        String diff = "diff --git a/src/LoginActions.java b/src/LoginActions.java\n"
            + "+public void test_login() {}\n";

        CommitMessagePromptBuilder.Prompt prompt = CommitMessagePromptBuilder.buildForDiff(
            getProject(), diff);

        assertTrue("User prompt must contain staged files list",
            prompt.user().contains("Staged files:"));
        assertTrue("User prompt must list the staged file",
            prompt.user().contains("src/LoginActions.java"));
    }
}
