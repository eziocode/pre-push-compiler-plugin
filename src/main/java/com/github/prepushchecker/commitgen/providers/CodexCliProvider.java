package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.ChatGPTOAuthFlow;
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
 * <b>ChatGPT OAuth token</b> obtained by the browser Device Code login flow
 * or the Codex desktop app's {@code ~/.codex/auth.json}.
 *
 * <h3>Authentication priority</h3>
 * <ol>
 *   <li>Token from browser sign-in (PasswordSafe, via {@link ChatGPTOAuthFlow}).</li>
 *   <li>Token from the Codex desktop app ({@code ~/.codex/auth.json}) as fallback.</li>
 *   <li>Manual API key entered in Settings (PasswordSafe).</li>
 * </ol>
 * If the token is expired, one refresh attempt is made automatically.
 */
public final class CodexCliProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "codex";
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        String token = ChatGPTOAuthFlow.getAccessToken();
        if (token == null || token.isBlank()) {
            // Last resort: manual API key from Settings
            token = CommitMessageSettings.getInstance().loadApiKey(Id.CODEX_CLI);
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "No ChatGPT account credentials found.\n\n"
                    + "Click \"Sign in with ChatGPT\" in:\n"
                    + "Settings → Tools → Pre-Push Checker — AI Commit Message Generator\n\n"
                    + "Or open the Codex desktop app and sign in with your ChatGPT account.");
        }

        String result = callApi(token, systemPrompt, userPrompt);
        if (result == null) {
            // Token may have expired — try to refresh once
            String newToken = ChatGPTOAuthFlow.refreshAccessToken();
            if (newToken != null) {
                result = callApi(newToken, systemPrompt, userPrompt);
            }
            if (result == null) {
                throw new RuntimeException(
                    "ChatGPT token expired and could not be refreshed.\n"
                        + "Please sign in again from the Settings page.");
            }
        }
        return result;
    }

    @Override
    public @NotNull Id id() { return Id.CODEX_CLI; }

    // ── API call ──────────────────────────────────────────────────────────────

    /**
     * Calls the OpenAI Chat Completions API. Returns the generated text, or
     * {@code null} if the token is invalid/expired (HTTP 401/403).
     */
    @Nullable
    private static String callApi(@NotNull String token,
                                   @NotNull String systemPrompt,
                                   @NotNull String userPrompt) throws Exception {
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

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return null; // signal caller to refresh
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

    // ── Legacy helpers (for CliPathResolver fallback display) ─────────────────

    /** Returns {@code true} when any credential is available. */
    public static boolean isAuthenticated() {
        return ChatGPTOAuthFlow.isAuthenticated();
    }

    /** Returns account_id from ~/.codex/auth.json for status display, or null. */
    @Nullable
    public static String getAccountId() {
        try {
            Path authFile = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
            if (!Files.isRegularFile(authFile)) return null;
            String json = Files.readString(authFile, StandardCharsets.UTF_8);
            return JsonUtil.extractString(json, "account_id");
        } catch (Exception e) { return null; }
    }

    /** Resolves the ChatGPT OAuth token (used by Settings card status check). */
    @Nullable
    public static String resolveToken() {
        return ChatGPTOAuthFlow.getAccessToken();
    }
}
