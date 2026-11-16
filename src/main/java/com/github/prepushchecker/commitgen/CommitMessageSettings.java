package com.github.prepushchecker.commitgen;

import com.github.prepushchecker.commitgen.cli.AiCli;
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

    /** Where users find the AI settings; used in error messages. */
    public static final String SETTINGS_PATH = "Settings → Tools → Pre-Push Checker → AI Provider";

    /** Default "Other CLI" template before 2.10.0; only valid for sahaa. */
    static final String LEGACY_SAHAA_TEMPLATE = "--chat {model?-m {model}} {prompt}";

    public static final class State {
        public String selectedProvider = CommitMessageProvider.Id.OPENAI.name();

        // Model overrides per provider
        public String openAiModel      = "gpt-4o";
        public String anthropicModel   = "claude-3-5-sonnet-20241022";
        public String geminiModel      = "gemini-1.5-flash";
        public String ollamaModel      = "llama3.2";

        // Non-secret provider config
        public String ollamaBaseUrl  = "http://localhost:11434";
        public String codexCliPath      = "";   // blank = auto-detect
        public String codexModel        = "";   // blank = codex default model
        public String codexExtraArgs    = "";   // extra args appended to `codex exec`
        public String ghCliPath      = "";   // blank = auto-detect (for GH_COPILOT)
        public String ghModel        = "";   // blank = gpt-4o
        public String llmCliPath     = "";   // blank = auto-detect
        public String llmModel       = "";   // blank = llm default model
        public String claudeCliPath  = "";   // blank = auto-detect
        public String claudeModel    = "";   // blank = CLI default model
        public String claudeExtraArgs = ""; // extra args appended to `claude -p`

        // Other local CLI (sahaa, gemini, cursor-agent, …)
        public String customCliBinary     = "sahaa"; // binary name used for auto-detect
        public String customCliPath       = "";      // blank = auto-detect customCliBinary
        public String customCliArgs       = "";      // blank = preset of the binary; prompt on stdin unless {prompt} used
        public String customCliModel      = "";
        public String customCliModelsArgs = "";      // args that list models, one per line

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

        /**
         * Team's default branch name used by the rules-file branch gate
         * (e.g. the branch on which {@code ISSUEFIX:} is allowed). Many teams use a
         * default branch whose name does not match what {@code origin/HEAD} points to
         * (or {@code origin/HEAD} is not set locally), so relying on git auto-detection
         * alone produced a wrong "non-default" verdict and suppressed {@code ISSUEFIX:}
         * even on the real default branch.
         *
         * <p>When set, this value is authoritative for the branch-gate verdict. When
         * blank, the plugin falls back to git auto-detection
         * ({@code git symbolic-ref refs/remotes/origin/HEAD}); if that also fails no
         * hard verdict is asserted and the AI applies the rules-file decision tree
         * using the branch name alone (preserving the pre-1.8.8 behaviour).</p>
         */
        public String  defaultBranchName      = "";
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
        migrate(s);
        this.state = s;
    }

    /**
     * Fixes saved states that ran a CLI the wrong way:
     * <ul>
     *   <li>"Other CLI" set to a binary that has its own provider (codex, claude, llm, gh) moves
     *       to that provider, taking the path and model along. This was the cause of
     *       {@code codex --chat} and bare {@code codex} ("stdin is not a terminal").</li>
     *   <li>The old sahaa default template saved for another binary is cleared, so that
     *       binary's preset is used.</li>
     * </ul>
     */
    static void migrate(@NotNull State s) {
        AiCli known = AiCli.byBinary(s.customCliBinary);
        if (LEGACY_SAHAA_TEMPLATE.equals(s.customCliArgs) && known != AiCli.SAHAA) {
            s.customCliArgs = "";
        }
        if (known == null || known.isCustom()) return;
        if (CommitMessageProvider.Id.CUSTOM_CLI.name().equals(s.selectedProvider)) {
            s.selectedProvider = known.providerId().name();
        }
        String path = s.customCliPath == null ? "" : s.customCliPath;
        String model = s.customCliModel == null ? "" : s.customCliModel;
        switch (known) {
            case CODEX -> {
                if (isBlank(s.codexCliPath)) s.codexCliPath = path;
                if (isBlank(s.codexModel)) s.codexModel = model;
            }
            case CLAUDE -> {
                if (isBlank(s.claudeCliPath)) s.claudeCliPath = path;
                if (isBlank(s.claudeModel)) s.claudeModel = model;
            }
            case LLM -> {
                if (isBlank(s.llmCliPath)) s.llmCliPath = path;
                if (isBlank(s.llmModel)) s.llmModel = model;
            }
            case GH -> {
                if (isBlank(s.ghCliPath)) s.ghCliPath = path;
                if (isBlank(s.ghModel)) s.ghModel = model;
            }
            default -> { }
        }
        s.customCliBinary = AiCli.SAHAA.binaryName();
        s.customCliPath = "";
        s.customCliArgs = "";
        s.customCliModel = "";
        s.customCliModelsArgs = "";
    }

    private static boolean isBlank(@Nullable String v) {
        return v == null || v.isBlank();
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
