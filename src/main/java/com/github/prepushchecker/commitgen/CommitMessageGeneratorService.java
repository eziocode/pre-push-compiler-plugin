package com.github.prepushchecker.commitgen;

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
        CommitMessagePromptBuilder.Prompt prompt =
            CommitMessagePromptBuilder.build(project, selectedChanges);
        CommitMessageProvider provider = resolveProvider();
        return provider.generate(prompt.system(), prompt.user());
    }

    /** Convenience overload for testing with a custom diff string. */
    public @NotNull String generateForDiff(@NotNull String diff) throws Exception {
        CommitMessagePromptBuilder.Prompt prompt = CommitMessagePromptBuilder.buildForDiff(project, diff);
        return resolveProvider().generate(prompt.system(), prompt.user());
    }

    private @NotNull CommitMessageProvider resolveProvider() {
        CommitMessageSettings settings = CommitMessageSettings.getInstance();
        CommitMessageProvider.Id id;
        try {
            id = CommitMessageProvider.Id.valueOf(settings.settings().selectedProvider);
        } catch (IllegalArgumentException e) {
            id = CommitMessageProvider.Id.OPENAI;
        }
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
        };
    }
}
