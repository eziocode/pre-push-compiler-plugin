package com.github.prepushchecker.commitgen.providers;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Generates commit messages via the OpenAI Chat Completions API using the
 * <b>ChatGPT OAuth token</b> that the Codex desktop app stores in
 * {@code ~/.codex/auth.json} — no API key required, no subprocess, no TTY.
 *
 * <h3>Authentication</h3>
 * Open the <b>Codex</b> desktop app and sign in with your ChatGPT account.
 * The app writes {@code ~/.codex/auth.json} with an OAuth access token that
 * this provider reads at generation time.
 */
public final class CodexCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "codex";
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String AUTH_JSON = System.getProperty("user.home") + "/.codex/auth.json";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        String token = resolveToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "No ChatGPT OAuth token found in ~/.codex/auth.json.\n\n"
                    + "Open the Codex desktop app and sign in with your ChatGPT account.\n"
                    + "The plugin will then use that session automatically — no API key needed.");
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
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            // Token may have expired — clear hint
            throw new RuntimeException(
                "ChatGPT OAuth token expired.\n"
                    + "Open the Codex app, sign out and sign back in to refresh it.");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "OpenAI API error " + response.statusCode() + ": " + response.body());
        }
        String content = JsonUtil.extractString(response.body(), "content");
        if (content == null) {
            throw new RuntimeException("Unexpected response: " + response.body());
        }
        return content.trim();
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    /**
     * Reads the ChatGPT OAuth access token from {@code ~/.codex/auth.json}.
     * Returns {@code null} if the file is absent or the token field is blank.
     */
    @Nullable
    public static String resolveToken() {
        try {
            Path authFile = Path.of(AUTH_JSON);
            if (!Files.isRegularFile(authFile)) return null;
            String json = Files.readString(authFile, StandardCharsets.UTF_8);
            // Try tokens.access_token first
            String token = JsonUtil.extractString(json, "access_token");
            if (token != null && !token.isBlank()) return token;
            // Fall back to top-level OPENAI_API_KEY
            return JsonUtil.extractString(json, "OPENAI_API_KEY");
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns {@code true} when a valid auth file exists. */
    public static boolean isAuthenticated() {
        String t = resolveToken();
        return t != null && !t.isBlank();
    }

    /** Returns the account_id from auth.json for display, or {@code null}. */
    @Nullable
    public static String getAccountId() {
        try {
            Path authFile = Path.of(AUTH_JSON);
            if (!Files.isRegularFile(authFile)) return null;
            String json = Files.readString(authFile, StandardCharsets.UTF_8);
            return JsonUtil.extractString(json, "account_id");
        } catch (Exception e) {
            return null;
        }
    }
}
