package com.github.prepushchecker.commitgen;

import com.github.prepushchecker.commitgen.cli.AiCallScheduler;
import com.github.prepushchecker.commitgen.providers.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Project-level service that orchestrates commit message generation.
 * Resolves the configured AI provider, builds the prompt from the current
 * git diff, and returns the raw generated message.
 */
@Service(Service.Level.PROJECT)
public final class CommitMessageGeneratorService {

    private final Project project;

    public CommitMessageGeneratorService(@NotNull Project project) {
        this.project = project;
    }

    public static CommitMessageGeneratorService getInstance(@NotNull Project project) {
        return project.getService(CommitMessageGeneratorService.class);
    }

    /**
     * Builds the diff-based prompt and delegates to the configured AI provider.
     *
     * @return the trimmed commit message
     * @throws Exception on diff failure, auth error, or API error
     */
    public @NotNull String generate() throws Exception {
        return generate(Collections.emptyList());
    }

    /**
     * Generates a commit message using only the {@code selectedChanges} (the checked
     * files in IntelliJ's Commit panel). Pass an empty list to use all staged/unstaged
     * changes (same as {@link #generate()}).
     */
    public @NotNull String generate(@NotNull List<Change> selectedChanges) throws Exception {
        return generate(selectedChanges, List.of());
    }

    /** As {@link #generate(List)}, including checked unversioned files as new files. */
    public @NotNull String generate(@NotNull List<Change> selectedChanges,
                                    @NotNull List<java.nio.file.Path> unversioned) throws Exception {
        return generate(selectedChanges, unversioned, null, null);
    }

    /**
     * As {@link #generate(List, List)}, with the cache: the same diff, rules and provider settings
     * return the cached message (or join a speculative generation still running), unless the
     * commit field already shows that message ({@code currentText}): clicking Generate again
     * then asks for a new one. {@code onPartial} receives the answer so far while a streaming
     * provider writes it (background thread).
     */
    public @NotNull String generate(@NotNull List<Change> selectedChanges,
                                    @NotNull List<java.nio.file.Path> unversioned,
                                    @org.jetbrains.annotations.Nullable String currentText,
                                    @org.jetbrains.annotations.Nullable java.util.function.Consumer<String> onPartial)
            throws Exception {
        long started = System.nanoTime();
        CommitMessagePromptBuilder.Prompt prompt =
            CommitMessagePromptBuilder.build(project, selectedChanges, unversioned);
        long promptMs = (System.nanoTime() - started) / 1_000_000;
        String key = CommitMessageCache.key(prompt.system(), prompt.user(), CommitMessageSettings.getInstance().settings());
        String current = currentText == null ? "" : currentText.strip();
        String cached = CommitMessageCache.get(key);
        if (cached != null && !cached.strip().equals(current)) {
            LOG.info("Commit message from cache (prompt " + promptMs + " ms)");
            return cached;
        }
        java.util.concurrent.CompletableFuture<String> speculative = CommitMessageCache.running(key);
        if (speculative != null && cached == null) {
            String joined = awaitQuietly(speculative);
            if (joined != null && !joined.strip().equals(current)) {
                LOG.info("Commit message joined a speculative generation (prompt " + promptMs + " ms, total "
                    + (System.nanoTime() - started) / 1_000_000 + " ms)");
                return joined;
            }
        }
        java.util.concurrent.CompletableFuture<String> mine = new java.util.concurrent.CompletableFuture<>();
        boolean registered = CommitMessageCache.startRunning(key, mine);
        try {
            AiRequest request = AiRequest.commitMessage(prompt.system(), prompt.user()).withPartial(onPartial);
            String message = AiOutputSanitizer.cleanOrThrow(resolveProvider().generate(request));
            if (registered) mine.complete(message); else CommitMessageCache.put(key, message);
            LOG.info("Commit message generated: prompt " + promptMs + " ms, total "
                + (System.nanoTime() - started) / 1_000_000 + " ms");
            return message;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        }
    }

