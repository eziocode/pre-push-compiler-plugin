package com.github.prepushchecker.commitgen;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
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
import java.awt.*;
import java.nio.file.Path;

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
    private JBTextField     codexExtraArgsField;
    private JBTextField     ghCliPathField;
    private JBLabel         ghStatusLabel;
    private JBTextField     llmCliPathField;
    private JBTextField     llmModelField;
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
        authPanel.add(buildGhCopilotCard(),  CommitMessageProvider.Id.GH_COPILOT.name());
        authPanel.add(buildLlmCliCard(),     CommitMessageProvider.Id.LLM_CLI.name());
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
        codexCliPathField = new JBTextField(30);
        codexCliPathField.getEmptyText().setText("blank = auto-detect from PATH");
        codexExtraArgsField = new JBTextField(30);
        codexExtraArgsField.getEmptyText().setText(
            "blank = --approval-policy full-auto  (default for Rust CLI 2025+)");

        JButton detectBtn = new JButton("Auto-detect");
        detectBtn.addActionListener(ev -> {
            String found = com.github.prepushchecker.commitgen.CliPathResolver.whichViaShell("codex");
            if (found != null) {
                codexCliPathField.setText(found);
            } else {
                codexCliPathField.setText("");
                Messages.showInfoMessage(
                    "codex not found on PATH.\n\n"
                        + "Install: npm install -g @openai/codex\n"
                        + "Auth:    codex auth   (ChatGPT account OAuth)",
                    "Codex CLI Not Found");
            }
        });

        JPanel card = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; card.add(new JBLabel("CLI path:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(codexCliPathField, gbc);
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        card.add(detectBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        card.add(new JBLabel("Extra args:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(codexExtraArgsField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JBLabel hint = new JBLabel("<html><i>"
            + "Rust CLI (2025+): leave blank → uses <code>--approval-policy full-auto</code><br>"
            + "Node CLI (older): try <code>--full-auto</code> or leave blank<br>"
            + "Auth: <code>codex auth</code> (ChatGPT) or set <code>OPENAI_API_KEY</code>"
            + "</i></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        card.add(hint, gbc);
        return card;
    }

    private @NotNull JPanel buildGhCopilotCard() {
        ghCliPathField  = new JBTextField(30);
        ghCliPathField.getEmptyText().setText("blank = auto-detect from PATH");
        ghStatusLabel   = new JBLabel(" ");

        JButton detectBtn = new JButton("Auto-detect gh");
        detectBtn.addActionListener(ev -> {
            String found = com.github.prepushchecker.commitgen.CliPathResolver.whichViaShell("gh");
            if (found != null) {
                ghCliPathField.setText(found);
                // Check auth status
                String token = com.github.prepushchecker.commitgen.providers.GhCopilotProvider
                    .getGhToken(found);
                ghStatusLabel.setText(token != null
                    ? "✓ Signed in to GitHub CLI"
                    : "✗ Not signed in — run: gh auth login");
            } else {
                ghCliPathField.setText("");
                ghStatusLabel.setText("✗ gh not found — install from cli.github.com");
            }
        });

        JPanel card = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; card.add(new JBLabel("gh CLI path:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(ghCliPathField, gbc);
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        card.add(detectBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
        card.add(ghStatusLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3;
        JBLabel hint = new JBLabel("<html><i>"
            + "Requires GitHub CLI + active Copilot subscription. "
            + "Auth: <code>gh auth login</code> &nbsp;|&nbsp; "
            + "Scope: <code>gh auth refresh -s copilot</code>"
            + "</i></html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        card.add(hint, gbc);
        return card;
    }

    private @NotNull JPanel buildLlmCliCard() {
        llmCliPathField = new JBTextField(30);
        llmCliPathField.getEmptyText().setText("blank = auto-detect from PATH");
        llmModelField   = new JBTextField(20);
        llmModelField.getEmptyText().setText("e.g. gpt-4o, claude-3-5-sonnet, gemini-1.5-flash");

        JButton detectBtn = new JButton("Auto-detect");
        detectBtn.addActionListener(ev -> {
            String found = com.github.prepushchecker.commitgen.CliPathResolver.whichViaShell("llm");
            if (found != null) {
                llmCliPathField.setText(found);
            } else {
                llmCliPathField.setText("");
                Messages.showInfoMessage(
                    "llm not found on PATH.\n\n"
                        + "Install:   pip install llm\n"
                        + "Docs:      https://llm.datasette.io\n"
                        + "Add keys:  llm keys set openai",
                    "llm CLI Not Found");
            }
        });

        JPanel card = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 0, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; card.add(new JBLabel("CLI path:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(llmCliPathField, gbc);
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        card.add(detectBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        card.add(new JBLabel("Model:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        card.add(llmModelField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3;
        JBLabel hint = new JBLabel("<html><i>"
            + "Supports 60+ models via plugins. "
            + "Install: <code>pip install llm</code> &nbsp;|&nbsp; "
            + "Add provider: <code>llm install llm-anthropic</code> &nbsp;|&nbsp; "
            + "List models: <code>llm models</code>"
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
        customRulesFileField.addBrowseFolderListener(
            "Select Commit Rules File",
            "Choose a Markdown (.md) file containing commit message generation rules",
            getFirstOpenProject(),
            fileDesc,
            com.intellij.openapi.ui.TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT);

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
        if (!codexExtraArgsField.getText().equals(s.codexExtraArgs)) return true;
        if (!ghCliPathField.getText().equals(s.ghCliPath)) return true;
        if (!llmCliPathField.getText().equals(s.llmCliPath)) return true;
        if (!llmModelField.getText().equals(s.llmModel)) return true;
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
        s.codexExtraArgs    = codexExtraArgsField.getText().trim();
        s.ghCliPath      = ghCliPathField.getText().trim();
        s.llmCliPath     = llmCliPathField.getText().trim();
        s.llmModel       = llmModelField.getText().trim();

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
        codexExtraArgsField.setText(s.codexExtraArgs);
        ghCliPathField.setText(s.ghCliPath);
        llmCliPathField.setText(s.llmCliPath);
        llmModelField.setText(s.llmModel);

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
        };
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }
}
