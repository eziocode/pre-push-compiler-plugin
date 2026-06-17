package com.github.prepushchecker.commitgen;

import org.jetbrains.annotations.NotNull;

/**
 * Contract that every AI back-end must implement.
 * Each provider receives a fully-assembled system prompt and a user prompt
 * (the git diff) and returns the raw generated commit message text.
 */
public interface CommitMessageProvider {

    enum Id {
        INTELLIJ_AI("JetBrains AI (Connected AI Apps)"),
        OPENAI("OpenAI (GPT-4o)"),
        ANTHROPIC("Anthropic (Claude)"),
        GEMINI("Google Gemini"),
        OLLAMA("Ollama (Local)"),
        CODEX_CLI("OpenAI Codex CLI");

        private final String displayName;

        Id(String displayName) { this.displayName = displayName; }

        public String displayName() { return displayName; }

        @Override
        public String toString() { return displayName; }
    }

    /**
     * Generates a commit message.
     *
     * @param systemPrompt the rules/instruction preamble
     * @param userPrompt   the git diff content
     * @return trimmed commit message text
     * @throws Exception on network, auth, subprocess, or API error
     */
    @NotNull
    String generate(@NotNull String systemPrompt, @NotNull String userPrompt) throws Exception;

    @NotNull
    Id id();
}
