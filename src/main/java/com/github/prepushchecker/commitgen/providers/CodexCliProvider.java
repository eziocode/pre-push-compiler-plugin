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
 * Delegates to the OpenAI Codex CLI ({@code codex}) for generation.
 *
 * <p>The prompt is written to the process <b>stdin</b> (not passed as a
 * positional argument) — this avoids argument-parser errors with multi-line
 * prompts and mirrors how {@link GhCopilotProvider} uses the {@code gh} CLI.
 *
 * <h3>Authentication</h3>
 * <ul>
 *   <li>Run {@code codex auth} once in a terminal to sign in with your
 *       <b>ChatGPT account</b> (OAuth, no API key needed).</li>
 *   <li>Or set the {@code OPENAI_API_KEY} environment variable.</li>
 * </ul>
 */
public final class CodexCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "codex";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String resolvedPath = CliPathResolver.resolve(s.codexCliPath, CLI_NAME);
        if (resolvedPath == null) {
            throw new RuntimeException("Configured Codex CLI path is not executable: " + s.codexCliPath);
        }

        // Build command WITHOUT a positional prompt — prompt is piped via stdin.
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedPath);
        String extraArgs = s.codexExtraArgs.isBlank()
            ? "--approval-policy full-auto"
            : s.codexExtraArgs.trim();
        for (String arg : extraArgs.split("\\s+")) {
            if (!arg.isBlank()) cmd.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        CliPathResolver.injectAugmentedPath(pb);

        String fullPrompt = systemPrompt + "\n\n" + userPrompt;
        ProcessExecution.Result processResult;
        try {
            processResult = ProcessExecution.run(pb, Duration.ofSeconds(90), fullPrompt);
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                "Cannot start Codex CLI at '" + resolvedPath + "'.\n"
                    + "Install: npm install -g @openai/codex\n"
                    + "Auth:    codex auth   (signs in with your ChatGPT account)\n"
                    + "Or set OPENAI_API_KEY as an environment variable.", e);
        }

        if (processResult.timedOut()) {
            throw new RuntimeException("Codex CLI timed out after 90 seconds.");
        }
        int exit = processResult.exitCode();
        String stdout = processResult.stdout();
        String stderr = processResult.stderr();
        if (exit != 0) {
            String detail = stderr.isBlank() ? stdout : stderr;
            String hint = (detail.contains("auth") || detail.contains("login") || detail.contains("401"))
                ? "\nRun 'codex auth' in a terminal to sign in with your ChatGPT account." : "";
            throw new RuntimeException("Codex CLI exited with code " + exit
                + (detail.isBlank() ? "" : ": " + detail.trim()) + hint);
        }
        String result = stdout.trim();
        if (result.isBlank()) {
            throw new RuntimeException(
                "Codex CLI returned an empty response. Run 'codex auth' if not authenticated.");
        }
        return result;
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }
}
