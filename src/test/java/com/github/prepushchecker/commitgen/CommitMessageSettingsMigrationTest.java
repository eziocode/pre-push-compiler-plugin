package com.github.prepushchecker.commitgen;

import junit.framework.TestCase;

public class CommitMessageSettingsMigrationTest extends TestCase {

    public void testCustomCodexMovesToCodexProvider() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.selectedProvider = CommitMessageProvider.Id.CUSTOM_CLI.name();
        s.customCliBinary = "codex";
        s.customCliPath = "/opt/homebrew/bin/codex";
        s.customCliArgs = "";
        s.customCliModel = "gpt-5-codex";

        CommitMessageSettings.migrate(s);

        assertEquals(CommitMessageProvider.Id.CODEX_CLI.name(), s.selectedProvider);
        assertEquals("/opt/homebrew/bin/codex", s.codexCliPath);
        assertEquals("gpt-5-codex", s.codexModel);
        assertEquals("sahaa", s.customCliBinary);
        assertEquals("", s.customCliArgs);
    }

    public void testExistingDedicatedSettingsAreKept() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.selectedProvider = CommitMessageProvider.Id.CUSTOM_CLI.name();
        s.customCliBinary = "claude";
        s.customCliModel = "sonnet";
        s.claudeModel = "opus";

        CommitMessageSettings.migrate(s);

        assertEquals(CommitMessageProvider.Id.CLAUDE_CLI.name(), s.selectedProvider);
        assertEquals("opus", s.claudeModel);
    }

    public void testSahaaTemplateClearedForOtherBinary() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.selectedProvider = CommitMessageProvider.Id.CUSTOM_CLI.name();
        s.customCliBinary = "gemini";
        s.customCliArgs = CommitMessageSettings.LEGACY_SAHAA_TEMPLATE;

        CommitMessageSettings.migrate(s);

        assertEquals(CommitMessageProvider.Id.CUSTOM_CLI.name(), s.selectedProvider);
        assertEquals("", s.customCliArgs);
    }

    public void testSahaaKeepsItsTemplate() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.customCliBinary = "sahaa";
        s.customCliArgs = CommitMessageSettings.LEGACY_SAHAA_TEMPLATE;

        CommitMessageSettings.migrate(s);

        assertEquals(CommitMessageSettings.LEGACY_SAHAA_TEMPLATE, s.customCliArgs);
    }

    public void testNewFileDiffForUnversionedFile() {
        String d = CommitMessagePromptBuilder.newFileDiff("src/New.java", "class New {}\n");
        assertTrue(d, d.startsWith("diff --git a/src/New.java b/src/New.java\nnew file mode 100644\n--- /dev/null\n+++ b/src/New.java\n@@ -0,0 +1,1 @@\n+class New {}\n"));
    }
}
