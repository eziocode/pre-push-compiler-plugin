package com.github.prepushchecker.commitgen.providers;

import com.github.prepushchecker.ProcessExecution;
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
    public @NotNull String generate(@NotNull String systemPrompt, @NotNull String userPrompt)
            throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String ghPath = CliPathResolver.resolve(s.ghCliPath, CLI_NAME);
        String token  = getGhToken(ghPath);

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                "No GitHub token found.\n"
                    + "Run: gh auth login\n"
                    + "Then ensure you have an active GitHub Copilot subscription.");
        }

        String body = "{"
            + "\"model\":\"gpt-4o\","
            + "\"max_tokens\":300,"
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":" + JsonUtil.quoted(systemPrompt) + "},"
            + "{\"role\":\"user\",\"content\":" + JsonUtil.quoted(userPrompt) + "}"
            + "]}";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(COPILOT_API_URL))
            .header("Authorization",          "Bearer " + token)
            .header("Content-Type",           "application/json")
            .header("Copilot-Integration-Id", "vscode-chat")
            .header("Editor-Version",         "vscode/1.85.0")
            .header("Editor-Plugin-Version",  "copilot-chat/0.12.0")
            .header("User-Agent",             "GithubCopilot/1.155.0")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new RuntimeException(
                "GitHub Copilot API returned " + response.statusCode() + ".\n"
                    + "Make sure you:\n"
                    + "  1. Are signed in: gh auth login\n"
                    + "  2. Have an active GitHub Copilot subscription\n"
                    + "  3. Your token has the 'copilot' scope: gh auth refresh -s copilot");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "GitHub Copilot API error " + response.statusCode() + ": " + response.body());
        }

        String content = JsonUtil.extractStringAtPath(
            response.body(), "choices", 0, "message", "content");
        if (content == null) {
            throw new RuntimeException("Unexpected Copilot response: " + response.body());
        }
        return content.trim();
    }

    @Override
    public @NotNull Id id() { return Id.GH_COPILOT; }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