    /**
     * Generates ahead of time in the background lane and caches the result; does nothing when the
     * message for this diff is cached or already being generated. Blocking; call off the EDT.
     *
     * @return {@code true} when a generation ran
     */
    public boolean pregenerate(@NotNull List<Change> selectedChanges,
                               @NotNull List<java.nio.file.Path> unversioned) throws Exception {
        CommitMessagePromptBuilder.Prompt prompt =
            CommitMessagePromptBuilder.build(project, selectedChanges, unversioned);
        String key = CommitMessageCache.key(prompt.system(), prompt.user(), CommitMessageSettings.getInstance().settings());
        if (CommitMessageCache.get(key) != null) return false;
        java.util.concurrent.CompletableFuture<String> mine = new java.util.concurrent.CompletableFuture<>();
        if (!CommitMessageCache.startRunning(key, mine)) return false;
        try {
            String message = com.github.prepushchecker.commitgen.cli.AiCallScheduler.inBackground(() ->
                AiOutputSanitizer.cleanOrThrow(resolveProvider().generate(
                    AiRequest.commitMessage(prompt.system(), prompt.user()))));
            mine.complete(message);
            LOG.info("Commit message generated ahead of time");
            return true;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t instanceof Exception e ? e : new RuntimeException(t);
        }
    }

    private static @org.jetbrains.annotations.Nullable String awaitQuietly(
            @NotNull java.util.concurrent.CompletableFuture<String> future) {
        com.intellij.openapi.progress.ProgressIndicator indicator =
            com.intellij.openapi.progress.ProgressIndicatorProvider.getGlobalProgressIndicator();
        while (true) {
            if (indicator != null) indicator.checkCanceled();
            try {
                return future.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // poll again so cancelling the task still works
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null; // the speculative call failed or yielded: generate normally
            }
        }
    }

    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(CommitMessageGeneratorService.class);

    /** Convenience overload for testing with a custom diff string. */
    public @NotNull String generateForDiff(@NotNull String diff) throws Exception {
        CommitMessagePromptBuilder.Prompt prompt = CommitMessagePromptBuilder.buildForDiff(project, diff);
        return AiOutputSanitizer.cleanOrThrow(resolveProvider().generate(prompt.system(), prompt.user()));
    }

    /** The provider selected in settings (OpenAI when the stored value is unknown). */
    public static @NotNull CommitMessageProvider resolveProvider() {
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        CommitMessageProvider.Id id;
        try {
            id = CommitMessageProvider.Id.valueOf(settings.settings().selectedProvider);
        } catch (IllegalArgumentException | NullPointerException e) {
            id = CommitMessageProvider.Id.OPENAI;
        }
        return providerFor(id);
    }

    /**
     * Creates the provider for {@code id}; the single place that maps ids to implementations.
     * Every request goes through {@link AiCallScheduler} (priority lanes, slot limit).
     */
    public static @NotNull CommitMessageProvider providerFor(@NotNull CommitMessageProvider.Id id) {
        return AiCallScheduler.wrap(rawProviderFor(id));
    }

    private static @NotNull CommitMessageProvider rawProviderFor(@NotNull CommitMessageProvider.Id id) {
        return switch (id) {
            case INTELLIJ_AI -> new IntelliJAiProvider();
            case OPENAI      -> new OpenAiProvider();
            case ANTHROPIC   -> new AnthropicProvider();
            case GEMINI      -> new GeminiProvider();
            case OLLAMA      -> new OllamaProvider();
            case CODEX_CLI   -> new CodexCliProvider();
            case GH_COPILOT  -> new GhCopilotProvider();
            case LLM_CLI     -> new LlmCliProvider();
            case CLAUDE_CLI  -> new ClaudeCliProvider();
            case CUSTOM_CLI  -> new CustomCliProvider();
        };
    }
}
