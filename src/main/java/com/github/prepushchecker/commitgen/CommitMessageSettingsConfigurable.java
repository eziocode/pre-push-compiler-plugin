package com.github.prepushchecker.commitgen;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

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
    private JBTextField     codexCliPathField;
    private JBLabel         intellijAiStatusLabel;

    // Rules
    private JBCheckBox conventionalCommitsCheck;
    private JSpinner   maxLengthSpinner;
    private JBTextField prefixTemplateField;
    private JBTextField languageField;
    private JBCheckBox autoScopeCheck;
    private JBTextArea extraInstructionsArea;
    private JBTextField customRulesFileField;
    private JBLabel     customRulesFileStatusLabel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "AI Commit Message Generator";
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
        panel.add(selectorRow, BorderLayout.NORTH);

        // Auth card panel (switches when provider changes)
        authCardLayout = new CardLayout();
        authPanel = new JPanel(authCardLayout);
        authPanel.add(buildOpenAiCard(),     CommitMessageProvider.Id.OPENAI.name());
        authPanel.add(buildAnthropicCard(),  CommitMessageProvider.Id.ANTHROPIC.name());
        authPanel.add(buildGeminiCard(),     CommitMessageProvider.Id.GEMINI.name());
        authPanel.add(buildOllamaCard(),     CommitMessageProvider.Id.OLLAMA.name());
        authPanel.add(buildCodexCliCard(),   CommitMessageProvider.Id.CODEX_CLI.name());
        authPanel.add(buildIntelliJAiCard(), CommitMessageProvider.Id.INTELLIJ_AI.name());
        panel.add(authPanel, BorderLayout.CENTER);

        providerCombo.addActionListener(ev -> {
            CommitMessageProvider.Id selected =
                (CommitMessageProvider.Id) providerCombo.getSelectedItem();
            if (selected != null) {
                authCardLayout.show(authPanel, selected.name());
            }
        });
        return panel;
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

    private @NotNull JPanel buildCodexCliCard() {
        codexCliPathField = new JBTextField("codex", 30);
        JPanel card = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; card.add(new JBLabel("Codex CLI path:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(codexCliPathField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JBLabel hint = new JBLabel("<html><i>"
            + "Install: npm install -g @openai/codex &nbsp;|&nbsp; "
            + "Auth: codex auth (ChatGPT account or OPENAI_API_KEY env var)"
            + "</i></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        card.add(hint, gbc);
        return card;
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
            com.intellij.ide.plugins.IdeaPluginDescriptor desc =
                com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId.getId("com.intellij.ml.llm"));
            if (desc != null && desc.isEnabled()) {
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

        customRulesFileField = new JBTextField(40);
        customRulesFileField.getEmptyText().setText(
            "Leave blank to auto-detect (.github/commit-instructions.md or COMMIT_RULES.md)");
        customRulesFileStatusLabel = new JBLabel();

        // Wire a document listener to update the status label as the user types
        customRulesFileField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { updateRulesFileStatus(); }
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
        if (!openAiModelField.getText().equals(s.openAiModel)) return true;
        if (!anthropicModelField.getText().equals(s.anthropicModel)) return true;
        if (!geminiModelField.getText().equals(s.geminiModel)) return true;
        if (!ollamaUrlField.getText().equals(s.ollamaBaseUrl)) return true;
        if (!ollamaModelField.getText().equals(s.ollamaModel)) return true;
        if (!codexCliPathField.getText().equals(s.codexCliPath)) return true;
        if (conventionalCommitsCheck.isSelected() != s.useConventionalCommits) return true;
        if ((int) maxLengthSpinner.getValue() != s.maxSubjectLength) return true;
        if (!prefixTemplateField.getText().equals(s.prefixTemplate)) return true;
        if (!languageField.getText().equals(s.language)) return true;
        if (autoScopeCheck.isSelected() != s.autoDetectScope) return true;
        if (!extraInstructionsArea.getText().equals(s.extraInstructions)) return true;
        if (!customRulesFileField.getText().equals(s.customRulesFilePath)) return true;
        return false;
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

        s.useConventionalCommits = conventionalCommitsCheck.isSelected();
        s.maxSubjectLength       = (int) maxLengthSpinner.getValue();
        s.prefixTemplate         = prefixTemplateField.getText();
        s.language               = languageField.getText();
        s.autoDetectScope        = autoScopeCheck.isSelected();
        s.extraInstructions      = extraInstructionsArea.getText();
        s.customRulesFilePath    = customRulesFileField.getText().trim();

        // Persist API keys to PasswordSafe
        String openAiKey = new String(openAiKeyField.getPassword());
        if (!openAiKey.isBlank()) cfg.saveApiKey(CommitMessageProvider.Id.OPENAI, openAiKey);

        String anthropicKey = new String(anthropicKeyField.getPassword());
        if (!anthropicKey.isBlank()) cfg.saveApiKey(CommitMessageProvider.Id.ANTHROPIC, anthropicKey);

        String geminiKey = new String(geminiKeyField.getPassword());
        if (!geminiKey.isBlank()) cfg.saveApiKey(CommitMessageProvider.Id.GEMINI, geminiKey);
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
        authCardLayout.show(authPanel, id.name());

        openAiModelField.setText(s.openAiModel);
        anthropicModelField.setText(s.anthropicModel);
        geminiModelField.setText(s.geminiModel);
        ollamaUrlField.setText(s.ollamaBaseUrl);
        ollamaModelField.setText(s.ollamaModel);
        codexCliPathField.setText(s.codexCliPath);

        // Never pre-fill key fields — show placeholder text only
        openAiKeyField.setText("");
        anthropicKeyField.setText("");
        geminiKeyField.setText("");

        boolean openAiHasKey    = cfg.loadApiKey(CommitMessageProvider.Id.OPENAI) != null;
        boolean anthropicHasKey = cfg.loadApiKey(CommitMessageProvider.Id.ANTHROPIC) != null;
        boolean geminiHasKey    = cfg.loadApiKey(CommitMessageProvider.Id.GEMINI) != null;

        openAiKeyField.putClientProperty("JPasswordField.cutCopyAllowed", true);
        if (openAiHasKey)    setPasswordPlaceholder(openAiKeyField, "•••••• (saved)");
        if (anthropicHasKey) setPasswordPlaceholder(anthropicKeyField, "•••••• (saved)");
        if (geminiHasKey)    setPasswordPlaceholder(geminiKeyField, "•••••• (saved)");

        conventionalCommitsCheck.setSelected(s.useConventionalCommits);
        maxLengthSpinner.setValue(s.maxSubjectLength);
        prefixTemplateField.setText(s.prefixTemplate);
        languageField.setText(s.language);
        autoScopeCheck.setSelected(s.autoDetectScope);
        extraInstructionsArea.setText(s.extraInstructions);
        customRulesFileField.setText(s.customRulesFilePath);
        updateRulesFileStatus();

        updateIntelliJAiStatus();
    }

    /** Updates the status label below the rules file path field. */
    private void updateRulesFileStatus() {
        if (customRulesFileStatusLabel == null) return;
        // We don't have a project reference in the configurable, so show a
        // generic note about where files are resolved from.
        String path = customRulesFileField.getText().trim();
        if (path.isBlank()) {
            customRulesFileStatusLabel.setText(
                "<html><i>Auto-detect: searches .github/commit-instructions.md, then COMMIT_RULES.md in project root.</i></html>");
        } else {
            customRulesFileStatusLabel.setText(
                "<html><i>Will read: &lt;project-root&gt;/" + path + "</i></html>");
        }
        customRulesFileStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
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
        };
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }
}
