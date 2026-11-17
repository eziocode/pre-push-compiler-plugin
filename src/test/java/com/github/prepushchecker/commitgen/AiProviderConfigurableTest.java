package com.github.prepushchecker.commitgen;

import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JLabel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import com.github.prepushchecker.commitgen.cli.AiCli;

public class AiProviderConfigurableTest extends BasePlatformTestCase {
    private boolean registeredSettings;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (CommitMessageSettings.getInstance() == null) {
            com.intellij.testFramework.ServiceContainerUtil.registerServiceInstance(
                com.intellij.openapi.application.ApplicationManager.getApplication(),
                CommitMessageSettings.class, new CommitMessageSettings());
            registeredSettings = true;
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (registeredSettings) com.intellij.testFramework.ServiceContainerUtil.unregisterService(
                com.intellij.openapi.application.ApplicationManager.getApplication(), CommitMessageSettings.class);
        } finally {
            super.tearDown();
        }
    }

    public void testOldGithubProbeCannotReplaceNewCliSummary() throws Exception {
        Path dir = Files.createTempDirectory("cli-summary-test");
        Path started = dir.resolve("started");
        Path release = dir.resolve("release");
        Path slow = dir.resolve("gh");
        Files.writeString(slow, "#!/bin/sh\n"
            + "touch '" + started + "'\n"
            + "while [ ! -f '" + release + "' ]; do sleep 0.01; done\n"
            + "echo old-github-version\n");
        assertTrue(slow.toFile().setExecutable(true));
        AiProviderConfigurable page = new AiProviderConfigurable();
        try {
            Future<?> oldProbe = selectForSummary(page, CommitMessageProvider.Id.GH_COPILOT, slow.toString());
            await(() -> Files.exists(started));
            Future<?> currentProbe = selectForSummary(page, CommitMessageProvider.Id.CLAUDE_CLI, "/usr/bin/true");
            JLabel summary = (JLabel) field(page, "cliSummary").get(page);
            currentProbe.get(5, TimeUnit.SECONDS);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue(summary.getText(), summary.getText().contains("/usr/bin/true"));
            Files.createFile(release);
            oldProbe.get(5, TimeUnit.SECONDS);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue(summary.getText(), summary.getText().contains("/usr/bin/true"));
            assertFalse(summary.getText(), summary.getText().contains("old-github-version"));
        } finally {
            Files.writeString(release, "");
            page.disposeUIResources();
        }
    }

    public void testProviderSurvivesApplyAndReset() throws Exception {
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        CommitMessageSettings.State original = settings.settings();
        settings.loadState(new CommitMessageSettings.State());
        AiProviderConfigurable page = new AiProviderConfigurable();
        try {
            page.createComponent();
            ((javax.swing.JRadioButton) field(page, "cliRadio").get(page)).doClick();
            for (CommitMessageProvider.Id id : new CommitMessageProvider.Id[] {
                CommitMessageProvider.Id.CLAUDE_CLI, CommitMessageProvider.Id.CODEX_CLI,
                CommitMessageProvider.Id.CUSTOM_CLI
            }) {
                AiCli cli = id == CommitMessageProvider.Id.CLAUDE_CLI ? AiCli.CLAUDE
                    : id == CommitMessageProvider.Id.CODEX_CLI ? AiCli.CODEX : AiCli.SAHAA;
                combo(page).setSelectedItem(cli);
                ((com.intellij.openapi.ui.TextFieldWithBrowseButton) field(page, "cliPathField").get(page))
                    .setText("/usr/bin/true");
                var models = (javax.swing.JComboBox<?>) field(page, "cliModelCombo").get(page);
                models.getEditor().setItem("chosen-model");
                assertTrue(page.isModified());
                page.apply();
                page.reset();
                assertEquals(id.name(), settings.settings().selectedProvider);
                assertEquals(id, field(page, "cliId").get(page));
                assertEquals("chosen-model", models.getEditor().getItem());
                assertEquals("/usr/bin/true", ((com.intellij.openapi.ui.TextFieldWithBrowseButton)
                    field(page, "cliPathField").get(page)).getText());
                assertFalse(page.isModified());
            }
        } finally {
            page.disposeUIResources();
            settings.loadState(original);
        }
    }

    public void testComboSwitchStaysAfterPlatformFocusReset() throws Exception {
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        CommitMessageSettings.State original = settings.settings();
        CommitMessageSettings.State saved = new CommitMessageSettings.State();
        saved.selectedProvider = CommitMessageProvider.Id.GH_COPILOT.name();
        settings.loadState(saved);
        AiProviderConfigurable page = new AiProviderConfigurable();
        try {
            page.createComponent();
            combo(page).setSelectedItem(AiCli.CODEX);
            assertEquals(CommitMessageProvider.Id.CODEX_CLI, field(page, "cliId").get(page));
            assertTrue(page.isModified());
            // Right after one of the page's own modal windows, a reset from the platform keeps the edit.
            field(page, "ownModalUntil").set(page, System.currentTimeMillis() + 60_000);
            page.reset();
            assertEquals(CommitMessageProvider.Id.CODEX_CLI, field(page, "cliId").get(page));
            assertEquals(AiCli.CODEX, combo(page).getSelectedItem());
            // Outside that window an explicit reset (Revert changes) still restores the saved CLI.
            field(page, "ownModalUntil").set(page, 0L);
            page.reset();
            assertEquals(CommitMessageProvider.Id.GH_COPILOT, field(page, "cliId").get(page));
            assertEquals(AiCli.GH, combo(page).getSelectedItem());
        } finally {
            page.disposeUIResources();
            settings.loadState(original);
        }
    }

    public void testSwitchingOtherCliBinaryDropsPreviousModel() throws Exception {
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        CommitMessageSettings.State original = settings.settings();
        CommitMessageSettings.State saved = new CommitMessageSettings.State();
        saved.selectedProvider = CommitMessageProvider.Id.CUSTOM_CLI.name();
        saved.customCliBinary = "gemini";
        saved.customCliModel = "gemini-model";
        settings.loadState(saved);
        AiProviderConfigurable page = new AiProviderConfigurable();
        try {
            page.createComponent();
            var models = (javax.swing.JComboBox<?>) field(page, "cliModelCombo").get(page);
            assertEquals("gemini-model", models.getEditor().getItem());
            combo(page).setSelectedItem(AiCli.OPENCODE);
            assertEquals("", models.getEditor().getItem());
        } finally {
            page.disposeUIResources();
            settings.loadState(original);
        }
    }

    @SuppressWarnings("unchecked")
    private static javax.swing.JComboBox<AiCli> combo(AiProviderConfigurable page) throws Exception {
        return (javax.swing.JComboBox<AiCli>) field(page, "cliCombo").get(page);
    }

    private static Future<?> selectForSummary(AiProviderConfigurable page, CommitMessageProvider.Id id,
                                         String path) throws Exception {
        field(page, "cliId").set(page, id);
        ((com.intellij.openapi.ui.TextFieldWithBrowseButton) field(page, "cliPathField").get(page)).setText(path);
        Method refresh = AiProviderConfigurable.class.getDeclaredMethod("refreshCliSummary");
        refresh.setAccessible(true);
        return (Future<?>) refresh.invoke(page);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        assertTrue("Timed out waiting for CLI probe", await(condition, 5000));
    }

    private static boolean await(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return false;
    }
}
