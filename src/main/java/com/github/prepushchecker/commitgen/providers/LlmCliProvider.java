package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Delegates to Simon Willison's <b>{@code llm}</b> CLI: {@code llm -s <system> [-m model] <prompt>}.
 *
 * <p>{@code llm} reaches many model providers through its plugins. It is resolved via
 * {@link CliPathResolver} so pip user installs ({@code ~/.local/bin}) are found even when
 * IntelliJ has a stripped PATH.</p>
 */
public final class LlmCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "llm";
    private static final String LABEL = "llm CLI";
    private static final String HINT =
        "Run 'llm models' to check available models and 'llm keys set <provider>' to configure auth."
            + "\nInstall: pip install llm — Docs: https://llm.datasette.io";

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        return run(LABEL, request, s.llmCliPath, request.modelOr(s.llmModel));
    }

    /** Runs {@code llm} with explicit settings; also used by the "Other CLI" provider. */
    static @NotNull String run(@NotNull String label, @NotNull AiRequest request,
                               @Nullable String configuredPath, @Nullable String model) throws Exception {
        String path = CliPathResolver.resolveOrNull(configuredPath, CLI_NAME);
        if (path == null) {
            throw new IllegalStateException(
                label + " not found.\n" + HINT
                    + "\n\nOr set its path in " + CommitMessageSettings.SETTINGS_PATH + ".");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(path);
        cmd.add("-s");
        cmd.add(request.structured()
            ? request.system().strip() + "\n\nReply with ONLY the JSON object described above."
            : request.system());
        if (model != null && !model.isBlank()) {
            cmd.add("-m");
            cmd.add(model.trim());
        }
        cmd.add(request.user());

        Path workDir = AiCliRunner.createWorkDir();
        try {
            String out = AiCliRunner.run(label, cmd, null, workDir, Map.of(), request.timeout(), HINT);
            return AiCliRunner.finish(label, out, HINT, request.structured());
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.LLM_CLI; }
}
