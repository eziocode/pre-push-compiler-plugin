package com.github.prepushchecker.commitgen;

import com.github.prepushchecker.commitgen.cli.AiCli;
import com.github.prepushchecker.commitgen.cli.AiCliDetector;
import com.github.prepushchecker.commitgen.cli.AiCliModels;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.table.JBTable;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Configurable that appears under Settings → Tools → AI Commit Message Generator.
 * Handles provider selection, API key entry (via PasswordSafe), and rule options.
 */
public final class CommitMessageSettingsConfigurable implements Configurable {

    // ── UI fields ─────────────────────────────────────────────────────────────

    private JPanel root;

    // Provider
    private JComboBox<CommitMessageProvider.Id> providerCombo;
    private JPanel authPanel;
    private CardLayout authCardLayout;

    // Per-provider auth cards
    private JPasswordField openAiKeyField;
    private JBTextField     openAiModelField;
    private JPasswordField anthropicKeyField;
    private JBTextField     anthropicModelField;
    private JPasswordField geminiKeyField;
    private JBTextField     geminiModelField;
    private JBTextField     ollamaUrlField;
    private JBTextField     ollamaModelField;
    private JPasswordField  codexKeyField;   // fallback only — exported as OPENAI_API_KEY
    private TextFieldWithBrowseButton codexCliPathField;
    private ComboBox<String> codexModelCombo;
    private JBTextField     codexExtraArgsField;
    private JBLabel         codexEnvStatusLabel;
    private JBLabel         codexPathStatusLabel;
    private TextFieldWithBrowseButton ghCliPathField;
    private ComboBox<String> ghModelCombo;
    private JBLabel         ghStatusLabel;
    private TextFieldWithBrowseButton llmCliPathField;
    private ComboBox<String> llmModelCombo;
    private JBLabel         llmStatusLabel;
    private JPasswordField  claudeKeyField;
    private TextFieldWithBrowseButton claudeCliPathField;
    private ComboBox<String> claudeModelCombo;
    private JBTextField     claudeExtraArgsField;
    private JBLabel         claudeEnvStatusLabel;
    private JBTextField     customBinaryField;
    private TextFieldWithBrowseButton customCliPathField;
    private JBTextField     customArgsField;
    private ComboBox<String> customModelCombo;
    private JBTextField     customModelsArgsField;
    private JBLabel         customStatusLabel;
    private JBLabel         intellijAiStatusLabel;

