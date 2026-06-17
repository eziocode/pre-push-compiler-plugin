package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Delegates to the OpenAI Codex CLI ({@code codex}) for generation.
 *
 * <p>The prompt is written to the process <b>stdin</b> with no positional
 * argument — the safest approach across all CLI versions.
 *
 * <p>Extra args default to <b>empty</b> (bare {@code codex} invocation).
 * If your version supports non-interactive flags (e.g.
 * {@code --approval-policy full-auto}), set them in Settings → Extra args.
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

        // Build command — prompt is piped via stdin, NOT passed as a positional argument.
        // Extra args default to empty so bare "codex" works on any CLI version.
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedPath);
        if (!s.codexExtraArgs.isBlank()) {
            for (String arg : s.codexExtraArgs.trim().split("\\s+")) {
                if (!arg.isBlank()) cmd.add(arg);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        CliPathResolver.injectAugmentedPath(pb);

        Process proc;
        try {
            proc = pb.start();
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                "Cannot start Codex CLI at '" + resolvedPath + "'.\n"
                    + "Install: npm install -g @openai/codex\n"
                    + "Auth:    codex auth   (signs in with your ChatGPT account)\n"
                    + "Or set OPENAI_API_KEY as an environment variable.", e);
        }

        // Write prompt to stdin then close so the CLI sees EOF
        String fullPrompt = systemPrompt + "\n\n" + userPrompt;
        try (Writer w = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(fullPrompt);
        }

        String stdout;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = r.lines().collect(Collectors.joining("\n"));
        }
        String stderr;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            stderr = r.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = proc.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("Codex CLI timed out after 90 seconds.");
        }
        int exit = proc.exitValue();
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
