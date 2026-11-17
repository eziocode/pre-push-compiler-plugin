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
 * Sends requests to a locally running Ollama server.
 * No API key is required. The base URL defaults to {@code http://localhost:11434}
 * and is configurable via Settings.
 */
public final class OllamaProvider implements CommitMessageProvider {

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        String systemPrompt = request.system();
        String userPrompt = request.user();
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String baseUrl = s.ollamaBaseUrl.replaceAll("/+$", "");
        String model   = request.modelOr(s.ollamaModel);
        String url = baseUrl + "/api/chat";

        String body = "{"
            + "\"model\":" + JsonUtil.quoted(model) + ","
            + "\"stream\":false,"
            // Reviews must give the same findings on every run: greedy sampling with a fixed seed.
            + (request.kind() == AiRequest.Kind.REVIEW ? "\"options\":{\"temperature\":0,\"seed\":0}," : "")
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":" + JsonUtil.quoted(systemPrompt) + "},"
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(request.kind() != AiRequest.Kind.COMPLETION
                && request.timeout().compareTo(Duration.ofSeconds(120)) < 0
                ? Duration.ofSeconds(120) : request.timeout())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            throw new RuntimeException(
                "Cannot connect to Ollama at " + baseUrl
                    + ". Make sure Ollama is running (`ollama serve`).", e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Ollama error " + response.statusCode() + ": " + AiOutputSanitizer.errorDetail(response.body()));
        }
        String content = JsonUtil.extractStringAtPath(response.body(), "message", "content");
        if (content == null) {
            throw new RuntimeException("Unexpected Ollama response: " + AiOutputSanitizer.errorDetail(response.body()));
        }
        return content.trim();
    }

    @Override
    public @NotNull Id id() { return Id.OLLAMA; }
}
