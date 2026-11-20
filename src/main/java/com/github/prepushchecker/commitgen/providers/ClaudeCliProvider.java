package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCliCommand;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import com.github.prepushchecker.commitgen.cli.CliCapabilities;
import com.github.prepushchecker.commitgen.cli.ClaudeStreamJson;
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

    static final String INLINE_DEFAULT_MODEL = AiCliCommand.CLAUDE_FAST_MODEL;

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        return run(LABEL, request, s.claudeCliPath, request.modelOr(s.claudeModel), s.claudeExtraArgs,
            AiCliCommand.effortFor(request, s.claudeEffort));
    }

    static @NotNull String run(@NotNull String label, @NotNull AiRequest request,
                               @Nullable String configuredPath, @Nullable String model,
                               @Nullable String extraArgs) throws Exception {
        return run(label, request, configuredPath, model, extraArgs, null);
    }

    /** Runs {@code claude -p} with explicit settings; also used by the "Other CLI" provider. */
    static @NotNull String run(@NotNull String label, @NotNull AiRequest request,
                               @Nullable String configuredPath, @Nullable String model,
                               @Nullable String extraArgs, @Nullable String effort) throws Exception {
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        long resolveStarted = System.nanoTime();
        String path = CliPathResolver.resolveOrNull(configuredPath, CLI_NAME);
        if (path == null) {
            throw new IllegalStateException(
                label + " not found.\n" + AUTH_HINT
                    + "\n\nOr set its path in " + CommitMessageSettings.SETTINGS_PATH + ".");
        }
        CliCapabilities caps = CliCapabilities.probe(path);

        Map<String, String> env = new HashMap<>();
        String fallbackKey = cfg.loadApiKey(Id.CLAUDE_CLI);
        if (fallbackKey != null && !fallbackKey.isBlank()) env.put("ANTHROPIC_API_KEY", fallbackKey);

        // No model chosen: haiku writes commit messages and completions in a fraction of the default's time.
        String effectiveModel = AiCliCommand.claudeModelFor(request, model);
        java.util.function.Consumer<String> partial = request.onPartial();
        boolean stream = partial != null && AiCliCommand.canStreamClaude(caps);
        boolean withSettings = NEEDS_USER_SETTINGS.contains(path);
        String prompt = AiCliCommand.combinePrompt(request.system(), request.user(), request);
        AiCliRunner.noteResolve(resolveStarted);
        try {
            return runOnce(label, request, path, effectiveModel, extraArgs, effort, caps, stream,
                withSettings, env, prompt, partial);
        } catch (com.intellij.openapi.progress.ProcessCanceledException e) {
            throw e;
        } catch (RuntimeException e) {
            if (withSettings || !caps.supports("--setting-sources") || !looksLikeSetupFailure(e.getMessage())) {
                throw e;
            }
            // Without the user's settings file claude may lack its login setup (apiKeyHelper, env,
            // proxy). Retry once with it and keep using it for this binary.
            NEEDS_USER_SETTINGS.add(path);
            return runOnce(label, request, path, effectiveModel, extraArgs, effort, caps, stream,
                true, env, prompt, partial);
        }
    }

    /** Login / endpoint failures that the user's settings file (apiKeyHelper, env, proxy) may fix. */
    static boolean looksLikeSetupFailure(@Nullable String message) {
        if (message == null || message.contains("timed out")) return false;
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("login") || m.contains("log in") || m.contains("logged in") || m.contains("auth")
            || m.contains("api key") || m.contains("apikey") || m.contains("401") || m.contains("403")
            || m.contains("credential") || m.contains("connect") || m.contains("base url") || m.contains("proxy");
    }

    /** Binaries that failed without the user's settings file ({@code --setting-sources ""}). */
    private static final java.util.Set<String> NEEDS_USER_SETTINGS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static @NotNull String runOnce(@NotNull String label, @NotNull AiRequest request, @NotNull String path,
                                           @Nullable String model, @Nullable String extraArgs,
                                           @Nullable String effort, @NotNull CliCapabilities caps,
                                           boolean stream, boolean withSettings,
                                           @NotNull Map<String, String> env, @NotNull String prompt,
                                           @Nullable java.util.function.Consumer<String> partial) throws Exception {
        List<String> cmd = AiCliCommand.claude(path, model, extraArgs, request.lowLatency(), effort,
            caps, stream, withSettings);
        java.util.function.Consumer<String> onLine = null;
        if (stream) {
            StringBuilder soFar = new StringBuilder();
            onLine = line -> {
                String delta = ClaudeStreamJson.delta(line);
                if (delta == null || delta.isEmpty()) return;
                soFar.append(delta);
                partial.accept(soFar.toString());
            };
        }
        Path workDir = AiCliRunner.createWorkDir();
        try {
            String out = AiCliRunner.run(label, cmd, prompt, workDir, env, request.timeout(), AUTH_HINT, onLine);
            if (stream) {
                String text = ClaudeStreamJson.finalText(out);
                if (text != null) out = text;
            }
            return AiCliRunner.finish(label, out, AUTH_HINT, request);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    @Override
    public @NotNull Id id() { return Id.CLAUDE_CLI; }
}
