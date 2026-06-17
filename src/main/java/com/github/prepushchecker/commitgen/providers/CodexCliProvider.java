package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Generates commit messages via the OpenAI Chat Completions API using
 * credentials stored by the <b>Codex CLI</b>.
 *
 * <h3>How it works — same pattern as GH Copilot</h3>
 * <ol>
 *   <li>Reads {@code OPENAI_API_KEY} from the user's shell environment
 *       (sources rc files so that variables set in {@code .zshrc} /
 *       {@code .bashrc} are visible even from a GUI-launched IntelliJ).</li>
 *   <li>Falls back to the PasswordSafe-stored key if the env var is absent.</li>
 *   <li>Calls {@code api.openai.com/v1/chat/completions} directly — no
 *       subprocess, no TTY requirement.</li>
 * </ol>
 *
 * <h3>Authentication</h3>
 * <ul>
 *   <li>Run {@code codex auth} once in a terminal — this stores your
 *       ChatGPT / OpenAI session and sets {@code OPENAI_API_KEY} in
 *       your shell environment.</li>
 *   <li>Or add {@code export OPENAI_API_KEY=sk-...} to your
 *       {@code .zshrc} / {@code .bashrc}.</li>
 *   <li>Or enter the key directly in
 *       Settings → Tools → Pre-Push Checker — AI Commit Message Generator.</li>
 * </ul>
 */
public final class CodexCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "codex";
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "No OpenAI API key found for Codex.\n\n"
                    + "Options:\n"
                    + "  1. Run 'codex auth' in a terminal (ChatGPT account OAuth)\n"
                    + "  2. Add 'export OPENAI_API_KEY=sk-...' to your .zshrc / .bashrc\n"
                    + "  3. Enter the key in Settings → Tools → "
                    + "Pre-Push Checker — AI Commit Message Generator");
        }

        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String model = s.openAiModel.isBlank() ? "gpt-4o" : s.openAiModel;

        String body = "{"
            + "\"model\":" + JsonUtil.quoted(model) + ","
            + "\"max_tokens\":300,"
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":" + JsonUtil.quoted(systemPrompt) + "},"
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new RuntimeException(
                "OpenAI API key is invalid or expired.\n"
                    + "Run 'codex auth' to refresh your credentials.");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "OpenAI API error " + response.statusCode() + ": " + response.body());
        }
        String content = JsonUtil.extractString(response.body(), "content");
        if (content == null) {
            throw new RuntimeException("Unexpected OpenAI response: " + response.body());
        }
        return content.trim();
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }

    // ── Credential resolution (GH-Copilot-style) ─────────────────────────────

    /**
     * Resolves the OpenAI API key in priority order:
     * <ol>
     *   <li>Shell environment ({@code OPENAI_API_KEY}) — picks up keys set
     *       in rc files or by {@code codex auth}.</li>
     *   <li>PasswordSafe stored key (entered manually in Settings).</li>
     * </ol>
     */
    @Nullable
    static String resolveApiKey() {
        // 1. Shell env var (covers codex auth + manual .zshrc exports)
        String fromEnv = CliPathResolver.resolveEnvVar("OPENAI_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;

        // 2. PasswordSafe (user entered key in Settings under CODEX_CLI provider)
        return CommitMessageSettings.getInstance().loadApiKey(Id.CODEX_CLI);
    }
}
