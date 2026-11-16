package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Delegates to Simon Willison's <b>{@code llm}</b> CLI for generation.
 *
 * <p>{@code llm} supports 60+ model providers (OpenAI, Anthropic, Google,
 * Ollama, Mistral, and more) through its plugin system. A single install
 * can replace multiple individual API providers.
 *
 * <h3>Quick start</h3>
 * <pre>
 *   pip install llm                         # install
 *   llm keys set openai                     # configure a provider key
 *   llm install llm-anthropic               # add a provider plugin (optional)
 *   llm models                              # list available models
 * </pre>
 *
 * <h3>PATH detection</h3>
 * {@code llm} is resolved via {@link CliPathResolver} so pip user-installs
 * ({@code ~/.local/bin}) are found even when IntelliJ has a stripped PATH.
 */
public final class LlmCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "llm";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String resolvedPath = CliPathResolver.resolve(s.llmCliPath, CLI_NAME);
        if (resolvedPath == null) {
            throw new RuntimeException("Configured llm CLI path is not executable: " + s.llmCliPath);
        }
        String model        = s.llmModel.isBlank() ? null : s.llmModel.trim();

        // llm -s <system> [-m model] <prompt>
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedPath);
        cmd.add("-s");
        cmd.add(systemPrompt);
        if (model != null) {
            cmd.add("-m");
            cmd.add(model);
        }
        cmd.add(userPrompt);

        String hint = "Run 'llm models' to check available models and 'llm keys set <provider>' to configure auth."
            + "\nInstall: pip install llm — Docs: https://llm.datasette.io";
        Path workDir = AiCliRunner.createWorkDir();
        try {
            String out = AiCliRunner.run("llm CLI", cmd, null, workDir, Map.of(),
                AiCliRunner.DEFAULT_TIMEOUT, hint);
            return AiCliRunner.requireText("llm CLI", out, hint);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.LLM_CLI; }
}
