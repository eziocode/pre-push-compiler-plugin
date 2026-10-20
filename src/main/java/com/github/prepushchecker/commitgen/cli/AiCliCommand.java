package com.github.prepushchecker.commitgen.cli;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds argument vectors for the local AI CLIs. Pure functions — no I/O. */
public final class AiCliCommand {

    /** Matches {@code {model?...}} conditional segments (no nested braces other than {model}). */
    private static final Pattern MODEL_CONDITIONAL =
        Pattern.compile("\\{model\\?((?:[^{}]|\\{model\\})*)\\}");

    private AiCliCommand() {}

    /** {@code claude -p --output-format text [--model M] [extra…]}; prompt goes on stdin. */
    public static @NotNull List<String> claude(@NotNull String path, @Nullable String model,
                                               @Nullable String extraArgs) {
        List<String> cmd = new ArrayList<>(List.of(path, "-p", "--output-format", "text"));
        if (!isBlank(model)) {
            cmd.add("--model");
            cmd.add(model.trim());
        }
        cmd.addAll(tokenize(extraArgs));
        return cmd;
    }

    /**
     * {@code codex exec --skip-git-repo-check --sandbox read-only --color never
     * [-m M] --output-last-message <file> [extra…] -}; prompt goes on stdin.
     */
    public static @NotNull List<String> codex(@NotNull String path, @Nullable String model,
                                              @Nullable String extraArgs,
                                              @NotNull Path lastMessageFile) {
        List<String> cmd = new ArrayList<>(List.of(
            path, "exec", "--skip-git-repo-check", "--sandbox", "read-only", "--color", "never"));
        if (!isBlank(model)) {
            cmd.add("-m");
            cmd.add(model.trim());
        }
        cmd.add("--output-last-message");
        cmd.add(lastMessageFile.toString());
        cmd.addAll(tokenize(extraArgs));
        cmd.add("-");
        return cmd;
    }

    /**
     * Expands a user args template for a generic CLI.
     * <ul>
     *   <li>{@code {model?...}} — kept (with {@code {model}} substituted) only when a model is set.</li>
     *   <li>{@code {model}} — the model name as a single argument.</li>
     *   <li>{@code {prompt}} — the full prompt as a single argument. When absent the
     *       caller sends the prompt on stdin.</li>
     * </ul>
     */
    public static @NotNull List<String> fromTemplate(@NotNull String path, @Nullable String template,
                                                     @Nullable String model, @NotNull String prompt) {
        String expanded = expandModelConditionals(template == null ? "" : template, model);
        List<String> cmd = new ArrayList<>();
        cmd.add(path);
        for (String token : tokenize(expanded)) {
            String value = token
                .replace("{model}", model == null ? "" : model.trim())
                .replace("{prompt}", prompt);
            cmd.add(value);
        }
        return cmd;
    }

    /** {@code true} when {@code template} passes the prompt as an argument. */
    public static boolean usesPromptArg(@Nullable String template) {
        return template != null && template.contains("{prompt}");
    }

    static @NotNull String expandModelConditionals(@NotNull String template, @Nullable String model) {
        Matcher m = MODEL_CONDITIONAL.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(isBlank(model) ? "" : m.group(1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Splits on whitespace, honouring single and double quotes. */
    public static @NotNull List<String> tokenize(@Nullable String args) {
        List<String> out = new ArrayList<>();
        if (args == null || args.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }

    /** Joins system and user prompts into one block for CLIs that take a single prompt. */
    public static @NotNull String combinePrompt(@NotNull String systemPrompt, @NotNull String userPrompt) {
        return systemPrompt.strip()
            + "\n\nReply with ONLY the commit message text — no preamble, no code fences, "
            + "do not run tools or edit files.\n\n---\n\n"
            + userPrompt.strip() + "\n";
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
