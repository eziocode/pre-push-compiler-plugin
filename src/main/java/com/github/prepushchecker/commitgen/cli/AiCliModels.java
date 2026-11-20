package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.providers.GhCopilotProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Comparator;
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
        "haiku", "sonnet", "opus",
        "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-4-5");

    /**
     * Visible entries of {@code codex debug models} for a ChatGPT login. The old ids
     * gpt-5-codex and o3 were dropped: codex answers "not supported when using Codex
     * with a ChatGPT account" for them.
     */
    static final List<String> CODEX_STATIC = List.of(
        "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
        "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna");

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
    public static @NotNull List<String> list(@Nullable AiCli cli, @Nullable String path,
                                             @Nullable String customListArgs) {
        try {
            if (cli == null) return customModels(path, customListArgs); // a user-added CLI
            return switch (cli) {
                case CLAUDE -> claudeModels();
                case CODEX  -> codexModels(path);
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
    public static @NotNull List<String> staticModels(@Nullable AiCli cli) {
        if (cli == null) return List.of();
        return switch (cli) {
            case CLAUDE -> CLAUDE_STATIC;
            case CODEX  -> CODEX_STATIC;
            case GH     -> COPILOT_STATIC;
            default     -> List.of();
        };
    }

    // ── Claude ────────────────────────────────────────────────────────────────

    private static @NotNull List<String> claudeModels() throws Exception {
        // The claude CLI has no model list; the aliases always resolve. When an API key is
        // available the account's own list replaces the static full ids.
        Set<String> out = new LinkedHashSet<>(List.of("sonnet", "opus", "haiku"));
        String key = CliPathResolver.resolveEnvVar("ANTHROPIC_API_KEY");
        if (key != null) {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/models?limit=100"))
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                List<String> ids = parseJsonIds(resp.body());
                if (!ids.isEmpty()) {
                    out.addAll(ids);
                    return List.copyOf(out);
                }
            }
        }
        return List.copyOf(CLAUDE_STATIC);
    }

    // ── Codex ─────────────────────────────────────────────────────────────────

    private static @NotNull List<String> codexModels(@Nullable String path) throws Exception {
        if (path != null) {
            // The catalogue is what this login can actually use (visibility, API support).
            List<String> catalogue = parseCodexCatalogue(runForOutput(path, List.of("debug", "models")));
            if (!catalogue.isEmpty()) return catalogue;
        }
        return CODEX_STATIC;
    }

    // ── GitHub Copilot (via gh token) ─────────────────────────────────────────

    private static @NotNull List<String> copilotModels(@Nullable String ghPath) throws Exception {
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
            if (resp.statusCode() == 200) {
                List<String> usable = parseCopilotModels(resp.body());
                if (!usable.isEmpty()) return usable;
            }
        }
        return COPILOT_STATIC;
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

    /**
     * Parses {@code codex debug models}: keeps entries listed in the picker (visibility
     * "list"), usable through the login ({@code supported_in_api} not false) and not
     * deprecated, ordered by the catalogue's priority (lower first).
     */
    static @NotNull List<String> parseCodexCatalogue(@NotNull String json) {
        JsonObject root = parseObject(json);
        if (root == null || !root.has("models") || !root.get("models").isJsonArray()) return List.of();
        List<JsonObject> kept = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("models")) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            if (str(m, "slug") == null) continue;
            String visibility = str(m, "visibility");
            if (visibility != null && !visibility.equals("list")) continue;
            if (m.has("supported_in_api") && m.get("supported_in_api").isJsonPrimitive()
                    && !m.get("supported_in_api").getAsBoolean()) continue;
            if (m.has("deprecation") && !m.get("deprecation").isJsonNull()) continue;
            kept.add(m);
        }
        kept.sort(Comparator.comparingInt(m ->
            m.has("priority") && m.get("priority").isJsonPrimitive() ? m.get("priority").getAsInt() : Integer.MAX_VALUE));
        Set<String> out = new LinkedHashSet<>();
        for (JsonObject m : kept) out.add(str(m, "slug"));
        return List.copyOf(out);
    }

    /**
     * Parses the Copilot {@code /models} response: chat models the account's policy has not
     * disabled and that the model picker offers.
     */
    static @NotNull List<String> parseCopilotModels(@NotNull String json) {
        JsonObject root = parseObject(json);
        if (root == null || !root.has("data") || !root.get("data").isJsonArray()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement e : root.getAsJsonArray("data")) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            String id = str(m, "id");
            if (id == null) continue;
            if (m.has("model_picker_enabled") && m.get("model_picker_enabled").isJsonPrimitive()
                    && !m.get("model_picker_enabled").getAsBoolean()) continue;
            if (m.has("policy") && m.get("policy").isJsonObject()) {
                String state = str(m.getAsJsonObject("policy"), "state");
                if (state != null && !state.equals("enabled")) continue;
            }
            if (m.has("capabilities") && m.get("capabilities").isJsonObject()) {
                String type = str(m.getAsJsonObject("capabilities"), "type");
                if (type != null && !type.equals("chat")) continue;
            }
            out.add(id);
        }
        return List.copyOf(out);
    }

    private static @Nullable JsonObject parseObject(@NotNull String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static @Nullable String str(@NotNull JsonObject o, @NotNull String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** Parses {@code llm models} lines like {@code OpenAI Chat: gpt-4o (aliases: 4o)}. */
    static @NotNull List<String> parseLlmModels(@NotNull String output) {
        Set<String> out = new LinkedHashSet<>();
        String def = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith("Default:")) {
                String d = line.substring("Default:".length()).trim();
                if (!d.isEmpty() && !d.contains(" ")) def = d;
                continue;
            }
            int colon = line.indexOf(": ");
            if (colon < 0) continue;
            String rest = line.substring(colon + 2).trim();
            int paren = rest.indexOf(" (");
            if (paren >= 0) rest = rest.substring(0, paren);
            if (!rest.isBlank() && !rest.contains(" ")) out.add(rest);
        }
        if (def != null && out.contains(def)) {
            List<String> ordered = new ArrayList<>();
            ordered.add(def);
            for (String m : out) if (!m.equals(def)) ordered.add(m);
            return List.copyOf(ordered);
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
