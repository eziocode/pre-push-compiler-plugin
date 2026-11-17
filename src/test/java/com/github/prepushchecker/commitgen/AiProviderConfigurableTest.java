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
import com.github.prepushchecker.commitgen.cli.AiCliDetector;
import com.intellij.openapi.ui.DialogWrapper;
import java.util.List;

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
            assertTrue(summary.getText(), summary.getText().startsWith("Claude Code"));
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
                Method useCli = AiProviderConfigurable.class.getDeclaredMethod("useCli", AiCli.class, String.class);
                useCli.setAccessible(true);
                useCli.invoke(page, cli, "/usr/bin/true");
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

    public void testChooserHighlightsCurrentProviderInsteadOfFirstInstalledCli() throws Exception {
        List<AiCliDetector.DetectedCli> rows = List.of(
            new AiCliDetector.DetectedCli(AiCli.GH, "/test/gh", "", ""),
            new AiCliDetector.DetectedCli(AiCli.CLAUDE, "/test/claude", "", ""),
            new AiCliDetector.DetectedCli(AiCli.CODEX, "/test/codex", "", ""));
        Class<?> type = Class.forName(AiProviderConfigurable.class.getName() + "$ChooseCliDialog");
        var constructor = type.getDeclaredConstructor(List.class, AiCli.class);
        constructor.setAccessible(true);
        Method selected = type.getDeclaredMethod("selected");
        selected.setAccessible(true);
        for (AiCli current : new AiCli[] {AiCli.CLAUDE, AiCli.CODEX}) {
            DialogWrapper dialog = (DialogWrapper) constructor.newInstance(rows, current);
            try {
                assertEquals(current, ((AiCliDetector.DetectedCli) selected.invoke(dialog)).cli());
            } finally {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE);
            }
        }
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
