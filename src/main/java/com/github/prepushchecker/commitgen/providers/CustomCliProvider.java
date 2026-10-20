package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCli;
import com.github.prepushchecker.commitgen.cli.AiCliCommand;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Delegates to any other local AI CLI (sahaa, gemini, cursor-agent, copilot,
 * opencode, …) using a user-editable argument template.
 *
 * <p>The binary is auto-detected by name like {@code gh}; the user can override
 * the path. Template placeholders: {@code {model}}, {@code {model?…}} and
 * {@code {prompt}} — see {@link AiCliCommand#fromTemplate}. Without
 * {@code {prompt}} the prompt is sent on stdin.</p>
 */
public final class CustomCliProvider implements CommitMessageProvider {

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String binary = s.customCliBinary == null || s.customCliBinary.isBlank()
            ? AiCli.SAHAA.binaryName() : s.customCliBinary.trim();
        AiCli known = AiCli.byBinary(binary);
        String label = (known != null ? known.displayName() : binary) + " CLI";
        String hint = "Make sure '" + binary + "' is installed and signed in."
            + (known != null ? "\nInstall: " + known.installHint() : "")
            + "\nCheck the arguments template in Settings.";

        String path = CliPathResolver.resolveOrNull(s.customCliPath, binary);
        if (path == null) {
            throw new IllegalStateException(
                label + " not found.\n" + hint
                    + "\n\nSet its path in Settings → Tools → "
                    + "Pre-Push Checker — AI Commit Message Generator.");
        }

        String prompt = AiCliCommand.combinePrompt(systemPrompt, userPrompt);
        List<String> cmd = AiCliCommand.fromTemplate(path, s.customCliArgs, s.customCliModel, prompt);
        String stdin = AiCliCommand.usesPromptArg(s.customCliArgs) ? null : prompt;

        Path workDir = AiCliRunner.createWorkDir();
        try {
            String out = AiCliRunner.run(label, cmd, stdin, workDir, Map.of(),
                AiCliRunner.DEFAULT_TIMEOUT, hint);
            return AiCliRunner.requireText(label, out, hint);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.CUSTOM_CLI; }
}
