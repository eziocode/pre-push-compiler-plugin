package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import com.github.prepushchecker.commitgen.JsonUtil;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends requests to the OpenAI Chat Completions API.
 * Requires an OpenAI API key stored in PasswordSafe.
 */
public final class OpenAiProvider implements CommitMessageProvider {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        String apiKey = settings.loadApiKey(Id.OPENAI);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "OpenAI API key not configured. Go to Settings → Tools → AI Commit Message Generator.");
        }
        String model = settings.settings().openAiModel;
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
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "OpenAI API error " + response.statusCode() + ": " + response.body());
        }
        String content = JsonUtil.extractStringAtPath(
            response.body(), "choices", 0, "message", "content");
        if (content == null) {
            throw new RuntimeException("Unexpected OpenAI response: " + response.body());
        }
        return content.trim();
    }

    @Override
    public @NotNull Id id() { return Id.OPENAI; }
}
