package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.ChatGPTOAuthFlow;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCliCommand;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegates to the local <b>Codex CLI</b> ({@code codex exec}).
 *
 * <ol>
 *   <li>Resolves the {@code codex} binary via {@link CliPathResolver}.</li>
 *   <li>Runs {@code codex exec --sandbox read-only … -} in an empty temp dir with
 *       the prompt on stdin, using the CLI's own login ({@code codex login}). Never the bare
 *       {@code codex} command, which starts the interactive terminal UI.</li>
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
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        return run(LABEL, request, s.codexCliPath, request.modelOr(s.codexModel), s.codexExtraArgs,
            AiCliCommand.effortFor(request, s.codexEffort));
    }

    static @NotNull String run(@NotNull String label, @NotNull AiRequest request,
                               @Nullable String configuredPath, @Nullable String model,
                               @Nullable String extraArgs) throws Exception {
        return run(label, request, configuredPath, model, extraArgs, null);
    }

    /** Runs {@code codex exec} with explicit settings; also used by the "Other CLI" provider. */
    /** The {@code --output-last-message} file when it has text; codex can leave it empty, so stdout otherwise. */
    static @NotNull String lastMessageOrStdout(@NotNull Path lastMessage, @Nullable String stdout) throws java.io.IOException {
        if (Files.isRegularFile(lastMessage)) {
            String text = Files.readString(lastMessage, StandardCharsets.UTF_8);
            if (!text.isBlank()) return text;
        }
        return stdout == null ? "" : stdout;
    }

    static @NotNull String run(@NotNull String label, @NotNull AiRequest request,
                               @Nullable String configuredPath, @Nullable String model,
                               @Nullable String extraArgs, @Nullable String effort) throws Exception {
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        String path = CliPathResolver.resolveOrNull(configuredPath, CLI_NAME);
        if (path == null) {
            throw new IllegalStateException(
                label + " not found.\n" + AUTH_HINT
                    + "\n\nOr set its path in " + CommitMessageSettings.SETTINGS_PATH + ".");
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
            List<String> cmd = AiCliCommand.codex(path, model, extraArgs, lastMessage, request.lowLatency(), effort);
            String stdout;
            try {
                stdout = AiCliRunner.run(label, cmd,
                    AiCliCommand.combinePrompt(request.system(), request.user(), request),
                    workDir, env, request.timeout(), AUTH_HINT);
            } catch (RuntimeException e) {
                String msg = String.valueOf(e.getMessage());
                if (model != null && !model.isBlank() && msg.contains("model") && msg.contains("not supported")) {
                    throw new IllegalStateException(label + ": the model '" + model.trim() + "' is not available "
                        + "for this Codex login.\nClear the Model field (codex then uses its default) or pick "
                        + "another model in " + CommitMessageSettings.SETTINGS_PATH + ".", e);
                }
                throw e;
            }
            String result = lastMessageOrStdout(lastMessage, stdout);
            return AiCliRunner.finish(label, result, AUTH_HINT, request);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }
}
