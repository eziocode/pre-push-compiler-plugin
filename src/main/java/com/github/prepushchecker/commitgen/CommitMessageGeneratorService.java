package com.github.prepushchecker.commitgen;

import com.github.prepushchecker.commitgen.providers.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
        CommitMessagePromptBuilder.Prompt prompt = CommitMessagePromptBuilder.build(project);
        CommitMessageProvider provider = resolveProvider();
        return provider.generate(prompt.system(), prompt.user());
    }

    /** Convenience overload for testing with a custom diff string. */
    public @NotNull String generateForDiff(@NotNull String diff) throws Exception {
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        String systemPrompt = CommitMessagePromptBuilder.build(project).system();
        String userPrompt   = "Git diff:\n```\n" + diff + "\n```";
        return resolveProvider().generate(systemPrompt, userPrompt);
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
        };
    }
}