    // Rules
    private JBCheckBox conventionalCommitsCheck;
    private JSpinner   maxLengthSpinner;
    private JBTextField prefixTemplateField;
    private JBTextField languageField;
    private JBCheckBox autoScopeCheck;
    private JBTextArea extraInstructionsArea;
    private TextFieldWithBrowseButton customRulesFileField;
    private JBLabel     customRulesFileStatusLabel;
    private JBTextField defaultBranchField;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Pre-Push Checker — AI Commit Message Generator";
    }

    @Override
    public @Nullable JComponent createComponent() {
        root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(buildProviderPanel(), BorderLayout.NORTH);
        root.add(buildRulesPanel(), BorderLayout.CENTER);
        return root;
    }

    // ── Provider panel ────────────────────────────────────────────────────────

    private @NotNull JPanel buildProviderPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "AI Provider"),
            BorderFactory.createEmptyBorder(4, 6, 6, 6)));

        // Provider selector row
        providerCombo = new JComboBox<>(CommitMessageProvider.Id.values());
        JPanel selectorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        selectorRow.add(new JBLabel("Provider:"));
        selectorRow.add(providerCombo);
        JButton testBtn = new JButton("Test Connection");
        testBtn.addActionListener(ev -> onTestConnection());
        selectorRow.add(testBtn);
        JButton detectAllBtn = new JButton("Detect Installed CLIs…");
        detectAllBtn.setToolTipText(
            "Scan for claude, codex, gh, llm, sahaa, gemini, cursor-agent, copilot and opencode");
        detectAllBtn.addActionListener(ev -> onDetectAllClis());
        selectorRow.add(detectAllBtn);
        panel.add(selectorRow, BorderLayout.NORTH);

        // Auth card panel (switches when provider changes)
        authCardLayout = new CardLayout();
        authPanel = new JPanel(authCardLayout);
        authPanel.add(buildOpenAiCard(),     CommitMessageProvider.Id.OPENAI.name());
        authPanel.add(buildAnthropicCard(),  CommitMessageProvider.Id.ANTHROPIC.name());
        authPanel.add(buildGeminiCard(),     CommitMessageProvider.Id.GEMINI.name());
        authPanel.add(buildOllamaCard(),     CommitMessageProvider.Id.OLLAMA.name());
        authPanel.add(buildCodexCliCard(),   CommitMessageProvider.Id.CODEX_CLI.name());
        authPanel.add(buildGhCopilotCard(),  CommitMessageProvider.Id.GH_COPILOT.name());
        authPanel.add(buildLlmCliCard(),     CommitMessageProvider.Id.LLM_CLI.name());
        authPanel.add(buildClaudeCliCard(),  CommitMessageProvider.Id.CLAUDE_CLI.name());
        authPanel.add(buildCustomCliCard(),  CommitMessageProvider.Id.CUSTOM_CLI.name());
        authPanel.add(buildIntelliJAiCard(), CommitMessageProvider.Id.INTELLIJ_AI.name());
        panel.add(authPanel, BorderLayout.CENTER);

        providerCombo.addActionListener(ev -> {
            CommitMessageProvider.Id selected =
                (CommitMessageProvider.Id) providerCombo.getSelectedItem();
            if (selected != null) {
                showSelectedProviderCard(selected);
            }
        });
        return panel;
    }

    private void showSelectedProviderCard(@NotNull CommitMessageProvider.Id selected) {
        if (selected == CommitMessageProvider.Id.INTELLIJ_AI) {
            updateIntelliJAiStatus();
        }
        authCardLayout.show(authPanel, selected.name());
        authPanel.revalidate();
        authPanel.repaint();
        if (root != null) {
            root.revalidate();
            root.repaint();
        }
    }

    private @NotNull JPanel buildOpenAiCard() {
        openAiKeyField   = new JPasswordField(30);
        openAiModelField = new JBTextField("gpt-4o", 20);
        return buildApiKeyCard("OpenAI API Key:", openAiKeyField, "Model:", openAiModelField,
            "Get a key at platform.openai.com/api-keys");
    }

    private @NotNull JPanel buildAnthropicCard() {
        anthropicKeyField   = new JPasswordField(30);
        anthropicModelField = new JBTextField("claude-3-5-sonnet-20241022", 20);
        return buildApiKeyCard("Anthropic API Key:", anthropicKeyField, "Model:", anthropicModelField,
            "Get a key at console.anthropic.com");
    }

    private @NotNull JPanel buildGeminiCard() {
        geminiKeyField   = new JPasswordField(30);
        geminiModelField = new JBTextField("gemini-1.5-flash", 20);
        return buildApiKeyCard("Gemini API Key:", geminiKeyField, "Model:", geminiModelField,
            "Get a key at aistudio.google.com/app/apikey");
    }

    private @NotNull JPanel buildApiKeyCard(
            String keyLabel, JPasswordField keyField,
            String modelLabel, JBTextField modelField,
            String hint) {
        JPanel card = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE;
        card.add(new JBLabel(keyLabel), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(keyField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        card.add(new JBLabel(modelLabel), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(modelField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JBLabel hintLabel = new JBLabel("<html><i>" + hint + "</i></html>");
        hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        card.add(hintLabel, gbc);
        return card;
    }

    private @NotNull JPanel buildOllamaCard() {
        ollamaUrlField   = new JBTextField("http://localhost:11434", 30);
        ollamaModelField = new JBTextField("llama3.2", 20);
        JPanel card = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; card.add(new JBLabel("Base URL:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(ollamaUrlField, gbc);
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        card.add(new JBLabel("Model:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(ollamaModelField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JBLabel hint = new JBLabel("<html><i>Requires a running Ollama server. Start with: ollama serve</i></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        card.add(hint, gbc);
        return card;
    }

    // ── Local CLI cards (claude, codex, gh, llm, custom) ──────────────────────

    /** Small GridBag helper: label | component | optional trailing button. */
    private static final class CardBuilder {
        final JPanel card = new JPanel(new GridBagLayout());
        private int row = 0;

        CardBuilder row(@Nullable String label, @NotNull JComponent field, @Nullable JComponent trailing) {
            GridBagConstraints g = base();
            g.gridx = 0; g.gridy = row;
            if (label != null) card.add(new JBLabel(label), g);
            g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
            g.gridwidth = trailing == null ? 2 : 1;
            card.add(field, g);
            if (trailing != null) {
                GridBagConstraints t = base();
                t.gridx = 2; t.gridy = row;
                card.add(trailing, t);
            }
            row++;
            return this;
        }

        CardBuilder full(@NotNull JComponent component) {
            GridBagConstraints g = base();
            g.gridx = 0; g.gridy = row++; g.gridwidth = 3;
            g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
            card.add(component, g);
            return this;
        }

        CardBuilder hint(@NotNull String html) {
            JBLabel hint = new JBLabel("<html><i>" + html + "</i></html>");
            hint.setForeground(UIManager.getColor("Label.disabledForeground"));
            return full(hint);
        }

        private static GridBagConstraints base() {
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(3, 0, 3, 6);
            g.anchor = GridBagConstraints.WEST;
            return g;
        }
    }

    private static @NotNull TextFieldWithBrowseButton newCliPathField() {
        JBTextField text = new JBTextField(30);
        text.getEmptyText().setText("blank = auto-detect from PATH");
        TextFieldWithBrowseButton field = new TextFieldWithBrowseButton(text);
        field.addBrowseFolderListener(new TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select CLI Executable")));
        return field;
    }

    private static @NotNull ComboBox<String> newModelCombo(@NotNull List<String> initial,
                                                           @NotNull String tooltip) {
        ComboBox<String> combo = new ComboBox<>(new DefaultComboBoxModel<>(initial.toArray(new String[0])));
        combo.setEditable(true);
        combo.setToolTipText(tooltip);
        setComboText(combo, "");
        return combo;
    }

    private static @NotNull JBTextField newArgsField(@NotNull String emptyText) {
        JBTextField f = new JBTextField(30);
        f.getEmptyText().setText(emptyText);
        return f;
    }

    private static void setStatus(@NotNull JBLabel label, @NotNull String text) {
        boolean ok = text.startsWith("✓");
        boolean bad = text.startsWith("✗");
        String color = ok ? "green" : bad ? "#cc4444" : "gray";
        label.setText("<html><b style='color:" + color + "'>" + escapeHtml(text) + "</b></html>");
    }

    private static @NotNull String escapeHtml(@NotNull String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Builds the "Auto-detect" button for a CLI card. */
    private @NotNull JButton detectButton(@NotNull Supplier<AiCli> cli,
                                          @NotNull Supplier<String> binary,
                                          @NotNull TextFieldWithBrowseButton pathField,
                                          @NotNull JBLabel status) {
        JButton btn = new JButton("Auto-detect");
        btn.addActionListener(ev -> autoDetect(cli.get(), binary.get(), pathField, status));
        return btn;
    }

    /**
     * Detects one CLI off the EDT (modal progress). When it is not found, offers
     * to browse for the binary so the user can point at it manually.
     */
    private void autoDetect(@Nullable AiCli cli, @NotNull String binary,
                            @NotNull TextFieldWithBrowseButton pathField, @NotNull JBLabel status) {
        String configured = pathField.getText().trim();
        AiCliDetector.DetectedCli result = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            () -> cli != null
                ? AiCliDetector.detect(cli, configured)
                : detectUnknown(binary, configured),
            "Detecting " + binary + "…", true, null);
        if (result != null && result.found()) {
            pathField.setText(result.path());
            String version = result.version().isBlank() ? "" : " (" + result.version() + ")";
            setStatus(status, "✓ Found " + result.path() + version + " — " + result.auth());
            return;
        }
        String install = cli != null ? "\n\nInstall: " + cli.installHint() : "";
        setStatus(status, "✗ " + binary + " not found on PATH");
        int choice = Messages.showYesNoDialog(
            "'" + binary + "' was not found on PATH or in common install locations." + install
                + "\n\nBrowse for the executable now?",
            binary + " Not Found", "Browse…", "Cancel", Messages.getWarningIcon());
        if (choice != Messages.YES) return;
        VirtualFile chosen = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select " + binary + " Executable"),
            pathField, null, null);
        if (chosen != null) {
            pathField.setText(chosen.getPath());
            setStatus(status, java.nio.file.Files.isExecutable(Path.of(chosen.getPath()))
                ? "✓ Using " + chosen.getPath()
                : "✗ " + chosen.getPath() + " is not executable");
        }
    }

    private static @NotNull AiCliDetector.DetectedCli detectUnknown(@NotNull String binary,
                                                                   @NotNull String configured) {
        String path = CliPathResolver.resolveOrNull(configured, binary);
        return new AiCliDetector.DetectedCli(AiCli.SAHAA, path,
            path == null ? "" : AiCliDetector.version(path),
            path == null ? "not installed" : "? Uses the CLI's own login");
    }

    /** Builds the ⟳ button that fills {@code combo} with the models the CLI reports. */
    private @NotNull JButton refreshModelsButton(@NotNull Supplier<AiCli> cli,
                                                 @NotNull Supplier<String> binary,
                                                 @NotNull TextFieldWithBrowseButton pathField,
                                                 @NotNull ComboBox<String> combo,
                                                 @NotNull JBLabel status,
                                                 @Nullable Supplier<String> customListArgs) {
        JButton btn = new JButton("⟳ Models");
        btn.setToolTipText("Fetch the models this CLI can use");
        btn.addActionListener(ev -> {
            AiCli target = cli.get();
            String bin = binary.get();
            String configured = pathField.getText().trim();
            String listArgs = customListArgs == null ? null : customListArgs.get();
            List<String> models = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> AiCliModels.list(target, CliPathResolver.resolveOrNull(configured, bin), listArgs),
                "Fetching " + bin + " models…", true, null);
            String current = comboText(combo);
            if (models == null || models.isEmpty()) {
                setStatus(status, customListArgs != null && (listArgs == null || listArgs.isBlank())
                    ? "? Set 'List models args' to fetch models — or type a model name"
                    : "✗ No models reported — type a model name");
                return;
            }
            combo.setModel(new DefaultComboBoxModel<>(models.toArray(new String[0])));
            setComboText(combo, current);
            setStatus(status, "✓ " + models.size() + " models available");
        });
        return btn;
    }

    private @NotNull JPanel buildCodexCliCard() {
        codexEnvStatusLabel  = new JBLabel("<html><i>Checking sign-in status…</i></html>");
        codexPathStatusLabel = new JBLabel(" ");
        codexKeyField        = new JPasswordField(30); // optional manual API-key fallback
        codexCliPathField    = newCliPathField();
        codexModelCombo      = newModelCombo(AiCliModels.staticModels(AiCli.CODEX),
            "Blank = codex default model (from ~/.codex/config.toml)");
        codexExtraArgsField  = newArgsField("extra args for codex exec, e.g. -c model_reasoning_effort=low");

        // Defer I/O to background — do NOT call the status check here on EDT
        scheduleCodexAuthRefresh();

        JButton signInBtn  = new JButton("Sign in with ChatGPT");
        JButton signOutBtn = new JButton("Sign out");
        JButton refreshBtn = new JButton("Refresh Status");

        signInBtn.addActionListener(ev -> {
            apply(); // persist the path so the login flow uses it
            signInBtn.setEnabled(false);
            signInBtn.setText("Opening terminal…");
            com.github.prepushchecker.commitgen.ChatGPTOAuthFlow.startSignIn(() -> {
                scheduleCodexAuthRefresh();
                signInBtn.setText("Sign in with ChatGPT");
                signInBtn.setEnabled(true);
            });
        });
        signOutBtn.addActionListener(ev -> {
            com.github.prepushchecker.commitgen.ChatGPTOAuthFlow.signOut();
            scheduleCodexAuthRefresh();
        });
        refreshBtn.addActionListener(ev -> scheduleCodexAuthRefresh());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.add(signInBtn);
        btnRow.add(signOutBtn);
        btnRow.add(refreshBtn);

        Supplier<AiCli> cli = () -> AiCli.CODEX;
        Supplier<String> bin = () -> "codex";
        return new CardBuilder()
            .row("codex CLI path:", codexCliPathField,
                detectButton(cli, bin, codexCliPathField, codexPathStatusLabel))
            .full(codexPathStatusLabel)
            .row("Model:", codexModelCombo,
                refreshModelsButton(cli, bin, codexCliPathField, codexModelCombo, codexPathStatusLabel, null))
            .row("Extra args:", codexExtraArgsField, null)
            .full(new JBLabel("<html><b>ChatGPT account</b></html>"))
            .full(codexEnvStatusLabel)
            .full(btnRow)
            .row("API key (fallback):", codexKeyField, null)
            .hint("Runs <code>codex exec --sandbox read-only</code> in a temp dir with the prompt on stdin, "
                + "using your <code>codex login</code> session.<br>"
                + "The fallback key is exported as <code>OPENAI_API_KEY</code> for the CLI.")
            .card;
    }

    /** Runs the sign-in status check off the EDT, then updates the label on EDT. */
    private void scheduleCodexAuthRefresh() {
        if (codexEnvStatusLabel == null) return;
        codexEnvStatusLabel.setText("<html><i>Checking…</i></html>");
        new Thread(() -> {
            // getStatusText() may run `codex login status` subprocess — must be off EDT
            String status = com.github.prepushchecker.commitgen.ChatGPTOAuthFlow.getStatusText();
            SwingUtilities.invokeLater(() -> {
                if (codexEnvStatusLabel == null) return;
                setStatus(codexEnvStatusLabel, status != null
                    ? "✓ " + status
                    : "✗ Not signed in — click \"Sign in with ChatGPT\" (runs codex login)");
            });
        }, "CommitGen-CodexAuthCheck").start();
    }

    private @NotNull JPanel buildGhCopilotCard() {
        ghCliPathField = newCliPathField();
        ghModelCombo   = newModelCombo(AiCliModels.staticModels(AiCli.GH), "Blank = gpt-4o");
        ghStatusLabel  = new JBLabel(" ");

        Supplier<AiCli> cli = () -> AiCli.GH;
        Supplier<String> bin = () -> "gh";
        return new CardBuilder()
            .row("gh CLI path:", ghCliPathField, detectButton(cli, bin, ghCliPathField, ghStatusLabel))
            .full(ghStatusLabel)
            .row("Model:", ghModelCombo,
                refreshModelsButton(cli, bin, ghCliPathField, ghModelCombo, ghStatusLabel, null))
            .hint("Requires GitHub CLI + active Copilot subscription. "
                + "Auth: <code>gh auth login</code> &nbsp;|&nbsp; "
                + "Scope: <code>gh auth refresh -s copilot</code>")
            .card;
    }

    private @NotNull JPanel buildLlmCliCard() {
        llmCliPathField = newCliPathField();
        llmModelCombo   = newModelCombo(List.of(), "Blank = llm default model");
        llmStatusLabel  = new JBLabel(" ");

        Supplier<AiCli> cli = () -> AiCli.LLM;
        Supplier<String> bin = () -> "llm";
        return new CardBuilder()
            .row("CLI path:", llmCliPathField, detectButton(cli, bin, llmCliPathField, llmStatusLabel))
            .full(llmStatusLabel)
            .row("Model:", llmModelCombo,
                refreshModelsButton(cli, bin, llmCliPathField, llmModelCombo, llmStatusLabel, null))
            .hint("Supports 60+ models via plugins. "
                + "Install: <code>pip install llm</code> &nbsp;|&nbsp; "
                + "Add provider: <code>llm install llm-anthropic</code> &nbsp;|&nbsp; "
                + "List models: <code>llm models</code>")
            .card;
    }

    private @NotNull JPanel buildClaudeCliCard() {
        claudeKeyField       = new JPasswordField(30);
        claudeCliPathField   = newCliPathField();
        claudeModelCombo     = newModelCombo(AiCliModels.staticModels(AiCli.CLAUDE),
            "Alias (sonnet, opus, haiku) or full model ID. Blank = CLI default");
        claudeExtraArgsField = newArgsField("extra args for claude -p, e.g. --max-turns 1");
        claudeEnvStatusLabel = new JBLabel(" ");

        Supplier<AiCli> cli = () -> AiCli.CLAUDE;
        Supplier<String> bin = () -> "claude";
        return new CardBuilder()
            .row("claude CLI path:", claudeCliPathField,
                detectButton(cli, bin, claudeCliPathField, claudeEnvStatusLabel))
            .full(claudeEnvStatusLabel)
            .row("Model:", claudeModelCombo,
                refreshModelsButton(cli, bin, claudeCliPathField, claudeModelCombo, claudeEnvStatusLabel, null))
            .row("Extra args:", claudeExtraArgsField, null)
            .row("API key (fallback):", claudeKeyField, null)
            .hint("Runs <code>claude -p --output-format text</code> with the prompt on stdin, "
                + "using your Claude Code login (subscription or API key).<br>"
                + "Sign in once with <code>claude</code> in a terminal. "
                + "The fallback key is exported as <code>ANTHROPIC_API_KEY</code> for the CLI.")
            .card;
    }

    private @NotNull JPanel buildCustomCliCard() {
        customBinaryField     = newArgsField("binary name, e.g. sahaa, gemini, cursor-agent");
        customCliPathField    = newCliPathField();
        customArgsField       = newArgsField("e.g. --chat {model?-m {model}} {prompt}");
        customModelCombo      = newModelCombo(List.of(), "Substituted into {model}");
        customModelsArgsField = newArgsField("args that print one model per line, e.g. models");
        customStatusLabel     = new JBLabel(" ");

        Supplier<String> bin = () -> {
            String b = customBinaryField.getText().trim();
            return b.isEmpty() ? AiCli.SAHAA.binaryName() : b;
        };
        Supplier<AiCli> cli = () -> AiCli.byBinary(bin.get());

        JComboBox<AiCli> presetCombo = new JComboBox<>(
            java.util.Arrays.stream(AiCli.values()).filter(AiCli::isCustom).toArray(AiCli[]::new));
        presetCombo.setSelectedItem(null);
        presetCombo.setToolTipText("Fill binary name and args template for a known CLI");
        presetCombo.addActionListener(ev -> {
            AiCli preset = (AiCli) presetCombo.getSelectedItem();
            if (preset == null) return;
            customBinaryField.setText(preset.binaryName());
            customArgsField.setText(preset.defaultArgsTemplate());
            customCliPathField.setText("");
            setStatus(customStatusLabel, "? Preset applied — click Auto-detect");
        });

        Supplier<AiCli> modelCli = () -> {
            AiCli known = cli.get();
            return known != null ? known : AiCli.SAHAA;
        };
        return new CardBuilder()
            .row("Preset:", presetCombo, null)
            .row("Binary name:", customBinaryField, null)
            .row("CLI path:", customCliPathField,
                detectButton(cli, bin, customCliPathField, customStatusLabel))
            .full(customStatusLabel)
            .row("Args template:", customArgsField, null)
            .row("Model:", customModelCombo,
                refreshModelsButton(modelCli, bin, customCliPathField, customModelCombo, customStatusLabel,
                    () -> customModelsArgsField.getText().trim()))
            .row("List models args:", customModelsArgsField, null)
            .hint("Placeholders: <code>{model}</code>, <code>{model?…}</code> (kept only when a model is set), "
                + "<code>{prompt}</code>. Without <code>{prompt}</code> the prompt is sent on stdin.<br>"
                + "Uses the CLI's own login. Runs in an empty temp directory with a 120 s timeout.")
            .card;
    }

    // ── Detect all installed CLIs ─────────────────────────────────────────────

    private void onDetectAllClis() {
        apply(); // scan honours the paths currently typed in the cards
        CommitMessageSettings.State s = CommitMessageSettings.getInstance().settings();
        List<AiCliDetector.DetectedCli> found = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            () -> AiCliDetector.scanAll(s), "Detecting installed AI CLIs…", true, null);
        if (found == null) return;
        DetectedClisDialog dialog = new DetectedClisDialog(found);
        if (!dialog.showAndGet()) return;
        AiCliDetector.DetectedCli chosen = dialog.selected();
        if (chosen == null || !chosen.found()) return;
        useDetectedCli(chosen);
    }

    private void useDetectedCli(@NotNull AiCliDetector.DetectedCli d) {
        String path = d.path();
        String status = "✓ Found " + path
            + (d.version().isBlank() ? "" : " (" + d.version() + ")") + " — " + d.auth();
        switch (d.cli()) {
            case CLAUDE -> { claudeCliPathField.setText(path); setStatus(claudeEnvStatusLabel, status); }
            case CODEX  -> { codexCliPathField.setText(path); setStatus(codexPathStatusLabel, status); }
            case GH     -> { ghCliPathField.setText(path); setStatus(ghStatusLabel, status); }
            case LLM    -> { llmCliPathField.setText(path); setStatus(llmStatusLabel, status); }
            default -> {
                if (!d.cli().binaryName().equals(customBinaryField.getText().trim())) {
                    customArgsField.setText(d.cli().defaultArgsTemplate());
                }
                customBinaryField.setText(d.cli().binaryName());
                customCliPathField.setText(path);
                setStatus(customStatusLabel, status);
            }
        }
        providerCombo.setSelectedItem(d.cli().providerId());
        showSelectedProviderCard(d.cli().providerId());
    }

    /** Table of every known CLI with path / version / sign-in state. */
    private static final class DetectedClisDialog extends DialogWrapper {
        private final List<AiCliDetector.DetectedCli> rows;
        private JBTable table;

        DetectedClisDialog(@NotNull List<AiCliDetector.DetectedCli> rows) {
            super(true);
            this.rows = rows;
            setTitle("Installed AI CLIs");
            setOKButtonText("Use Selected");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            String[] cols = {"CLI", "Found", "Path", "Version", "Sign-in"};
            Object[][] data = new Object[rows.size()][];
            int firstFound = -1;
            for (int i = 0; i < rows.size(); i++) {
                AiCliDetector.DetectedCli d = rows.get(i);
                if (firstFound < 0 && d.found()) firstFound = i;
                data[i] = new Object[]{
                    d.cli().displayName() + " (" + d.cli().binaryName() + ")",
                    d.found() ? "✓" : "✗",
                    d.found() ? d.path() : "—",
                    d.version(),
                    d.found() ? d.auth() : d.cli().installHint()
                };
            }
            table = new JBTable(new javax.swing.table.DefaultTableModel(data, cols) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            });
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.getColumnModel().getColumn(1).setMaxWidth(50);
            if (firstFound >= 0) table.setRowSelectionInterval(firstFound, firstFound);
            JBScrollPane scroll = new JBScrollPane(table);
            scroll.setPreferredSize(new Dimension(900, 260));
            JPanel panel = new JPanel(new BorderLayout(0, 6));
            panel.add(scroll, BorderLayout.CENTER);
            panel.add(new JBLabel("<html><i>Select an installed CLI and click <b>Use Selected</b> to "
                + "fill its path and switch the provider. Not found? Set the path manually in its card."
                + "</i></html>"), BorderLayout.SOUTH);
            return panel;
        }

        @Nullable AiCliDetector.DetectedCli selected() {
            int r = table == null ? -1 : table.getSelectedRow();
            return r < 0 ? null : rows.get(r);
        }
    }

    private @NotNull JPanel buildIntelliJAiCard() {
        intellijAiStatusLabel = new JBLabel();
        updateIntelliJAiStatus();

        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.add(intellijAiStatusLabel, BorderLayout.NORTH);
        JBLabel hint = new JBLabel("<html><i>"
            + "Requires the JetBrains AI Assistant plugin (com.intellij.ml.llm) to be installed "
            + "and a JetBrains account to be signed in."
            + "</i></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        card.add(hint, BorderLayout.CENTER);
        return card;
    }

    private void updateIntelliJAiStatus() {
        if (intellijAiStatusLabel == null) return;
        try {
            com.intellij.openapi.extensions.PluginId pluginId =
                com.intellij.openapi.extensions.PluginId.getId("com.intellij.ml.llm");
            if (PluginManagerCore.isPluginInstalled(pluginId) && !PluginManagerCore.isDisabled(pluginId)) {
                intellijAiStatusLabel.setText(
                    "✓ JetBrains AI Assistant plugin is installed and enabled.");
            } else {
                intellijAiStatusLabel.setText(
                    "✗ JetBrains AI Assistant plugin not found. Install it from the Marketplace.");
            }
        } catch (Exception ignored) {
            intellijAiStatusLabel.setText("Unable to detect JetBrains AI Assistant status.");
        }
    }

    // ── Rules panel ───────────────────────────────────────────────────────────

    private @NotNull JPanel buildRulesPanel() {
        conventionalCommitsCheck = new JBCheckBox("Conventional Commits format (feat:, fix:, chore:, …)");
        conventionalCommitsCheck.setToolTipText(
            "Enforces <type>(<scope>): <subject> format per the Conventional Commits specification.");

        maxLengthSpinner = new JSpinner(new SpinnerNumberModel(72, 0, 500, 1));
        JPanel maxLengthRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        maxLengthRow.add(new JBLabel("Max subject line length:"));
        maxLengthRow.add(maxLengthSpinner);
        maxLengthRow.add(new JBLabel("characters (0 = no limit)"));

        prefixTemplateField = new JBTextField(30);
        prefixTemplateField.getEmptyText().setText("e.g. [JIRA-{branch}] or [PROJ-123]");

        languageField = new JBTextField(20);
        languageField.getEmptyText().setText("e.g. English, concise");

        autoScopeCheck = new JBCheckBox("Auto-detect scope from changed file paths");
        autoScopeCheck.setToolTipText(
            "Instructs the AI to derive the Conventional Commits scope from the module or package name.");

        extraInstructionsArea = new JBTextArea(4, 50);
        extraInstructionsArea.setLineWrap(true);
        extraInstructionsArea.setWrapStyleWord(true);
        extraInstructionsArea.getEmptyText().setText(
            "Any additional instructions appended to every prompt…");

        customRulesFileField = new TextFieldWithBrowseButton();
        customRulesFileField.getTextField().setToolTipText(
            "Leave blank to auto-detect (.github/commit-instructions.md or COMMIT_RULES.md)");
        customRulesFileStatusLabel = new JBLabel();

        // File chooser: single .md / .txt file
        FileChooserDescriptor fileDesc = new FileChooserDescriptor(
                true, false, false, false, false, false)
            .withTitle("Select Commit Rules File")
            .withDescription("Choose a Markdown file with commit message generation rules")
            .withFileFilter(vf -> {
                String ext = vf.getExtension();
                return ext != null && (ext.equalsIgnoreCase("md")
                    || ext.equalsIgnoreCase("markdown")
                    || ext.equalsIgnoreCase("txt"));
            });
        customRulesFileField.addBrowseFolderListener(new TextBrowseFolderListener(fileDesc, getFirstOpenProject()));

        // After a selection, convert absolute path → project-relative when possible
        customRulesFileField.getTextField().getDocument().addDocumentListener(
            new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e)  { normaliseAndUpdateStatus(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e)  { updateRulesFileStatus(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { updateRulesFileStatus(); }
            });

        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.fill   = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(3, 0, 3, 6);
        g.weightx = 1;
        g.gridwidth = 2;

        int row = 0;
        g.gridx = 0; g.gridy = row++; options.add(conventionalCommitsCheck, g);
        g.gridwidth = 1; g.weightx = 0;
        g.gridx = 0; g.gridy = row; options.add(maxLengthRow, g);
        row++;

        g.gridx = 0; g.gridy = row; g.gridwidth = 1; g.weightx = 0;
        options.add(new JBLabel("Prefix / ticket template:"), g);
        g.gridx = 1; g.weightx = 1;
        options.add(prefixTemplateField, g);
        row++;

        g.gridx = 0; g.gridy = row; g.gridwidth = 1; g.weightx = 0;
        options.add(new JBLabel("Language / tone:"), g);
        g.gridx = 1; g.weightx = 1;
        options.add(languageField, g);
        row++;

        defaultBranchField = new JBTextField(30);
        defaultBranchField.getEmptyText().setText("blank = auto-detect (git origin/HEAD)");
        defaultBranchField.setToolTipText(
            "Team default branch used by the rules-file branch gate. When set, ISSUEFIX:/DOCS: "
                + "prefixes are treated as allowed on this branch and forbidden on all others. "
                + "Leave blank to auto-detect from git.");
        g.gridx = 0; g.gridy = row; g.gridwidth = 1; g.weightx = 0;
        options.add(new JBLabel("Default branch (rules gate):"), g);
        g.gridx = 1; g.weightx = 1;
        options.add(defaultBranchField, g);
        row++;

        g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.weightx = 1;
        options.add(autoScopeCheck, g);

        g.gridx = 0; g.gridy = row++; g.gridwidth = 2;
        options.add(new JBLabel("Extra instructions (appended to every prompt):"), g);

        g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        options.add(new JBScrollPane(extraInstructionsArea), g);

        // ── Markdown rules file ───────────────────────────────────────────────
        g.fill = GridBagConstraints.HORIZONTAL; g.weighty = 0;
        g.gridx = 0; g.gridy = row++; g.gridwidth = 2;
        JSeparator sep = new JSeparator();
        sep.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        options.add(sep, g);

        g.gridx = 0; g.gridy = row++; g.gridwidth = 2;
        JBLabel mdHeader = new JBLabel(
            "<html><b>Project rules file</b> — commit to VCS to share rules with your team</html>");
        options.add(mdHeader, g);

        g.gridx = 0; g.gridy = row; g.gridwidth = 1; g.weightx = 0;
        options.add(new JBLabel("Rules file path:"), g);
        g.gridx = 1; g.weightx = 1;
        options.add(customRulesFileField, g);
        row++;

        g.gridx = 0; g.gridy = row++; g.gridwidth = 2;
        options.add(customRulesFileStatusLabel, g);

        g.gridx = 0; g.gridy = row; g.gridwidth = 2; g.weightx = 0;
        JBLabel mdHint = new JBLabel("<html><i>"
            + "Markdown file read at generation time and appended to the AI system prompt. "
            + "Auto-detected paths (if blank): "
            + "<code>.github/commit-instructions.md</code>, <code>COMMIT_RULES.md</code>."
            + "</i></html>");
        mdHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        options.add(mdHint, g);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Commit Message Rules"),
            BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        panel.add(options, BorderLayout.CENTER);
        return panel;
    }

    // ── Configurable interface ────────────────────────────────────────────────

    @Override
    public boolean isModified() {
        if (root == null) return false;
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        CommitMessageSettings.State s = cfg.settings();
        CommitMessageProvider.Id selected =
            (CommitMessageProvider.Id) providerCombo.getSelectedItem();
        if (selected == null) return false;

        if (!selected.name().equals(s.selectedProvider)) return true;
        if (hasPasswordInput(openAiKeyField)) return true;
        if (hasPasswordInput(anthropicKeyField)) return true;
        if (hasPasswordInput(geminiKeyField)) return true;
        if (hasPasswordInput(codexKeyField)) return true;
        if (hasPasswordInput(claudeKeyField)) return true;
        if (!trimmedTextEquals(openAiModelField, s.openAiModel)) return true;
        if (!trimmedTextEquals(anthropicModelField, s.anthropicModel)) return true;
        if (!trimmedTextEquals(geminiModelField, s.geminiModel)) return true;
        if (!trimmedTextEquals(ollamaUrlField, s.ollamaBaseUrl)) return true;
        if (!trimmedTextEquals(ollamaModelField, s.ollamaModel)) return true;
        if (!trimmedTextEquals(codexCliPathField.getTextField(), s.codexCliPath)) return true;
        if (!comboTextEquals(codexModelCombo, s.codexModel)) return true;
        if (!trimmedTextEquals(codexExtraArgsField, s.codexExtraArgs)) return true;
        if (!trimmedTextEquals(ghCliPathField.getTextField(), s.ghCliPath)) return true;
        if (!comboTextEquals(ghModelCombo, s.ghModel)) return true;
        if (!trimmedTextEquals(llmCliPathField.getTextField(), s.llmCliPath)) return true;
        if (!comboTextEquals(llmModelCombo, s.llmModel)) return true;
        if (!trimmedTextEquals(claudeCliPathField.getTextField(), s.claudeCliPath)) return true;
        if (!comboTextEquals(claudeModelCombo, s.claudeModel)) return true;
        if (!trimmedTextEquals(claudeExtraArgsField, s.claudeExtraArgs)) return true;
        if (!trimmedTextEquals(customBinaryField, s.customCliBinary)) return true;
        if (!trimmedTextEquals(customCliPathField.getTextField(), s.customCliPath)) return true;
        if (!trimmedTextEquals(customArgsField, s.customCliArgs)) return true;
        if (!comboTextEquals(customModelCombo, s.customCliModel)) return true;
        if (!trimmedTextEquals(customModelsArgsField, s.customCliModelsArgs)) return true;
        if (conventionalCommitsCheck.isSelected() != s.useConventionalCommits) return true;
        if ((int) maxLengthSpinner.getValue() != s.maxSubjectLength) return true;
        if (!prefixTemplateField.getText().equals(s.prefixTemplate)) return true;
        if (!languageField.getText().equals(s.language)) return true;
        if (autoScopeCheck.isSelected() != s.autoDetectScope) return true;
        if (!extraInstructionsArea.getText().equals(s.extraInstructions)) return true;
        if (!trimmedTextEquals(customRulesFileField.getTextField(), s.customRulesFilePath)) return true;
        if (!trimmedTextEquals(defaultBranchField, s.defaultBranchName)) return true;
        return false;
    }

    private static boolean hasPasswordInput(@NotNull JPasswordField field) {
        return !new String(field.getPassword()).isBlank();
    }

    private static boolean trimmedTextEquals(@NotNull JTextComponent field, @Nullable String expected) {
        return field.getText().trim().equals(expected == null ? "" : expected);
    }

    private static boolean comboTextEquals(@NotNull ComboBox<String> combo, @Nullable String expected) {
        return comboText(combo).equals(expected == null ? "" : expected);
    }

    private static @NotNull String comboText(@NotNull ComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    private static void setComboText(@NotNull ComboBox<String> combo, @Nullable String value) {
        String v = value == null ? "" : value;
        combo.setSelectedItem(v);
        combo.getEditor().setItem(v);
    }

    private static void saveApiKeyIfPresent(
            @NotNull CommitMessageSettings cfg,
            @NotNull CommitMessageProvider.Id provider,
            @NotNull JPasswordField field) {
        String key = new String(field.getPassword());
        if (key.isBlank()) return;
        cfg.saveApiKey(provider, key);
        field.setText("");
        setPasswordPlaceholder(field, "•••••• (saved)");
    }

    @Override
    public void apply() {
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        CommitMessageSettings.State s = cfg.settings();
        CommitMessageProvider.Id selected =
            (CommitMessageProvider.Id) providerCombo.getSelectedItem();
        if (selected != null) s.selectedProvider = selected.name();

        s.openAiModel    = openAiModelField.getText().trim();
        s.anthropicModel = anthropicModelField.getText().trim();
        s.geminiModel    = geminiModelField.getText().trim();
        s.ollamaBaseUrl  = ollamaUrlField.getText().trim();
        s.ollamaModel    = ollamaModelField.getText().trim();
        s.codexCliPath   = codexCliPathField.getText().trim();
        s.codexModel     = comboText(codexModelCombo);
        s.codexExtraArgs = codexExtraArgsField.getText().trim();
        s.ghCliPath      = ghCliPathField.getText().trim();
        s.ghModel        = comboText(ghModelCombo);
        s.llmCliPath     = llmCliPathField.getText().trim();
        s.llmModel       = comboText(llmModelCombo);
        s.claudeCliPath  = claudeCliPathField.getText().trim();
        s.claudeModel    = comboText(claudeModelCombo);
        s.claudeExtraArgs = claudeExtraArgsField.getText().trim();
        s.customCliBinary     = customBinaryField.getText().trim();
        s.customCliPath       = customCliPathField.getText().trim();
        s.customCliArgs       = customArgsField.getText().trim();
        s.customCliModel      = comboText(customModelCombo);
        s.customCliModelsArgs = customModelsArgsField.getText().trim();

        s.useConventionalCommits = conventionalCommitsCheck.isSelected();
        s.maxSubjectLength       = (int) maxLengthSpinner.getValue();
        s.prefixTemplate         = prefixTemplateField.getText();
        s.language               = languageField.getText();
        s.autoDetectScope        = autoScopeCheck.isSelected();
        s.extraInstructions      = extraInstructionsArea.getText();
        s.customRulesFilePath    = customRulesFileField.getText().trim();
        s.defaultBranchName      = defaultBranchField.getText().trim();

        saveApiKeyIfPresent(cfg, CommitMessageProvider.Id.OPENAI, openAiKeyField);
        saveApiKeyIfPresent(cfg, CommitMessageProvider.Id.ANTHROPIC, anthropicKeyField);
        saveApiKeyIfPresent(cfg, CommitMessageProvider.Id.GEMINI, geminiKeyField);
        saveApiKeyIfPresent(cfg, CommitMessageProvider.Id.CODEX_CLI, codexKeyField);
        // Also store in ChatGPTOAuthFlow's dedicated PasswordSafe slot
        String codexManual = new String(codexKeyField.getPassword());
        if (!codexManual.isBlank())
            com.github.prepushchecker.commitgen.ChatGPTOAuthFlow.saveManualKey(codexManual);
        saveApiKeyIfPresent(cfg, CommitMessageProvider.Id.CLAUDE_CLI, claudeKeyField);
    }

    @Override
    public void reset() {
        if (root == null) return;
        CommitMessageSettings cfg = CommitMessageSettings.getInstance();
        CommitMessageSettings.State s = cfg.settings();

        CommitMessageProvider.Id id;
        try {
            id = CommitMessageProvider.Id.valueOf(s.selectedProvider);
        } catch (IllegalArgumentException e) {
            id = CommitMessageProvider.Id.OPENAI;
        }
        providerCombo.setSelectedItem(id);
        showSelectedProviderCard(id);

        openAiModelField.setText(s.openAiModel);
        anthropicModelField.setText(s.anthropicModel);
        geminiModelField.setText(s.geminiModel);
        ollamaUrlField.setText(s.ollamaBaseUrl);
        ollamaModelField.setText(s.ollamaModel);
        codexCliPathField.setText(s.codexCliPath);
        setComboText(codexModelCombo, s.codexModel);
        codexExtraArgsField.setText(s.codexExtraArgs);
        ghCliPathField.setText(s.ghCliPath);
        setComboText(ghModelCombo, s.ghModel);
        llmCliPathField.setText(s.llmCliPath);
        setComboText(llmModelCombo, s.llmModel);
        claudeCliPathField.setText(s.claudeCliPath);
        setComboText(claudeModelCombo, s.claudeModel);
        claudeExtraArgsField.setText(s.claudeExtraArgs);
        customBinaryField.setText(s.customCliBinary);
        customCliPathField.setText(s.customCliPath);
        customArgsField.setText(s.customCliArgs);
        setComboText(customModelCombo, s.customCliModel);
        customModelsArgsField.setText(s.customCliModelsArgs);

        // Never pre-fill key fields — show placeholder text only
        openAiKeyField.setText("");
        anthropicKeyField.setText("");
        geminiKeyField.setText("");
        codexKeyField.setText("");
        claudeKeyField.setText("");

        boolean openAiHasKey    = cfg.loadApiKey(CommitMessageProvider.Id.OPENAI) != null;
        boolean anthropicHasKey = cfg.loadApiKey(CommitMessageProvider.Id.ANTHROPIC) != null;
        boolean geminiHasKey    = cfg.loadApiKey(CommitMessageProvider.Id.GEMINI) != null;
        boolean codexHasKey     = cfg.loadApiKey(CommitMessageProvider.Id.CODEX_CLI) != null;
        boolean claudeCliHasKey = cfg.loadApiKey(CommitMessageProvider.Id.CLAUDE_CLI) != null;

        openAiKeyField.putClientProperty("JPasswordField.cutCopyAllowed", true);
        if (openAiHasKey)    setPasswordPlaceholder(openAiKeyField, "•••••• (saved)");
        if (anthropicHasKey) setPasswordPlaceholder(anthropicKeyField, "•••••• (saved)");
        if (geminiHasKey)    setPasswordPlaceholder(geminiKeyField, "•••••• (saved)");
        if (codexHasKey)     setPasswordPlaceholder(codexKeyField, "•••••• (saved)");
        if (claudeCliHasKey) setPasswordPlaceholder(claudeKeyField, "•••••• (saved)");

        conventionalCommitsCheck.setSelected(s.useConventionalCommits);
        maxLengthSpinner.setValue(s.maxSubjectLength);
        prefixTemplateField.setText(s.prefixTemplate);
        languageField.setText(s.language);
        autoScopeCheck.setSelected(s.autoDetectScope);
        extraInstructionsArea.setText(s.extraInstructions);
        customRulesFileField.setText(s.customRulesFilePath);
        defaultBranchField.setText(s.defaultBranchName);
        updateRulesFileStatus();

        updateIntelliJAiStatus();
    }

    /** Updates the status label below the rules file path field. */
    private void updateRulesFileStatus() {
        if (customRulesFileStatusLabel == null || customRulesFileField == null) return;
        String path = customRulesFileField.getText().trim();
        Project project = getFirstOpenProject();

        if (path.isBlank()) {
            // Show which auto-detected file would be used (if any)
            if (project != null) {
                String found = CommitMessagePromptBuilder.resolvedRuleFileName(project, "");
                if (found != null) {
                    customRulesFileStatusLabel.setText(
                        "<html><i>✓ Auto-detected: <b>" + found + "</b></i></html>");
                    customRulesFileStatusLabel.setForeground(
                        UIManager.getColor("Label.foreground"));
                } else {
                    customRulesFileStatusLabel.setText(
                        "<html><i>No rules file found — will use IDE settings only. "
                            + "Create <code>.github/commit-instructions.md</code> or <code>COMMIT_RULES.md</code>.</i></html>");
                    customRulesFileStatusLabel.setForeground(
                        UIManager.getColor("Label.disabledForeground"));
                }
            } else {
                customRulesFileStatusLabel.setText(
                    "<html><i>Auto-detect: .github/commit-instructions.md → COMMIT_RULES.md</i></html>");
                customRulesFileStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
        } else {
            // Show whether the specified file resolves to something that exists
            java.nio.file.Path resolved = resolveRulesPath(project, path);
            boolean exists = resolved != null && java.nio.file.Files.isRegularFile(resolved);
            if (exists) {
                customRulesFileStatusLabel.setText(
                    "<html><i>✓ Found: <b>" + resolved.toAbsolutePath() + "</b></i></html>");
                customRulesFileStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
            } else {
                customRulesFileStatusLabel.setText(
                    "<html><i>⚠ File not found: " + path + "</i></html>");
                customRulesFileStatusLabel.setForeground(
                    UIManager.getColor("Component.errorFocusColor") != null
                        ? UIManager.getColor("Component.errorFocusColor")
                        : java.awt.Color.RED.darker());
            }
        }
    }

    /**
     * Called after a browse-dialog selection: if the chosen absolute path is
     * inside the project root, store just the relative portion.
     */
    private void normaliseAndUpdateStatus() {
        if (customRulesFileField == null) return;
        String raw = customRulesFileField.getText().trim();
        if (raw.isBlank()) { updateRulesFileStatus(); return; }

        Project project = getFirstOpenProject();
        if (project != null && project.getBasePath() != null) {
            try {
                java.nio.file.Path base = java.nio.file.Path.of(project.getBasePath()).toAbsolutePath();
                java.nio.file.Path file = java.nio.file.Path.of(raw).toAbsolutePath();
                if (file.startsWith(base)) {
                    String relative = base.relativize(file).toString();
                    // Avoid recursion: only setText if it changes
                    if (!relative.equals(raw)) {
                        customRulesFileField.setText(relative);
                        return; // setText fires another document event → updateRulesFileStatus
                    }
                }
            } catch (Exception ignored) {}
        }
        updateRulesFileStatus();
    }

    /** Returns the first open non-default project, or {@code null}. */
    @Nullable
    private static Project getFirstOpenProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        for (Project p : projects) {
            if (!p.isDefault() && p.getBasePath() != null) return p;
        }
        return null;
    }

    /**
     * Resolves {@code path} against the project base (if relative) or as-is
     * (if absolute). Returns {@code null} when neither succeeds.
     */
    @Nullable
    private static java.nio.file.Path resolveRulesPath(@Nullable Project project, @NotNull String path) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(path);
            if (p.isAbsolute()) return p;
            if (project != null && project.getBasePath() != null) {
                return java.nio.file.Path.of(project.getBasePath()).resolve(p);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void setPasswordPlaceholder(@NotNull JPasswordField field, @NotNull String hint) {
        field.setToolTipText(hint);
    }

    private void onTestConnection() {
        CommitMessageProvider.Id selected =
            (CommitMessageProvider.Id) providerCombo.getSelectedItem();
        if (selected == null) return;

        // Persist current values first so the provider can read them
        apply();

        new Thread(() -> {
            try {
                com.github.prepushchecker.commitgen.CommitMessageProvider provider =
                    buildProvider(selected);
                String result = provider.generate(
                    "You are a test. Reply with exactly one word: OK",
                    "Test prompt.");
                SwingUtilities.invokeLater(() ->
                    Messages.showInfoMessage(
                        "Connection successful!\n\nResponse: " + result.trim(),
                        "Test Connection"));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                    Messages.showErrorDialog(
                        ex.getMessage() != null ? ex.getMessage() : ex.toString(),
                        "Connection Failed"));
            }
        }, "CommitMsgGen-TestConnection").start();
    }

    private static com.github.prepushchecker.commitgen.CommitMessageProvider buildProvider(
            @NotNull CommitMessageProvider.Id id) {
        return switch (id) {
            case INTELLIJ_AI -> new com.github.prepushchecker.commitgen.providers.IntelliJAiProvider();
            case OPENAI      -> new com.github.prepushchecker.commitgen.providers.OpenAiProvider();
            case ANTHROPIC   -> new com.github.prepushchecker.commitgen.providers.AnthropicProvider();
            case GEMINI      -> new com.github.prepushchecker.commitgen.providers.GeminiProvider();
            case OLLAMA      -> new com.github.prepushchecker.commitgen.providers.OllamaProvider();
            case CODEX_CLI   -> new com.github.prepushchecker.commitgen.providers.CodexCliProvider();
            case GH_COPILOT  -> new com.github.prepushchecker.commitgen.providers.GhCopilotProvider();
            case LLM_CLI     -> new com.github.prepushchecker.commitgen.providers.LlmCliProvider();
            case CLAUDE_CLI  -> new com.github.prepushchecker.commitgen.providers.ClaudeCliProvider();
            case CUSTOM_CLI  -> new com.github.prepushchecker.commitgen.providers.CustomCliProvider();
        };
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }
}
