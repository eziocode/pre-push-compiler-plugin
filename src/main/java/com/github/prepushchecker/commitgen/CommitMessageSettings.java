package com.github.prepushchecker.commitgen;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level persistent settings for the AI commit message generator.
 * Plain provider config (URLs, model names, rule flags) is persisted to XML.
 * API keys are stored securely via IntelliJ's PasswordSafe and never written
 * to the XML state file.
 */
@State(name = "CommitMessageSettings", storages = @Storage("commitMessageGenerator.xml"))
public final class CommitMessageSettings implements PersistentStateComponent<CommitMessageSettings.State> {

    public static final class State {
        public String selectedProvider = CommitMessageProvider.Id.OPENAI.name();

        // Model overrides per provider
        public String openAiModel      = "gpt-4o";
        public String anthropicModel   = "claude-3-5-sonnet-20241022";
        public String geminiModel      = "gemini-1.5-flash";
        public String ollamaModel      = "llama3.2";

        // Non-secret provider config
        public String ollamaBaseUrl  = "http://localhost:11434";
        public String codexCliPath   = "";   // blank = auto-detect
        public String ghCliPath      = "";   // blank = auto-detect (for GH_COPILOT)
        public String llmCliPath     = "";   // blank = auto-detect
        public String llmModel       = "";   // blank = llm default model

        // Commit message rules
        public boolean useConventionalCommits = true;
        public int     maxSubjectLength       = 72;
        public String  prefixTemplate         = "";
        public String  language               = "English, concise";
        public boolean autoDetectScope        = true;
        public String  extraInstructions      = "";

        /**
         * Path (relative to project root) of an optional markdown file whose
         * content is appended verbatim to the AI system prompt.
         * Checked in order: this field → .github/commit-instructions.md → COMMIT_RULES.md.
         * Leave blank to use the default search order.
         */
        public String  customRulesFilePath    = "";
    }

    private State state = new State();

    public static CommitMessageSettings getInstance() {
        return ApplicationManager.getApplication().getService(CommitMessageSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State s) {
        this.state = s;
    }

    public @NotNull State settings() {
        return state;
    }

    // ── Secure API key access via PasswordSafe ────────────────────────────────

    private static CredentialAttributes credAttrs(@NotNull CommitMessageProvider.Id provider) {
        return new CredentialAttributes("PrePushChecker.CommitGen", provider.name());
    }

    public void saveApiKey(@NotNull CommitMessageProvider.Id provider, @Nullable String key) {
        PasswordSafe.getInstance().set(
            credAttrs(provider),
            key == null || key.isBlank()
                ? null
                : new Credentials(provider.name(), key)
        );
    }

    @Nullable
    public String loadApiKey(@NotNull CommitMessageProvider.Id provider) {
        Credentials c = PasswordSafe.getInstance().get(credAttrs(provider));
        return c == null ? null : c.getPasswordAsString();
    }
}
