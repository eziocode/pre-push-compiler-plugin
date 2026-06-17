package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        CliPathResolver.injectAugmentedPath(pb);   // ensures python3 etc. are on PATH

        ProcessExecution.Result processResult;
        try {
            processResult = ProcessExecution.run(pb, Duration.ofSeconds(120));
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                "Cannot start llm CLI at '" + resolvedPath + "'.\n"
                    + "Install: pip install llm\n"
                    + "Docs:    https://llm.datasette.io", e);
        }

        if (processResult.timedOut()) {
            throw new RuntimeException("llm CLI timed out after 120 seconds.");
        }
        int exit = processResult.exitCode();
        String stdout = processResult.stdout();
        String stderr = processResult.stderr();
        if (exit != 0) {
            String detail = stderr.isBlank() ? stdout : stderr;
            throw new RuntimeException(
                "llm CLI exited with code " + exit
                    + (detail.isBlank() ? "" : ": " + detail.trim())
                    + "\nRun 'llm models' to check available models and 'llm keys set <provider>' to configure auth.");
        }
        String result = stdout.trim();
        if (result.isBlank()) {
            throw new RuntimeException(
                "llm CLI returned an empty response. "
                    + "Run 'llm keys set openai' (or your provider) to set up credentials.");
        }
        return result;
    }

    @Override
    public @NotNull Id id() { return Id.LLM_CLI; }
}
