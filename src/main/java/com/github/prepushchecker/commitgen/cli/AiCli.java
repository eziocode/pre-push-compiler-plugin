package com.github.prepushchecker.commitgen.cli;

import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Registry of local AI command-line tools the plugin can auto-detect.
 *
 * <p>Each entry is found the same way as the GitHub CLI: by binary name via
 * {@link com.github.prepushchecker.commitgen.CliPathResolver}, honouring a
 * user-configured path first. Entries mapped to {@link CommitMessageProvider.Id#CUSTOM_CLI}
 * are driven by the generic "Other local CLI" provider using
 * {@link #defaultArgsTemplate()}.</p>
 */
public enum AiCli {
    CLAUDE("Claude Code", CommitMessageProvider.Id.CLAUDE_CLI, "claude",
        "", "npm install -g @anthropic-ai/claude-code && claude"),
    CODEX("Codex", CommitMessageProvider.Id.CODEX_CLI, "codex",
        "", "npm install -g @openai/codex && codex login"),
    GH("GitHub CLI (Copilot)", CommitMessageProvider.Id.GH_COPILOT, "gh",
        "", "brew install gh && gh auth login"),
    LLM("llm", CommitMessageProvider.Id.LLM_CLI, "llm",
        "", "pip install llm && llm keys set openai"),
    SAHAA("Sahaa", CommitMessageProvider.Id.CUSTOM_CLI, "sahaa",
        "--chat {model?-m {model}} {prompt}", "Install sahaa, then run: sahaa auth"),
    GEMINI("Gemini CLI", CommitMessageProvider.Id.CUSTOM_CLI, "gemini",
        "{model?-m {model}} -p {prompt}", "npm install -g @google/gemini-cli && gemini"),
    CURSOR_AGENT("Cursor Agent", CommitMessageProvider.Id.CUSTOM_CLI, "cursor-agent",
        "-p --output-format text {model?--model {model}} {prompt}", "curl https://cursor.com/install -fsS | bash"),
    COPILOT("GitHub Copilot CLI", CommitMessageProvider.Id.CUSTOM_CLI, "copilot",
        "{model?--model {model}} -p {prompt}", "npm install -g @github/copilot"),
    OPENCODE("opencode", CommitMessageProvider.Id.CUSTOM_CLI, "opencode",
        "run {model?-m {model}} {prompt}", "npm install -g opencode-ai");

    private final String displayName;
    private final CommitMessageProvider.Id providerId;
    private final String binaryName;
    private final String defaultArgsTemplate;
    private final String installHint;

    AiCli(String displayName, CommitMessageProvider.Id providerId, String binaryName,
          String defaultArgsTemplate, String installHint) {
        this.displayName = displayName;
        this.providerId = providerId;
        this.binaryName = binaryName;
        this.defaultArgsTemplate = defaultArgsTemplate;
        this.installHint = installHint;
    }

    public @NotNull String displayName() { return displayName; }

    public @NotNull CommitMessageProvider.Id providerId() { return providerId; }

    public @NotNull String binaryName() { return binaryName; }

    /** Args template for {@link CommitMessageProvider.Id#CUSTOM_CLI} entries. */
    public @NotNull String defaultArgsTemplate() { return defaultArgsTemplate; }

    public @NotNull String installHint() { return installHint; }

    public boolean isCustom() { return providerId == CommitMessageProvider.Id.CUSTOM_CLI; }

    /** The user-configured path override for this CLI, or {@code null}. */
    public @Nullable String configuredPath(@NotNull CommitMessageSettings.State s) {
        return switch (this) {
            case CLAUDE -> s.claudeCliPath;
            case CODEX  -> s.codexCliPath;
            case GH     -> s.ghCliPath;
            case LLM    -> s.llmCliPath;
            default     -> binaryName.equals(s.customCliBinary) ? s.customCliPath : null;
        };
    }

    /** Looks up a registry entry by binary name, or {@code null}. */
    public static @Nullable AiCli byBinary(@Nullable String binary) {
        if (binary == null) return null;
        for (AiCli cli : values()) {
            if (cli.binaryName.equals(binary.trim())) return cli;
        }
        return null;
    }

    @Override
    public String toString() { return displayName; }
}
