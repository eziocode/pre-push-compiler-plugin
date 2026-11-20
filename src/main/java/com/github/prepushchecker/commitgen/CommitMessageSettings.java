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
    /** Binary that was the built-in "Other CLI" default before 3.3; kept only where installed. */
    static final String LEGACY_SAHAA = "sahaa";

    /**
     * One "Other local CLI" configuration: a user-added CLI, or the saved values of a catalog CLI
     * that runs through an args template (gemini, cursor-agent, …). Keyed by {@link #name}, which is
     * also the value of {@link State#customCliBinary} while it is the active CLI.
     */
    public static final class CustomCliEntry {
        public String name = "";           // display name and key; a bare binary name for PATH lookup
        public String path = "";           // absolute path, blank = find {@code name} on PATH
        public String argsTemplate = "";   // {prompt}, {model}, {model?…}; blank = catalog preset
        public String listModelsArgs = ""; // args that print one model per line
        public String model = "";
        public boolean userAdded = true;   // false = remembered values of a catalog CLI

        public CustomCliEntry() {}

        public CustomCliEntry(String name, String path, String argsTemplate, String listModelsArgs,
                              String model, boolean userAdded) {
            this.name = name == null ? "" : name;
            this.path = path == null ? "" : path;
            this.argsTemplate = argsTemplate == null ? "" : argsTemplate;
            this.listModelsArgs = listModelsArgs == null ? "" : listModelsArgs;
            this.model = model == null ? "" : model;
            this.userAdded = userAdded;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CustomCliEntry e && userAdded == e.userAdded
                && java.util.Objects.equals(name, e.name) && java.util.Objects.equals(path, e.path)
                && java.util.Objects.equals(argsTemplate, e.argsTemplate)
                && java.util.Objects.equals(listModelsArgs, e.listModelsArgs)
                && java.util.Objects.equals(model, e.model);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, path, argsTemplate, listModelsArgs, model, userAdded);
        }

        @Override
        public String toString() { return name; }
    }

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
        public String claudeEffort = "low"; // --effort for commit messages; blank = CLI default
        public String codexEffort = "low";  // model_reasoning_effort for commit messages; blank = CLI default

        // Other local CLI (gemini, cursor-agent, a user-added CLI, …): the active one.
        // Every custom CLI's own values are kept in customClis, so switching never wipes them.
        public String customCliBinary     = "";      // catalog binary or CustomCliEntry name; blank = none
        public String customCliPath       = "";      // blank = auto-detect customCliBinary
        public String customCliArgs       = "";      // blank = preset of the binary; prompt on stdin unless {prompt} used
        public String customCliModel      = "";
        public String customCliModelsArgs = "";      // args that list models, one per line
        public java.util.List<CustomCliEntry> customClis = new java.util.ArrayList<>();

        /** Set once the user clicked Generate: only then are messages generated ahead of time. */
        public boolean commitGenerateUsed = false;

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
     *   <li>sahaa, the built-in default before 3.3 (stored as an absent value), stays only when it
     *       is installed; it then becomes a user-added entry.</li>
     * </ul>
     */
    static void migrate(@NotNull State s) {
        migrate(s, CommitMessageSettings::installedWithoutShell);
    }

    static void migrate(@NotNull State s, @NotNull java.util.function.BiPredicate<String, String> installed) {
        if (s.customClis == null) s.customClis = new java.util.ArrayList<>();
        s.customClis.removeIf(e -> e == null || isBlank(e.name));
        boolean customSelected = CommitMessageProvider.Id.CUSTOM_CLI.name().equals(s.selectedProvider);
        if (isBlank(s.customCliBinary) && customSelected && findCustom(s, LEGACY_SAHAA) == null) {
            s.customCliBinary = LEGACY_SAHAA; // absent value = the old default
        }
        AiCli known = AiCli.byBinary(s.customCliBinary);
        if (LEGACY_SAHAA_TEMPLATE.equals(s.customCliArgs) && !LEGACY_SAHAA.equals(trim(s.customCliBinary))) {
            s.customCliArgs = "";
        }
        if (known != null && !known.isCustom()) {
            if (customSelected) s.selectedProvider = known.providerId().name();
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
            clearActiveCustom(s);
            return;
        }
        if (LEGACY_SAHAA.equals(trim(s.customCliBinary)) && findCustom(s, LEGACY_SAHAA) == null) {
            if (installed.test(s.customCliPath, LEGACY_SAHAA)) {
                String args = isBlank(s.customCliArgs) ? LEGACY_SAHAA_TEMPLATE : s.customCliArgs;
                s.customCliArgs = args;
                s.customClis.add(new CustomCliEntry(LEGACY_SAHAA, s.customCliPath, args,
                    s.customCliModelsArgs, s.customCliModel, true));
            } else {
                clearActiveCustom(s);
            }
            return;
        }
        rememberActiveCustom(s);
    }

    private static void clearActiveCustom(@NotNull State s) {
        s.customCliBinary = "";
        s.customCliPath = "";
        s.customCliArgs = "";
        s.customCliModel = "";
        s.customCliModelsArgs = "";
    }

    /** The custom entry named {@code name}, or {@code null}. */
    public static @Nullable CustomCliEntry findCustom(@NotNull State s, @Nullable String name) {
        if (name == null || s.customClis == null) return null;
        String key = name.trim();
        for (CustomCliEntry e : s.customClis) {
            if (e != null && key.equals(trim(e.name))) return e;
        }
        return null;
    }

    /** Stores the active custom CLI's fields in its entry (creating one for a catalog CLI). */
    public static void rememberActiveCustom(@NotNull State s) {
        String name = trim(s.customCliBinary);
        if (name.isEmpty()) return;
        if (s.customClis == null) s.customClis = new java.util.ArrayList<>();
        CustomCliEntry e = findCustom(s, name);
        if (e == null) {
            boolean catalog = AiCli.byBinary(name) != null;
            if (catalog && isBlank(s.customCliPath) && isBlank(s.customCliArgs) && isBlank(s.customCliModel)
                    && isBlank(s.customCliModelsArgs)) {
                return; // nothing worth remembering for a catalog preset
            }
            e = new CustomCliEntry(name, "", "", "", "", !catalog);
            s.customClis.add(e);
        }
        e.path = nz(s.customCliPath);
        e.argsTemplate = nz(s.customCliArgs);
        e.model = nz(s.customCliModel);
        e.listModelsArgs = nz(s.customCliModelsArgs);
    }

    /** Makes {@code name} (a catalog binary or an entry) the active custom CLI, restoring its values. */
    public static void activateCustom(@NotNull State s, @NotNull String name) {
        rememberActiveCustom(s);
        CustomCliEntry e = findCustom(s, name);
        s.customCliBinary = name.trim();
        s.customCliPath = e == null ? "" : nz(e.path);
        s.customCliArgs = e == null ? "" : nz(e.argsTemplate);
        s.customCliModel = e == null ? "" : nz(e.model);
        s.customCliModelsArgs = e == null ? "" : nz(e.listModelsArgs);
    }

    /** User-added custom CLIs, in the order they were added. */
    public static @NotNull java.util.List<CustomCliEntry> userCustomClis(@NotNull State s) {
        java.util.List<CustomCliEntry> out = new java.util.ArrayList<>();
        if (s.customClis == null) return out;
        for (CustomCliEntry e : s.customClis) if (e != null && e.userAdded && !isBlank(e.name)) out.add(e);
        return out;
    }

    /**
     * Cheap install check without a shell (settings load may run on the EDT): a configured
     * absolute path, the IDE's PATH, then the well-known install directories.
     */
    static boolean installedWithoutShell(@Nullable String configured, @NotNull String name) {
        try {
            if (!isBlank(configured)) {
                java.nio.file.Path p = java.nio.file.Path.of(configured.trim());
                if (p.isAbsolute() && java.nio.file.Files.isExecutable(p)) return true;
            }
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    if (!dir.isBlank() && java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, name))) return true;
                }
            }
            return CliPathResolver.findInExtraDirs(name) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static @NotNull String trim(@Nullable String v) {
        return v == null ? "" : v.trim();
    }

    private static @NotNull String nz(@Nullable String v) {
        return v == null ? "" : v;
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
