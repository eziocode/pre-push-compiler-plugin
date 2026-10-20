package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.providers.GhCopilotProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the models a local AI CLI can use, for the Settings model dropdown.
 * Every method blocks — call off the EDT. Failures degrade to a static list.
 */
public final class AiCliModels {

    static final List<String> CLAUDE_STATIC = List.of(
        "sonnet", "opus", "haiku",
        "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-4-5");

    static final List<String> CODEX_STATIC = List.of(
        "gpt-5-codex", "gpt-5", "gpt-5-mini", "o4-mini", "o3");

    static final List<String> COPILOT_STATIC = List.of(
        "gpt-4o", "gpt-4.1", "gpt-5", "claude-sonnet-4", "gemini-2.5-pro");

    private static final Pattern JSON_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOML_MODEL =
        Pattern.compile("(?m)^\\s*model\\s*=\\s*[\"']([^\"']+)[\"']");

    private AiCliModels() {}

    /**
     * Returns the models for {@code cli}. {@code customListArgs} is only used for
     * custom CLIs: arguments that make the CLI print one model per line.
     */
    public static @NotNull List<String> list(@NotNull AiCli cli, @Nullable String path,
                                             @Nullable String customListArgs) {
        try {
            return switch (cli) {
                case CLAUDE -> claudeModels();
                case CODEX  -> codexModels();
                case GH     -> copilotModels(path);
                case LLM    -> path == null ? List.of() : parseLlmModels(runForOutput(path, List.of("models")));
                default     -> customModels(path, customListArgs);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Models offered before the user clicks refresh — no I/O. */
    public static @NotNull List<String> staticModels(@NotNull AiCli cli) {
        return switch (cli) {
            case CLAUDE -> CLAUDE_STATIC;
            case CODEX  -> CODEX_STATIC;
            case GH     -> COPILOT_STATIC;
            default     -> List.of();
        };
    }

    // ── Claude ────────────────────────────────────────────────────────────────

    private static @NotNull List<String> claudeModels() throws Exception {
        Set<String> out = new LinkedHashSet<>(CLAUDE_STATIC);
        String key = CliPathResolver.resolveEnvVar("ANTHROPIC_API_KEY");
        if (key != null) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/models?limit=100"))
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) out.addAll(parseJsonIds(resp.body()));
        }
        return List.copyOf(out);
    }

    // ── Codex ─────────────────────────────────────────────────────────────────

    private static @NotNull List<String> codexModels() {
        Set<String> out = new LinkedHashSet<>();
        Path config = Path.of(System.getProperty("user.home"), ".codex", "config.toml");
        try {
            if (Files.isRegularFile(config)) {
                out.addAll(parseTomlModels(Files.readString(config, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {}
        out.addAll(CODEX_STATIC);
        return List.copyOf(out);
    }

    // ── GitHub Copilot (via gh token) ─────────────────────────────────────────

    private static @NotNull List<String> copilotModels(@Nullable String ghPath) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        String token = ghPath == null ? null : GhCopilotProvider.getGhToken(ghPath);
        if (token != null) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.githubcopilot.com/models"))
                .header("Authorization", "Bearer " + token)
                .header("Copilot-Integration-Id", "vscode-chat")
                .header("Editor-Version", "vscode/1.85.0")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) out.addAll(parseJsonIds(resp.body()));
        }
        out.addAll(COPILOT_STATIC);
        return List.copyOf(out);
    }

    // ── Custom ────────────────────────────────────────────────────────────────

    private static @NotNull List<String> customModels(@Nullable String path, @Nullable String listArgs)
            throws Exception {
        if (path == null || listArgs == null || listArgs.isBlank()) return List.of();
        return parseLines(runForOutput(path, AiCliCommand.tokenize(listArgs)));
    }

    // ── Parsers (package-private for tests) ───────────────────────────────────

    static @NotNull List<String> parseJsonIds(@NotNull String json) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = JSON_ID.matcher(json);
        while (m.find()) out.add(m.group(1));
        return List.copyOf(out);
    }

    static @NotNull List<String> parseTomlModels(@NotNull String toml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOML_MODEL.matcher(toml);
        while (m.find()) out.add(m.group(1));
        return List.copyOf(out);
    }

    /** Parses {@code llm models} lines like {@code OpenAI Chat: gpt-4o (aliases: 4o)}. */
    static @NotNull List<String> parseLlmModels(@NotNull String output) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            int colon = line.indexOf(": ");
            if (colon < 0) continue;
            String rest = line.substring(colon + 2).trim();
            int paren = rest.indexOf(" (");
            if (paren >= 0) rest = rest.substring(0, paren);
            if (!rest.isBlank() && !rest.contains(" ")) out.add(rest);
        }
        return List.copyOf(out);
    }

    /** One model per line; takes the first whitespace-separated token, skips blanks and comments. */
    static @NotNull List<String> parseLines(@NotNull String output) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            out.add(t.split("\\s+", 2)[0]);
        }
        return List.copyOf(out);
    }

    private static @NotNull String runForOutput(@NotNull String path, @NotNull List<String> args)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(path);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        CliPathResolver.injectAugmentedPath(pb);
        ProcessExecution.Result r = ProcessExecution.run(pb, Duration.ofSeconds(20));
        return r.isSuccess() ? r.stdout() : "";
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }
}
