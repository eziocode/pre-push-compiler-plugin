package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Delegates to the OpenAI Codex CLI ({@code codex}) for generation.
 * <p>
 * Auth is handled externally — run {@code codex auth} once in a terminal to
 * sign in with your ChatGPT account. The plugin then shells out to the CLI.
 * Alternatively, if {@code OPENAI_API_KEY} is set in the environment, the CLI
 * uses that automatically.
 * <p>
 * The CLI path defaults to {@code codex} (resolved from PATH) and is
 * configurable via Settings → Tools → AI Commit Message Generator.
 */
public final class CodexCliProvider implements CommitMessageProvider {

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String cliPath = s.codexCliPath.isBlank() ? "codex" : s.codexCliPath;

        // Build the full prompt to pass to the CLI
        String fullPrompt = systemPrompt + "\n\n" + userPrompt;

        // codex -q <prompt>  — quiet / non-interactive mode
        List<String> cmd = new ArrayList<>();
        cmd.add(cliPath);
        cmd.add("-q");
        cmd.add(fullPrompt);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                "Cannot start Codex CLI at path '" + cliPath
                    + "'. Install it with: npm install -g @openai/codex\n"
                    + "Then authenticate with: codex auth", e);
        }

        // Read stdout
        String stdout;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = reader.lines().collect(Collectors.joining("\n"));
        }
        // Read stderr for error messages
        String stderr;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            stderr = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = proc.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Codex CLI timed out after 90 seconds.");
        }
        int exitCode = proc.exitValue();
        if (exitCode != 0) {
            String detail = stderr.isBlank() ? stdout : stderr;
            throw new RuntimeException(
                "Codex CLI exited with code " + exitCode
                    + (detail.isBlank() ? "" : ": " + detail.trim()));
        }
        String result = stdout.trim();
        if (result.isBlank()) {
            throw new RuntimeException(
                "Codex CLI returned an empty response. "
                    + "Run 'codex auth' to authenticate if you have not already done so.");
        }
        return result;
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }
}
