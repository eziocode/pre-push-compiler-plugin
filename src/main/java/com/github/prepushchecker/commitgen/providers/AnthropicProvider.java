package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import com.github.prepushchecker.commitgen.AiRequest;
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
 * Sends requests to the Anthropic Messages API.
 * Requires an Anthropic API key stored in PasswordSafe.
 */
public final class AnthropicProvider implements CommitMessageProvider {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        String systemPrompt = request.system();
        String userPrompt = request.user();
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        String apiKey = settings.loadApiKey(Id.ANTHROPIC);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "Anthropic API key not configured. Go to " + CommitMessageSettings.SETTINGS_PATH + ".");
        }
        String model = request.modelOr(settings.settings().anthropicModel);
        // Reviews must give the same findings on every run: temperature 0. A model that rejects
        // the parameter (400) is asked again without it.
        boolean temperature = request.kind() == AiRequest.Kind.REVIEW;
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpResponse<String> response = send(client, apiKey, request, model, systemPrompt, userPrompt, temperature);
        if (temperature && response.statusCode() == 400 && response.body() != null
            && response.body().contains("temperature")) {
            response = send(client, apiKey, request, model, systemPrompt, userPrompt, false);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Anthropic API error " + response.statusCode() + ": " + AiOutputSanitizer.errorDetail(response.body()));
        }
        String text = JsonUtil.extractStringAtPath(response.body(), "content", 0, "text");
        if (text == null) {
            throw new RuntimeException("Unexpected Anthropic response: " + AiOutputSanitizer.errorDetail(response.body()));
        }
        return text.trim();
    }

    private static HttpResponse<String> send(HttpClient client, String apiKey, AiRequest request, String model,
                                             String systemPrompt, String userPrompt, boolean temperature)
            throws Exception {
        // Anthropic API supports a top-level "system" field
        String body = "{"
            + "\"model\":" + JsonUtil.quoted(model) + ","
            + "\"max_tokens\":" + request.maxOutputTokens() + ","
            + (temperature ? "\"temperature\":0," : "")
            + "\"system\":" + JsonUtil.quoted(systemPrompt) + ","
            + "\"messages\":["
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .timeout(request.timeout())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public @NotNull Id id() { return Id.ANTHROPIC; }
}
