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
 * Sends requests to a locally running Ollama server.
 * No API key is required. The base URL defaults to {@code http://localhost:11434}
 * and is configurable via Settings.
 */
public final class OllamaProvider implements CommitMessageProvider {

    @Override
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String baseUrl = s.ollamaBaseUrl.replaceAll("/+$", "");
        String model   = s.ollamaModel;
        String url = baseUrl + "/api/chat";

        String body = "{"
            + "\"model\":" + JsonUtil.quoted(model) + ","
            + "\"stream\":false,"
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":" + JsonUtil.quoted(systemPrompt) + "},"
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            throw new RuntimeException(
                "Cannot connect to Ollama at " + baseUrl
                    + ". Make sure Ollama is running (`ollama serve`).", e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Ollama error " + response.statusCode() + ": " + response.body());
        }
        // Parse {"message":{"role":"assistant","content":"..."}, ...}
        String content = JsonUtil.extractString(response.body(), "content");
        if (content == null) {
            throw new RuntimeException("Unexpected Ollama response: " + response.body());
        }
        return content.trim();
    }

    @Override
    public @NotNull Id id() { return Id.OLLAMA; }
}
