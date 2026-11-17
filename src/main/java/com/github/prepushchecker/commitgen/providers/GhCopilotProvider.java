package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.ProcessExecution;
import com.github.prepushchecker.commitgen.CliPathResolver;
import com.github.prepushchecker.commitgen.AiOutputSanitizer;
import com.github.prepushchecker.commitgen.AiRequest;
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
 * Generates commit messages via the <b>GitHub Copilot Chat API</b>.
 *
 * <h3>Authentication</h3>
 * <ol>
 *   <li>Install the GitHub CLI: <a href="https://cli.github.com">cli.github.com</a></li>
 *   <li>Sign in: {@code gh auth login}</li>
 *   <li>This provider calls {@code gh auth token} to obtain the current OAuth
 *       token and uses it with the Copilot Chat completions endpoint.</li>
 * </ol>
 *
 * <h3>PATH detection</h3>
 * {@code gh} is resolved via {@link CliPathResolver} so it is found even when
 * IntelliJ was launched as a GUI app with a stripped PATH.
 */
public final class GhCopilotProvider implements CommitMessageProvider {

    public static final String CLI_NAME = "gh";
    private static final String COPILOT_API_URL =
        "https://api.githubcopilot.com/chat/completions";

    @Override
    public @NotNull String generate(@NotNull AiRequest request) throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        return run(request, s.ghCliPath, request.modelOr(s.ghModel));
    }

    /** Calls the Copilot API with a {@code gh} token; also used by the "Other CLI" provider. */
    static @NotNull String run(@NotNull AiRequest request, @Nullable String configuredPath,
                               @Nullable String model) throws Exception {
        String systemPrompt = request.system();
        String userPrompt = request.user();
        String ghPath = CliPathResolver.resolveOrNull(configuredPath, CLI_NAME);
        if (ghPath == null) {
            throw new IllegalStateException(
                "GitHub CLI (gh) not found.\nInstall: brew install gh && gh auth login"
                    + "\n\nOr set its path in " + CommitMessageSettings.SETTINGS_PATH + ".");
        }
        String token  = cachedGhToken(ghPath);

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "No GitHub token found.\n"
                    + "Run: gh auth login\n"
                    + "Then ensure you have an active GitHub Copilot subscription.");
        }

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        // Reviews must give the same findings on every run: temperature 0. A model that rejects
        // the parameter (400) is asked again without it.
        boolean temperature = request.kind() == AiRequest.Kind.REVIEW;
        HttpResponse<String> response = send(client, token, request, model, systemPrompt, userPrompt, temperature);
        if (temperature && response.statusCode() == 400 && response.body() != null
            && response.body().contains("temperature")) {
            response = send(client, token, request, model, systemPrompt, userPrompt, false);
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            invalidateToken();
            throw new RuntimeException(
                "GitHub Copilot API returned " + response.statusCode() + ".\n"
                    + "Make sure you:\n"
                    + "  1. Are signed in: gh auth login\n"
                    + "  2. Have an active GitHub Copilot subscription\n"
                    + "  3. Your token has the 'copilot' scope: gh auth refresh -s copilot");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "GitHub Copilot API error " + response.statusCode() + ": " + AiOutputSanitizer.errorDetail(response.body()));
        }

        String content = JsonUtil.extractStringAtPath(
            response.body(), "choices", 0, "message", "content");
        if (content == null) {
            throw new RuntimeException("Unexpected Copilot response: " + AiOutputSanitizer.errorDetail(response.body()));
        }
        return content.trim();
    }

    private static HttpResponse<String> send(HttpClient client, String token, AiRequest request,
                                             @Nullable String model, String systemPrompt, String userPrompt,
                                             boolean temperature) throws Exception {
        String body = "{"
            + "\"model\":" + JsonUtil.quoted(model == null || model.isBlank() ? "gpt-4o" : model.trim()) + ","
            + "\"max_tokens\":" + request.maxOutputTokens() + ","
            + (temperature ? "\"temperature\":0," : "")
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":" + JsonUtil.quoted(systemPrompt) + "},"
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(COPILOT_API_URL))
            .header("Authorization",          "Bearer " + token)
            .header("Content-Type",           "application/json")
            .header("Copilot-Integration-Id", "vscode-chat")
            .header("Editor-Version",         "vscode/1.85.0")
            .header("Editor-Plugin-Version",  "copilot-chat/0.12.0")
            .header("User-Agent",             "GithubCopilot/1.155.0")
            .timeout(request.timeout())
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public @NotNull Id id() { return Id.GH_COPILOT; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final long TOKEN_TTL_MS = 10 * 60 * 1000L;
    private record CachedToken(String ghPath, String token, long at) {}
    private static volatile @Nullable CachedToken cachedToken;

    /**
     * {@code gh auth token}, reused for {@link #TOKEN_TTL_MS}: spawning gh on every inline suggestion
     * added its start-up time to each one. Cleared when the API rejects the token.
     */
    static @Nullable String cachedGhToken(@NotNull String ghPath) {
        CachedToken c = cachedToken;
        long now = System.currentTimeMillis();
        if (c != null && c.ghPath().equals(ghPath) && now - c.at() < TOKEN_TTL_MS) return c.token();
        String token = getGhToken(ghPath);
        cachedToken = token == null ? null : new CachedToken(ghPath, token, now);
        return token;
    }

    static void invalidateToken() {
        cachedToken = null;
    }

    /** Runs {@code gh auth token} and returns the token, or {@code null}. */
    public static @Nullable String getGhToken(@NotNull String ghPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ghPath, "auth", "token");
            pb.redirectErrorStream(true);
            CliPathResolver.injectAugmentedPath(pb);
            ProcessExecution.Result result = ProcessExecution.run(pb, Duration.ofSeconds(10));
            if (!result.isSuccess()) return null;
            String out = result.combinedOutput().trim();
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
}
