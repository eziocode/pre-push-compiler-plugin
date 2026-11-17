package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCliCommand;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegates to the local <b>Claude Code CLI</b> ({@code claude -p}).
 *
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

    static final String INLINE_DEFAULT_MODEL = "haiku";

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        return run(LABEL, request, s.claudeCliPath, request.modelOr(s.claudeModel), s.claudeExtraArgs);
    }

    /** Runs {@code claude -p} with explicit settings; also used by the "Other CLI" provider. */
    static @NotNull String run(@NotNull String label, @NotNull AiRequest request,
                               @Nullable String configuredPath, @Nullable String model,
                               @Nullable String extraArgs) throws Exception {
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        String path = CliPathResolver.resolveOrNull(configuredPath, CLI_NAME);
        if (path == null) {
            throw new IllegalStateException(
                label + " not found.\n" + AUTH_HINT
                    + "\n\nOr set its path in " + CommitMessageSettings.SETTINGS_PATH + ".");
        }

        Map<String, String> env = new HashMap<>();
        String fallbackKey = cfg.loadApiKey(Id.CLAUDE_CLI);
        if (fallbackKey != null && !fallbackKey.isBlank()) env.put("ANTHROPIC_API_KEY", fallbackKey);

        // Inline completions with no model chosen: haiku answers in a fraction of the default's time.
        String effectiveModel = (model == null || model.isBlank()) && request.lowLatency() ? INLINE_DEFAULT_MODEL : model;
        List<String> cmd = AiCliCommand.claude(path, effectiveModel, extraArgs, request.lowLatency());
        Path workDir = AiCliRunner.createWorkDir();
        try {
            String out = AiCliRunner.run(label, cmd,
                AiCliCommand.combinePrompt(request.system(), request.user(), request),
                workDir, env, request.timeout(), AUTH_HINT);
            return AiCliRunner.finish(label, out, AUTH_HINT, request);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.CLAUDE_CLI; }
}
