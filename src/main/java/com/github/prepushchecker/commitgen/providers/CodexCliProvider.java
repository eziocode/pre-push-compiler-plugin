package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.ChatGPTOAuthFlow;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCliCommand;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegates to the local <b>Codex CLI</b> ({@code codex exec}).
 *
 * <h3>How it works — same pattern as GH Copilot</h3>
 * <ol>
 *   <li>Resolves the {@code codex} binary via {@link CliPathResolver}.</li>
 *   <li>Runs {@code codex exec --sandbox read-only … -} in an empty temp dir with
 *       the prompt on stdin, using the CLI's own login ({@code codex login}).</li>
 *   <li>Reads the final answer from {@code --output-last-message}, falling back
 *       to stdout.</li>
 *   <li>A manual API key saved in Settings is exported as {@code OPENAI_API_KEY}
 *       / {@code CODEX_API_KEY} for the subprocess.</li>
 * </ol>
 */
public final class CodexCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "codex";
    private static final String LABEL = "Codex CLI";
    private static final String AUTH_HINT =
        "Make sure Codex is installed and signed in: codex login\n"
            + "Install: npm install -g @openai/codex";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        CommitMessageSettings.State s = cfg.settings();
        String path = CliPathResolver.resolveOrNull(s.codexCliPath, CLI_NAME);
        if (path == null) {
            throw new IllegalStateException(
                "Codex CLI not found.\n" + AUTH_HINT
                    + "\n\nOr set its path in Settings → Tools → "
                    + "Pre-Push Checker — AI Commit Message Generator.");
        }

        Map<String, String> env = new HashMap<>();
        String manualKey = ChatGPTOAuthFlow.loadManualKey();
        if (manualKey == null || manualKey.isBlank()) manualKey = cfg.loadApiKey(Id.CODEX_CLI);
        if (manualKey != null && !manualKey.isBlank()) {
            env.put("OPENAI_API_KEY", manualKey);
            env.put("CODEX_API_KEY", manualKey);
        }

        Path workDir = AiCliRunner.createWorkDir();
        try {
            Path lastMessage = workDir.resolve("last-message.txt");
            List<String> cmd = AiCliCommand.codex(path, s.codexModel, s.codexExtraArgs, lastMessage);
            String stdout = AiCliRunner.run(LABEL, cmd,
                AiCliCommand.combinePrompt(systemPrompt, userPrompt),
                workDir, env, AiCliRunner.DEFAULT_TIMEOUT, AUTH_HINT);
            String result = Files.isRegularFile(lastMessage)
                ? Files.readString(lastMessage, StandardCharsets.UTF_8)
                : stdout;
            return AiCliRunner.requireText(LABEL, result, AUTH_HINT);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }
}
