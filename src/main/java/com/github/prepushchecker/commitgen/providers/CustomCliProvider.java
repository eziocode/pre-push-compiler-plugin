package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.AiRequest;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.cli.AiCli;
import com.github.prepushchecker.commitgen.cli.AiCliCommand;
import com.github.prepushchecker.commitgen.cli.AiCliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs any other local AI CLI (gemini, cursor-agent, copilot, opencode, a user-added CLI, …) with an
 * argument template.
 *
 * <p>A binary that has its own provider (codex, claude, llm, gh) is never run through a
 * template: it is handed to that provider with this card's path and model, so {@code codex}
 * always runs as {@code codex exec … -} and never as the interactive terminal UI.</p>
 *
 * <p>Template placeholders: {@code {model}}, {@code {model?…}} and {@code {prompt}}; see
 * {@link AiCliCommand#fromTemplate}. A blank template uses the preset for a known binary.
 * Without {@code {prompt}} the prompt is sent on stdin.</p>
 */
public final class CustomCliProvider implements CommitMessageProvider {

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String binary = binaryName(s);
        if (binary.isEmpty()) {
            throw new IllegalStateException("No local CLI selected.\nPick or add one in "
                + CommitMessageSettings.SETTINGS_PATH + " (Detect Local CLIs / Add custom CLI…).");
        }
        AiCli known = AiCli.byBinary(binary);
        String label = "Other CLI (" + binary + ")";

        if (known != null && !known.isCustom()) {
            return switch (known) {
                case CODEX -> CodexCliProvider.run(label, request, s.customCliPath, request.modelOr(s.customCliModel), "");
                case CLAUDE -> ClaudeCliProvider.run(label, request, s.customCliPath, request.modelOr(s.customCliModel), "");
                case LLM -> LlmCliProvider.run(label, request, s.customCliPath, request.modelOr(s.customCliModel));
                case GH -> GhCopilotProvider.run(request, s.customCliPath, request.modelOr(s.customCliModel));
                default -> throw new IllegalStateException("Unsupported CLI: " + binary);
            };
        }

        String hint = "Make sure '" + binary + "' is installed and signed in."
            + (known != null ? "\nInstall: " + known.installHint() : "")
            + "\nCheck the arguments template in " + CommitMessageSettings.SETTINGS_PATH + ".";

        long resolveStarted = System.nanoTime();
        String path = CliPathResolver.resolveOrNull(s.customCliPath, binary);
        if (path == null) {
            throw new IllegalStateException(
                label + " not found.\n" + hint);
        }

        String template = effectiveTemplate(s.customCliArgs, known);
        String prompt = AiCliCommand.combinePrompt(request.system(), request.user(), request);
        List<String> cmd = AiCliCommand.fromTemplate(path, template, request.modelOr(s.customCliModel), prompt);
        java.util.List<String> lean = AiCliCommand.leanTemplateFlags(known, known == null
            ? com.github.prepushchecker.commitgen.cli.CliCapabilities.UNKNOWN
            : com.github.prepushchecker.commitgen.cli.CliCapabilities.probe(path));
        if (!lean.isEmpty()) {
            cmd = new java.util.ArrayList<>(cmd);
            cmd.addAll(1, lean);
        }
        AiCliRunner.noteResolve(resolveStarted);
        String stdin = AiCliCommand.usesPromptArg(template) ? null : prompt;

        Path workDir = AiCliRunner.createWorkDir();
        try {
            String out;
            try {
                out = AiCliRunner.run(label, cmd, stdin, workDir, Map.of(), request.timeout(), hint);
            } catch (RuntimeException e) {
                if (stdin != null && needsTerminal(e.getMessage())) {
                    throw new IllegalStateException(label + " started its interactive terminal mode, "
                        + "which cannot run from the IDE.\nSet an arguments template that runs it once "
                        + "and exits, for example: -p {prompt}\n(" + CommitMessageSettings.SETTINGS_PATH
                        + " → Local CLI → Advanced)", e);
                }
                throw e;
            }
            return AiCliRunner.finish(label, out, hint, request);
        } finally {
            AiCliRunner.deleteQuietly(workDir);
        }
    }

    static @NotNull String binaryName(@NotNull CommitMessageSettings.State s) {
        return s.customCliBinary == null ? "" : s.customCliBinary.trim();
    }

    /** The saved template, or the preset of a known binary when the saved one is blank. */
    static @NotNull String effectiveTemplate(@Nullable String saved, @Nullable AiCli known) {
        if (saved != null && !saved.isBlank()) return saved;
        return known != null ? known.defaultArgsTemplate() : "";
    }

    /** {@code true} when a CLI error says it wanted a terminal on stdin. */
    static boolean needsTerminal(@Nullable String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("not a terminal") || m.contains("not a tty")
            || m.contains("inappropriate ioctl") || m.contains("raw mode is not supported");
    }

    @Override
    public @NotNull Id id() { return Id.CUSTOM_CLI; }
}
