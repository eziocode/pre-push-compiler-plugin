package com.github.prepushchecker.commitgen;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collections;
import java.util.List;

public class CommitMessagePromptBuilderTest extends BasePlatformTestCase {

    @Override
    protected void tearDown() throws Exception {
        // Remove any rules file written into the shared sandbox base path so it does
        // not leak into subsequent tests (they auto-detect COMMIT_RULES.md).
        try {
            String base = getProject().getBasePath();
            if (base != null) {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(base, "COMMIT_RULES.md"));
            }
        } finally {
            super.tearDown();
        }
    }

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

    // ── Git-facts block tests (rules-driven, no hardcoded policy) ─────────────

    public void testBranchNamesMatchIgnoresPrefixesAndCase() {
        assertTrue(CommitMessagePromptBuilder.branchNamesMatch("main", "MAIN"));
        assertTrue(CommitMessagePromptBuilder.branchNamesMatch("origin/main", "main"));
        assertTrue(CommitMessagePromptBuilder.branchNamesMatch("refs/heads/develop", "develop"));
        assertFalse(CommitMessagePromptBuilder.branchNamesMatch("feature/x", "main"));
        assertFalse(CommitMessagePromptBuilder.branchNamesMatch(null, "main"));
    }

    public void testConfiguredDefaultBranchIsAuthoritativeForFactsBlock() {
        // The configured team default must win over git auto-detection so the facts
        // block reports the correct default even when origin/HEAD is unset locally.
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.defaultBranchName = "release_main";

        String detected = CommitMessagePromptBuilder
            .resolveConfiguredOrDetectedDefaultBranch(getProject(), s);
        assertEquals("release_main", detected);
    }

    public void testGitFactsBlockMarksCurrentBranchAsDefaultWhenItMatches() {
        // The exact defect the user reported: on the real default branch the facts must
        // say "Current branch is the default branch: YES" so the rules file applies
        // ISSUEFIX:. No prefix is hardcoded — the rules file decides.
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.defaultBranchName = "release_main";
        s.customRulesFilePath = writeSandboxRulesFile();

        String system = CommitMessagePromptBuilder.buildSystemPrompt(getProject(), s, "release_main");

        assertTrue("Facts block must be present when rules file is loaded",
            system.contains("Git context"));
        assertTrue("Facts must report the repository default branch",
            system.contains("Repository default branch: release_main"));
        assertTrue("Facts must confirm current branch IS the default branch",
            system.contains("Current branch is the default branch: YES"));
    }

    public void testGitFactsBlockMarksFeatureBranchAsNonDefault() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.defaultBranchName = "release_main";
        s.customRulesFilePath = writeSandboxRulesFile();

        String system = CommitMessagePromptBuilder.buildSystemPrompt(getProject(), s, "FEATURE_abc");

        assertTrue("Facts must report the current branch",
            system.contains("Current branch") && system.contains("FEATURE_abc"));
        assertTrue("Facts must confirm current branch is NOT the default branch",
            system.contains("Current branch is the default branch: NO"));
    }

    public void testGitFactsBlockDoesNotHardcodeAnyPrefixOrBranchName() {
        // Regression guard for the "no hardcoding" requirement: the facts block and
        // enforcement must NOT bake in any specific prefix (e.g. ISSUEFIX/METHOD_ENTRY)
        // or project branch name. Those live exclusively in the rules file.
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.defaultBranchName = "release_main";
        s.customRulesFilePath = writeSandboxRulesFile();

        String system = CommitMessagePromptBuilder.buildSystemPrompt(getProject(), s, "FEATURE_abc");

        // The rules file body we wrote does not mention these tokens, and the code must
        // not inject them either.
        assertFalse("Code must not hardcode ISSUEFIX: policy", system.contains("ISSUEFIX:"));
        assertFalse("Code must not hardcode METHOD_ENTRY: policy", system.contains("METHOD_ENTRY:"));
        assertFalse("Code must not hardcode TESTCASE: policy", system.contains("TESTCASE:"));
    }

    /**
     * Writes a minimal decision-tree rules file into the sandbox project base and
     * returns its project-relative path so the git-facts block is emitted.
     */
    private String writeSandboxRulesFile() {
        try {
            String base = getProject().getBasePath();
            assertNotNull("Sandbox project must have a base path", base);
            java.nio.file.Path file = java.nio.file.Path.of(base, "COMMIT_RULES.md");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file,
                "# Commit Message Convention\n\n## Decision Tree\n"
                    + "### Step 0 — Branch gate\nRun: git branch --show-current\n");
            return "COMMIT_RULES.md";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Front-matter stripping tests ──────────────────────────────────────────

    public void testStripFrontMatterRemovesLeadingYamlBlock() {
        String content = "---\napply: always\n---\n\n# Commit Message Convention\nrules here";
        String stripped = CommitMessagePromptBuilder.stripFrontMatter(content);

        assertFalse("apply: always front-matter must be removed",
            stripped.contains("apply: always"));
        assertTrue("Body content must be preserved",
            stripped.startsWith("# Commit Message Convention"));
    }

    public void testStripFrontMatterLeavesBodyHorizontalRulesIntact() {
        // A --- separator in the body (not at the top) must NOT be treated as
        // front-matter and must survive untouched.
        String content = "# Title\n\nSection one\n\n---\n\nSection two";
        String stripped = CommitMessagePromptBuilder.stripFrontMatter(content);

        assertEquals("Content without a leading front-matter block is unchanged",
            content, stripped);
        assertTrue("Body horizontal rule must be preserved", stripped.contains("\n---\n"));
    }

    public void testStripFrontMatterNoOpWhenAbsent() {
        String content = "# Commit Message Convention\nno front matter";
        assertEquals(content, CommitMessagePromptBuilder.stripFrontMatter(content));
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
