package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCliCommand;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegates to the local <b>Claude Code CLI</b> ({@code claude -p}).
 *
 * <h3>How it works — same pattern as GH Copilot</h3>
 * <ol>
 *   <li>Resolves the {@code claude} binary via {@link CliPathResolver}
 *       (configured path → shell PATH → well-known npm / ~/.claude/local dirs).</li>
 *   <li>Runs {@code claude -p --output-format text [--model M]} with the prompt
 *       on stdin, so the CLI's own login (Claude subscription or API key) is used.</li>
 *   <li>If a fallback API key is saved in Settings it is exported as
 *       {@code ANTHROPIC_API_KEY} for the subprocess.</li>
 * </ol>
 */
public final class ClaudeCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "claude";
    private static final String LABEL = "Claude CLI";
    private static final String AUTH_HINT =
        "Make sure Claude Code is installed and signed in: run 'claude' once in a terminal.\n"
            + "Install: npm install -g @anthropic-ai/claude-code";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        CommitMessageSettings.State s = cfg.settings();
        String path = CliPathResolver.resolveOrNull(s.claudeCliPath, CLI_NAME);
        if (path == null) {
            throw new IllegalStateException(
                "Claude CLI not found.\n" + AUTH_HINT
                    + "\n\nOr set its path in Settings → Tools → "
                    + "Pre-Push Checker — AI Commit Message Generator.");
        }

        Map<String, String> env = new HashMap<>();
        String fallbackKey = cfg.loadApiKey(Id.CLAUDE_CLI);
        if (fallbackKey != null && !fallbackKey.isBlank()) env.put("ANTHROPIC_API_KEY", fallbackKey);

        List<String> cmd = AiCliCommand.claude(path, s.claudeModel, s.claudeExtraArgs);
        Path workDir = AiCliRunner.createWorkDir();
        try {
            String out = AiCliRunner.run(LABEL, cmd,
                AiCliCommand.combinePrompt(systemPrompt, userPrompt),
                workDir, env, AiCliRunner.DEFAULT_TIMEOUT, AUTH_HINT);
            return AiCliRunner.requireText(LABEL, out, AUTH_HINT);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.CLAUDE_CLI; }
}
