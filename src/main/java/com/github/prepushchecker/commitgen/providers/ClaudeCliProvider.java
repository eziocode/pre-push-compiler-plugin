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
 * Generates commit messages via the Anthropic Messages API using credentials
 * stored by the <b>Claude CLI</b> ({@code claude}).
 *
 * <h3>How it works — same pattern as GH Copilot</h3>
 * <ol>
 *   <li>Reads {@code ANTHROPIC_API_KEY} from the user's shell environment
 *       (sources rc files so variables set in {@code .zshrc} / {@code .bashrc}
 *       are visible even from a GUI-launched IntelliJ).</li>
 *   <li>Falls back to the PasswordSafe-stored key if the env var is absent.</li>
 *   <li>Calls {@code api.anthropic.com/v1/messages} directly — no subprocess,
 *       no TTY requirement.</li>
 * </ol>
 *
 * <h3>Authentication</h3>
 * <ul>
 *   <li>Run the Claude CLI once in a terminal to authenticate — it sets
 *       {@code ANTHROPIC_API_KEY} in your shell environment.</li>
 *   <li>Or add {@code export ANTHROPIC_API_KEY=sk-ant-...} to your
 *       {@code .zshrc} / {@code .bashrc}.</li>
 *   <li>Or enter the key in
 *       Settings → Tools → Pre-Push Checker — AI Commit Message Generator.</li>
 * </ul>
 */
public final class ClaudeCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "claude";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "No Anthropic API key found for Claude CLI.\n\n"
                    + "Options:\n"
                    + "  1. Run 'claude' in a terminal to authenticate\n"
                    + "  2. Add 'export ANTHROPIC_API_KEY=sk-ant-...' to your .zshrc / .bashrc\n"
                    + "  3. Enter the key in Settings → Tools → "
                    + "Pre-Push Checker — AI Commit Message Generator");
        }

        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String model = s.claudeModel.isBlank() ? "claude-3-5-sonnet-20241022" : s.claudeModel;

        String body = "{"
            + "\"model\":" + JsonUtil.quoted(model) + ","
            + "\"max_tokens\":300,"
            + "\"system\":" + JsonUtil.quoted(systemPrompt) + ","
            + "\"messages\":["
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new RuntimeException(
                "Anthropic API key is invalid or expired.\n"
                    + "Run 'claude' to refresh credentials, or update ANTHROPIC_API_KEY.");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Anthropic API error " + response.statusCode() + ": " + response.body());
        }
        String text = JsonUtil.extractString(response.body(), "text");
        if (text == null) {
            throw new RuntimeException("Unexpected Anthropic response: " + response.body());
        }
        return text.trim();
    }

    @Override
    public @NotNull Id id() { return Id.CLAUDE_CLI; }

    // ── Credential resolution (GH-Copilot-style) ─────────────────────────────

    /**
     * Resolves the Anthropic API key in priority order:
     * <ol>
     *   <li>Shell environment ({@code ANTHROPIC_API_KEY}).</li>
     *   <li>PasswordSafe stored key (entered manually in Settings).</li>
     * </ol>
     */
    @Nullable
    static String resolveApiKey() {
        String fromEnv = CliPathResolver.resolveEnvVar("ANTHROPIC_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return CommitMessageSettings.getInstance().loadApiKey(Id.CLAUDE_CLI);
    }
}
