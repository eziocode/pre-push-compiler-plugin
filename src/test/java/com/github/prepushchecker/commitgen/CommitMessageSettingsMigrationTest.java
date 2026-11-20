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
        assertEquals("", s.customCliBinary);
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

    public void testInstalledSahaaKeepsItsTemplateAndBecomesACustomEntry() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.customCliBinary = "sahaa";
        s.customCliArgs = CommitMessageSettings.LEGACY_SAHAA_TEMPLATE;

        CommitMessageSettings.migrate(s, (path, name) -> true);

        assertEquals("sahaa", s.customCliBinary);
        assertEquals(CommitMessageSettings.LEGACY_SAHAA_TEMPLATE, s.customCliArgs);
        CommitMessageSettings.CustomCliEntry e = CommitMessageSettings.findCustom(s, "sahaa");
        assertNotNull(e);
        assertTrue(e.userAdded);
        assertEquals(CommitMessageSettings.LEGACY_SAHAA_TEMPLATE, e.argsTemplate);
    }

    public void testLegacyDefaultSahaaKeptOnlyWhenInstalled() {
        // Before 3.3 "sahaa" was the default, so a saved "Other CLI" choice has no binary stored.
        CommitMessageSettings.State installed = new CommitMessageSettings.State();
        installed.selectedProvider = CommitMessageProvider.Id.CUSTOM_CLI.name();
        CommitMessageSettings.migrate(installed, (path, name) -> "sahaa".equals(name));
        assertEquals("sahaa", installed.customCliBinary);
        assertEquals(CommitMessageSettings.LEGACY_SAHAA_TEMPLATE, installed.customCliArgs);
        assertEquals(1, CommitMessageSettings.userCustomClis(installed).size());

        CommitMessageSettings.State missing = new CommitMessageSettings.State();
        missing.selectedProvider = CommitMessageProvider.Id.CUSTOM_CLI.name();
        missing.customCliModel = "m";
        CommitMessageSettings.migrate(missing, (path, name) -> false);
        assertEquals("", missing.customCliBinary);
        assertEquals("", missing.customCliModel);
        assertTrue(missing.customClis.isEmpty());
    }

    public void testFreshStateHasNoSahaa() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        CommitMessageSettings.migrate(s, (path, name) -> true);
        assertEquals("", s.customCliBinary);
        assertTrue(s.customClis.isEmpty());
    }

    public void testSwitchingCustomClisKeepsEachOnesValues() {
        CommitMessageSettings.State s = new CommitMessageSettings.State();
        s.customClis.add(new CommitMessageSettings.CustomCliEntry("mine", "/opt/mine", "ask {prompt}", "", "m1", true));
        CommitMessageSettings.activateCustom(s, "mine");
        assertEquals("/opt/mine", s.customCliPath);
        assertEquals("ask {prompt}", s.customCliArgs);
        s.customCliModel = "m2";

        CommitMessageSettings.activateCustom(s, "gemini");
        assertEquals("gemini", s.customCliBinary);
        assertEquals("", s.customCliModel);
        s.customCliModel = "gemini-pro";

        CommitMessageSettings.activateCustom(s, "mine");
        assertEquals("m2", s.customCliModel);
        CommitMessageSettings.activateCustom(s, "gemini");
        assertEquals("gemini-pro", s.customCliModel);
        assertEquals(1, CommitMessageSettings.userCustomClis(s).size());
    }

    public void testNewFileDiffForUnversionedFile() {
        String d = CommitMessagePromptBuilder.newFileDiff("src/New.java", "class New {}\n");
        assertTrue(d, d.startsWith("diff --git a/src/New.java b/src/New.java\nnew file mode 100644\n--- /dev/null\n+++ b/src/New.java\n@@ -0,0 +1,1 @@\n+class New {}\n"));
    }
}
