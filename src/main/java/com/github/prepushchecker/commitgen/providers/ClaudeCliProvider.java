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
 * Delegates to Anthropic's <b>{@code claude}</b> CLI for generation.
 *
 * <p>The prompt is written to the process <b>stdin</b> — this avoids
 * argument-parser issues and is consistent with how {@link GhCopilotProvider}
 * and {@link CodexCliProvider} are integrated.
 *
 * <h3>Quick start</h3>
 * <pre>
 *   # Install
 *   npm install -g @anthropic-ai/claude-code    # or: brew install anthropic
 *
 *   # Authenticate (API key stored securely by the CLI)
 *   claude                                       # opens interactive auth on first run
 *   # or set ANTHROPIC_API_KEY in your environment
 * </pre>
 *
 * <h3>Non-interactive flags</h3>
 * Default extra args: {@code --print} — outputs the response to stdout and
 * exits immediately. Configure via Settings → Extra args if your version
 * uses a different flag (e.g. {@code -p}).
 */
public final class ClaudeCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "claude";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String resolvedPath = CliPathResolver.resolve(s.claudeCliPath, CLI_NAME);
        if (resolvedPath == null) {
            throw new RuntimeException("Configured Claude CLI path is not executable: " + s.claudeCliPath);
        }

        // Build command WITHOUT a positional prompt — prompt is piped via stdin.
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedPath);

        // Default: --print (non-interactive, writes response to stdout and exits)
        String extraArgs = s.claudeExtraArgs.isBlank()
            ? "--print"
            : s.claudeExtraArgs.trim();
        for (String arg : extraArgs.split("\\s+")) {
            if (!arg.isBlank()) cmd.add(arg);
        }

        // Optional model override
        if (!s.claudeModel.isBlank()) {
            cmd.add("--model");
            cmd.add(s.claudeModel.trim());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        CliPathResolver.injectAugmentedPath(pb);

        String fullPrompt = systemPrompt + "\n\n" + userPrompt;
        ProcessExecution.Result processResult;
        try {
            processResult = ProcessExecution.run(pb, Duration.ofSeconds(120), fullPrompt);
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                "Cannot start Claude CLI at '" + resolvedPath + "'.\n"
                    + "Install: npm install -g @anthropic-ai/claude-code\n"
                    + "Auth:    run 'claude' once in a terminal, or set ANTHROPIC_API_KEY.", e);
        }

        if (processResult.timedOut()) {
            throw new RuntimeException("Claude CLI timed out after 120 seconds.");
        }
        int exit = processResult.exitCode();
        String stdout = processResult.stdout();
        String stderr = processResult.stderr();
        if (exit != 0) {
            String detail = stderr.isBlank() ? stdout : stderr;
            String hint = (detail.contains("auth") || detail.contains("API key") || detail.contains("401"))
                ? "\nRun 'claude' in a terminal to authenticate, or set ANTHROPIC_API_KEY." : "";
            throw new RuntimeException("Claude CLI exited with code " + exit
                + (detail.isBlank() ? "" : ": " + detail.trim()) + hint);
        }
        String result = stdout.trim();
        if (result.isBlank()) {
            throw new RuntimeException(
                "Claude CLI returned an empty response. "
                    + "Run 'claude' in a terminal to authenticate, or set ANTHROPIC_API_KEY.");
        }
        return result;
    }

    @Override
    public @NotNull Id id() { return Id.CLAUDE_CLI; }
}
