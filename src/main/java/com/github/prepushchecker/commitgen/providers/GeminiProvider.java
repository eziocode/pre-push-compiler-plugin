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
 * Sends requests to the Google Gemini generateContent API.
 * Requires a Gemini API key stored in PasswordSafe.
 */
public final class GeminiProvider implements CommitMessageProvider {

    private static final String API_BASE =
        "https://generativelanguage.googleapis.com/v1beta/models/";

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        String apiKey = settings.loadApiKey(Id.GEMINI);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "Gemini API key not configured. Go to Settings → Tools → AI Commit Message Generator.");
        }
        String model = settings.settings().geminiModel;
        String url = API_BASE + model + ":generateContent?key=" + apiKey;

        // Gemini supports a systemInstruction field (v1beta)
        String body = "{"
            + "\"systemInstruction\":{"
            + "\"parts\":[{\"text\":" + JsonUtil.quoted(systemPrompt) + "}]},"
            + "\"contents\":[{"
            + "\"parts\":[{\"text\":" + JsonUtil.quoted(userPrompt) + "}]"
            + "}]}";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Gemini API error " + response.statusCode() + ": " + response.body());
        }
        String text = JsonUtil.extractStringAtPath(
            response.body(), "candidates", 0, "content", "parts", 0, "text");
        if (text == null) {
            throw new RuntimeException("Unexpected Gemini response: " + response.body());
        }
        return text.trim();
    }

    @Override
    public @NotNull Id id() { return Id.GEMINI; }
}
