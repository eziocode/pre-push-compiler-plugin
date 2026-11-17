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
 * Sends requests to the OpenAI Chat Completions API.
 * Requires an OpenAI API key stored in PasswordSafe.
 */
public final class OpenAiProvider implements CommitMessageProvider {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        String systemPrompt = request.system();
        String userPrompt = request.user();
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        String apiKey = settings.loadApiKey(Id.OPENAI);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "OpenAI API key not configured. Go to " + CommitMessageSettings.SETTINGS_PATH + ".");
        }
        String model = request.modelOr(settings.settings().openAiModel);
        // Reviews must give the same findings on every run: temperature 0. Reasoning models
        // reject the parameter (400); they are then asked again without it.
        boolean temperature = request.kind() == AiRequest.Kind.REVIEW;
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpResponse<String> response = send(client, apiKey, request, model, systemPrompt, userPrompt, temperature);
        if (temperature && rejectsTemperature(response)) {
            response = send(client, apiKey, request, model, systemPrompt, userPrompt, false);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "OpenAI API error " + response.statusCode() + ": " + AiOutputSanitizer.errorDetail(response.body()));
        }
        String content = JsonUtil.extractStringAtPath(
            response.body(), "choices", 0, "message", "content");
        if (content == null) {
            throw new RuntimeException("Unexpected OpenAI response: " + AiOutputSanitizer.errorDetail(response.body()));
        }
        return content.trim();
    }

    private static HttpResponse<String> send(HttpClient client, String apiKey, AiRequest request, String model,
                                             String systemPrompt, String userPrompt, boolean temperature)
            throws Exception {
        String body = "{"
            + "\"model\":" + JsonUtil.quoted(model) + ","
            + "\"max_tokens\":" + request.maxOutputTokens() + ","
            + (temperature ? "\"temperature\":0," : "")
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":" + JsonUtil.quoted(systemPrompt) + "},"
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(request.timeout())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    /** {@code true} for a 400 answer that names the temperature parameter. */
    static boolean rejectsTemperature(HttpResponse<String> response) {
        String body = response.body();
        return response.statusCode() == 400 && body != null && body.contains("temperature");
    }

    @Override
    public @NotNull Id id() { return Id.OPENAI; }
}
