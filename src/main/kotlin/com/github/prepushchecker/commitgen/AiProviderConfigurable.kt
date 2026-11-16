package com.github.prepushchecker.commitgen

import com.github.prepushchecker.commitgen.CommitMessageProvider.Id
import com.github.prepushchecker.commitgen.cli.AiCli
import com.github.prepushchecker.commitgen.cli.AiCliDetector
import com.github.prepushchecker.commitgen.cli.AiCliModels
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowsRange
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * Settings → Tools → Pre-Push Checker → AI Provider: which AI the commit message generator and
 * the code review use. Pick a source (API key, local CLI, JetBrains AI); for a CLI, one
 * "Choose CLI…" button detects the installed CLIs, and the details sit under Advanced.
 */
class AiProviderConfigurable : SearchableConfigurable {

    companion object {
        /** Kept from the pre-2.10 page so existing links to it still open this page. */
        const val ID = "com.github.prepushchecker.commitgen.CommitMessageSettingsConfigurable"
        private val API_IDS = listOf(Id.OPENAI, Id.ANTHROPIC, Id.GEMINI, Id.OLLAMA)
        private val CLI_IDS = setOf(Id.CODEX_CLI, Id.CLAUDE_CLI, Id.GH_COPILOT, Id.LLM_CLI, Id.CUSTOM_CLI)

        private fun apiName(id: Id) = when (id) {
            Id.OPENAI -> "OpenAI"
            Id.ANTHROPIC -> "Anthropic"
            Id.GEMINI -> "Google Gemini"
            Id.OLLAMA -> "Ollama (local)"
            else -> id.displayName()
        }

        private fun cliOf(id: Id, binary: String?): AiCli = when (id) {
            Id.CODEX_CLI -> AiCli.CODEX
            Id.CLAUDE_CLI -> AiCli.CLAUDE
            Id.GH_COPILOT -> AiCli.GH
            Id.LLM_CLI -> AiCli.LLM
            else -> AiCli.byBinary(binary) ?: AiCli.SAHAA
        }
    }

    private enum class Source { API, CLI, IDE }

    private val saved get() = CommitMessageSettings.getInstance().settings()
    private var work = CommitMessageSettings.State()
    private var source = Source.API
    private var apiId = Id.OPENAI
    private var cliId = Id.CODEX_CLI
    private val typedApiKeys = mutableMapOf<Id, String>()
    private val typedCliKeys = mutableMapOf<Id, String>()

    // Source
    private val apiRadio = JBRadioButton("API key")
    private val cliRadio = JBRadioButton("Local CLI")
    private val ideRadio = JBRadioButton("JetBrains AI")

    // API
    private val apiCombo = ComboBox(DefaultComboBoxModel(API_IDS.toTypedArray()))
    private val apiKeyField = JBPasswordField()
    private val ollamaUrlField = JBTextField()
    private val apiModelField = JBTextField()

    // CLI
    private val cliSummary = JBLabel()
    private val cliModelCombo = ComboBox<String>().apply { isEditable = true }
    private val cliPathField = TextFieldWithBrowseButton(JBTextField().apply { emptyText.text = "blank = find on PATH" }).apply {
        addBrowseFolderListener(TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select CLI Executable")))
    }
    private val customBinaryField = JBTextField()
    private val extraArgsField = JBTextField()
    private val templateField = JBTextField()
    private val listModelsArgsField = JBTextField()
    private val cliKeyField = JBPasswordField()
    private val codexStatus = JBLabel()
    private val modelsStatus = JBLabel()

    // JetBrains AI
    private val ideStatus = JBLabel()

    private val testResult = JBLabel()

    private var apiRange: RowsRange? = null
    private var cliRange: RowsRange? = null
    private var ideRange: RowsRange? = null
    private var keyRow: Row? = null
    private var urlRow: Row? = null
    private var binaryRow: Row? = null
    private var extraArgsRow: Row? = null
    private var templateRow: Row? = null
    private var listArgsRow: Row? = null
    private var cliKeyRow: Row? = null
    private var codexRow: Row? = null
    private var component: JComponent? = null
    private var loading = false
    @Volatile private var pendingKeySave: java.util.concurrent.Future<*>? = null

    override fun getId() = ID

    override fun getDisplayName() = "AI Provider"

    override fun createComponent(): JComponent {
        listOf(apiRadio to Source.API, cliRadio to Source.CLI, ideRadio to Source.IDE).forEach { (radio, s) ->
            radio.addActionListener { if (radio.isSelected && !loading) { source = s; updateVisibility() } }
        }
        apiCombo.renderer = SimpleListCellRenderer.create("") { apiName(it) }
        apiCombo.addActionListener {
            if (loading) return@addActionListener
            stashApi()
            apiId = apiCombo.selectedItem as Id
            loadApi()
            updateVisibility()
        }
        customBinaryField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                if (!loading) templateField.emptyText.text = presetHint()
            }
        })

        val p = panel {
            buttonsGroup {
                row("Source:") {
                    cell(apiRadio)
                    cell(cliRadio)
                    cell(ideRadio)
                }.bottomGap(com.intellij.ui.dsl.builder.BottomGap.SMALL)
            }

            apiRange = rowsRange {
                row("Provider:") { cell(apiCombo) }
                keyRow = row("API key:") {
                    cell(apiKeyField).columns(COLUMNS_MEDIUM)
                        .comment("Stored in the IDE password safe. Leave blank to keep the saved key.")
                }
                urlRow = row("Base URL:") { cell(ollamaUrlField).columns(COLUMNS_MEDIUM) }
                row("Model:") { cell(apiModelField).columns(COLUMNS_MEDIUM) }
            }

            cliRange = rowsRange {
                row("CLI:") {
                    cell(cliSummary)
                    button("Choose CLI…") { chooseCli() }
                }
                row("Model:") {
                    cell(cliModelCombo).columns(COLUMNS_MEDIUM)
                    link("Refresh models") { refreshModels() }
                    cell(modelsStatus)
                }
                collapsibleGroup("Advanced") { advancedCliRows(this) }
            }

            ideRange = rowsRange {
                row { cell(ideStatus) }
                row { comment("Needs the JetBrains AI Assistant plugin and a signed-in JetBrains account.") }
            }

            separator().topGap(com.intellij.ui.dsl.builder.TopGap.MEDIUM)
            row {
                link("Test connection") { testConnection() }
                cell(testResult)
            }
        }
        component = p
        reset()
        return p
    }

    private fun advancedCliRows(panel: Panel) = with(panel) {
        binaryRow = row("Binary name:") {
            cell(customBinaryField).columns(COLUMNS_MEDIUM)
                .comment("Command name found on PATH, e.g. sahaa, gemini, cursor-agent, copilot, opencode.")
        }
        row("Path:") {
            cell(cliPathField).columns(COLUMNS_LARGE)
        }
        extraArgsRow = row("Extra arguments:") {
            cell(extraArgsField).columns(COLUMNS_LARGE)
                .comment("Added to the command the plugin builds. Usually empty.")
        }
        templateRow = row("Arguments:") {
            cell(templateField).columns(COLUMNS_LARGE)
                .comment("Placeholders: {prompt}, {model}, {model?…}. Blank uses the preset shown. Without {prompt} the prompt goes on stdin.")
        }
        listArgsRow = row("List models arguments:") {
            cell(listModelsArgsField).columns(COLUMNS_MEDIUM)
                .comment("Arguments that print one model per line. Optional.")
        }
        cliKeyRow = row("Fallback API key:") {
            cell(cliKeyField).columns(COLUMNS_MEDIUM)
                .comment("Used only when the CLI is not signed in. Leave blank to keep the saved key.")
        }
        codexRow = row("ChatGPT account:") {
            cell(codexStatus)
            link("Sign in") { codexSignIn() }
            link("Sign out") {
                ChatGPTOAuthFlow.signOut()
                refreshCodexStatus()
            }
        }
    }

    // ── Load / store ──────────────────────────────────────────────────────────

    override fun reset() {
        work = XmlSerializerUtil.createCopy(saved)
        typedApiKeys.clear()
        typedCliKeys.clear()
        val selected = runCatching { Id.valueOf(work.selectedProvider) }.getOrDefault(Id.OPENAI)
        source = when (selected) {
            Id.INTELLIJ_AI -> Source.IDE
            in CLI_IDS -> Source.CLI
            else -> Source.API
        }
        apiId = if (selected in API_IDS) selected else Id.OPENAI
        cliId = if (selected in CLI_IDS) selected else Id.CODEX_CLI
        loading = true
        try {
            apiRadio.isSelected = source == Source.API
            cliRadio.isSelected = source == Source.CLI
            ideRadio.isSelected = source == Source.IDE
            apiCombo.selectedItem = apiId
            loadApi()
            loadCli()
        } finally {
            loading = false
        }
        testResult.text = ""
        updateVisibility()
        refreshIdeStatus()
    }

    override fun isModified(): Boolean {
        val copy = XmlSerializerUtil.createCopy(work)
        writeUi(copy)
        return fingerprint(copy) != fingerprint(saved)
            || typedApiKeys.values.any { it.isNotBlank() } || typedCliKeys.values.any { it.isNotBlank() }
            || String(apiKeyField.password).isNotBlank() || String(cliKeyField.password).isNotBlank()
    }

    override fun apply() {
        writeUi(work)
        stashKeys()
        XmlSerializerUtil.copyBean(work, saved)
        val apiKeys = typedApiKeys.filterValues { it.isNotBlank() }
        val cliKeys = typedCliKeys.filterValues { it.isNotBlank() }
        typedApiKeys.clear()
        typedCliKeys.clear()
        apiKeyField.text = ""
        cliKeyField.text = ""
        if (apiKeys.isEmpty() && cliKeys.isEmpty()) return
        // Password safe writes are slow (keychain): off the UI thread.
        pendingKeySave = ApplicationManager.getApplication().executeOnPooledThread {
            val settings = CommitMessageSettings.getInstance()
            apiKeys.forEach { (id, key) -> settings.saveApiKey(id, key) }
            cliKeys.forEach { (id, key) ->
                settings.saveApiKey(id, key)
                if (id == Id.CODEX_CLI) ChatGPTOAuthFlow.saveManualKey(key)
            }
            ApplicationManager.getApplication().invokeLater({ updateKeyHints() }, ModalityState.any())
        }
    }

    override fun disposeUIResources() {
        component = null
    }

    private fun writeUi(s: CommitMessageSettings.State) {
        stashApiInto(s)
        stashCliInto(s)
        s.selectedProvider = when (source) {
            Source.API -> apiId
            Source.CLI -> cliId
            Source.IDE -> Id.INTELLIJ_AI
        }.name
    }

    private fun fingerprint(s: CommitMessageSettings.State): List<Any?> = listOf(
        s.selectedProvider, s.openAiModel, s.anthropicModel, s.geminiModel, s.ollamaModel, s.ollamaBaseUrl,
        s.codexCliPath, s.codexModel, s.codexExtraArgs, s.ghCliPath, s.ghModel, s.llmCliPath, s.llmModel,
        s.claudeCliPath, s.claudeModel, s.claudeExtraArgs, s.customCliBinary, s.customCliPath, s.customCliArgs,
        s.customCliModel, s.customCliModelsArgs)

    private fun stashApi() = stashApiInto(work)

    private fun stashApiInto(s: CommitMessageSettings.State) {
        val model = apiModelField.text.trim()
        when (apiId) {
            Id.OPENAI -> s.openAiModel = model
            Id.ANTHROPIC -> s.anthropicModel = model
            Id.GEMINI -> s.geminiModel = model
            Id.OLLAMA -> { s.ollamaModel = model; s.ollamaBaseUrl = ollamaUrlField.text.trim() }
            else -> {}
        }
        if (s === work) stashKeys()
    }

    private fun stashKeys() {
        val apiKey = String(apiKeyField.password)
        if (apiKey.isNotBlank()) typedApiKeys[apiId] = apiKey
        val cliKey = String(cliKeyField.password)
        if (cliKey.isNotBlank()) typedCliKeys[cliId] = cliKey
    }

    private fun loadApi() {
        apiModelField.text = when (apiId) {
            Id.OPENAI -> work.openAiModel
            Id.ANTHROPIC -> work.anthropicModel
            Id.GEMINI -> work.geminiModel
            Id.OLLAMA -> work.ollamaModel
            else -> ""
        }
        ollamaUrlField.text = work.ollamaBaseUrl
        apiKeyField.text = typedApiKeys[apiId].orEmpty()
        updateKeyHints()
    }

    private fun stashCliInto(s: CommitMessageSettings.State) {
        val path = cliPathField.text.trim()
        val model = (cliModelCombo.editor.item as? String ?: cliModelCombo.selectedItem as? String ?: "").trim()
        when (cliId) {
            Id.CODEX_CLI -> { s.codexCliPath = path; s.codexModel = model; s.codexExtraArgs = extraArgsField.text.trim() }
            Id.CLAUDE_CLI -> { s.claudeCliPath = path; s.claudeModel = model; s.claudeExtraArgs = extraArgsField.text.trim() }
            Id.GH_COPILOT -> { s.ghCliPath = path; s.ghModel = model }
            Id.LLM_CLI -> { s.llmCliPath = path; s.llmModel = model }
            Id.CUSTOM_CLI -> {
                s.customCliBinary = customBinaryField.text.trim()
                s.customCliPath = path
                s.customCliModel = model
                s.customCliArgs = templateField.text.trim()
                s.customCliModelsArgs = listModelsArgsField.text.trim()
            }
            else -> {}
        }
    }

    private fun loadCli() {
        val wasLoading = loading
        loading = true
        try {
            val (path, model, extra) = when (cliId) {
                Id.CODEX_CLI -> Triple(work.codexCliPath, work.codexModel, work.codexExtraArgs)
                Id.CLAUDE_CLI -> Triple(work.claudeCliPath, work.claudeModel, work.claudeExtraArgs)
                Id.GH_COPILOT -> Triple(work.ghCliPath, work.ghModel, "")
                Id.LLM_CLI -> Triple(work.llmCliPath, work.llmModel, "")
                else -> Triple(work.customCliPath, work.customCliModel, "")
            }
            cliPathField.text = path.orEmpty()
            extraArgsField.text = extra.orEmpty()
            customBinaryField.text = work.customCliBinary.orEmpty()
            templateField.text = work.customCliArgs.orEmpty()
            templateField.emptyText.text = presetHint()
            listModelsArgsField.text = work.customCliModelsArgs.orEmpty()
            val cli = cliOf(cliId, work.customCliBinary)
            cliModelCombo.model = DefaultComboBoxModel(AiCliModels.staticModels(cli).toTypedArray())
            cliModelCombo.editor.item = model.orEmpty()
            cliModelCombo.selectedItem = model.orEmpty()
            cliKeyField.text = typedCliKeys[cliId].orEmpty()
            modelsStatus.text = ""
        } finally {
            loading = wasLoading
        }
        updateKeyHints()
        refreshCliSummary()
        if (cliId == Id.CODEX_CLI) refreshCodexStatus()
    }

    private fun presetHint(): String {
        val preset = AiCli.byBinary(customBinaryField.text.trim())?.takeIf { it.isCustom }?.defaultArgsTemplate()
        return if (preset.isNullOrBlank()) "-p {prompt}" else preset
    }

    /** Password safe reads are slow (keychain): done off the UI thread, then the hints update. */
    private fun updateKeyHints() {
        val api = apiId
        val cli = cliId
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = CommitMessageSettings.getInstance()
            val apiSaved = api != Id.OLLAMA && !settings.loadApiKey(api).isNullOrBlank()
            val cliSaved = cli in setOf(Id.CODEX_CLI, Id.CLAUDE_CLI) && !settings.loadApiKey(cli).isNullOrBlank()
            ApplicationManager.getApplication().invokeLater({
                if (apiId == api) apiKeyField.emptyText.text = if (apiSaved) "saved (type to replace)" else "not set"
                if (cliId == cli) cliKeyField.emptyText.text = if (cliSaved) "saved (type to replace)" else "optional"
                apiKeyField.repaint()
                cliKeyField.repaint()
            }, ModalityState.any())
        }
    }

    private fun updateVisibility() {
        apiRange?.visible(source == Source.API)
        cliRange?.visible(source == Source.CLI)
        ideRange?.visible(source == Source.IDE)
        if (source == Source.API) {
            keyRow?.visible(apiId != Id.OLLAMA)
            urlRow?.visible(apiId == Id.OLLAMA)
        }
        if (source == Source.CLI) {
            val custom = cliId == Id.CUSTOM_CLI
            binaryRow?.visible(custom)
            templateRow?.visible(custom)
            listArgsRow?.visible(custom)
            extraArgsRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            cliKeyRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            codexRow?.visible(cliId == Id.CODEX_CLI)
        }
    }

    // ── CLI: choose, summary, models, sign-in ─────────────────────────────────

    private fun chooseCli() {
        stashCliInto(work)
        val scan = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<AiCliDetector.DetectedCli>, Exception>(
            { AiCliDetector.scanAll(work) }, "Detecting installed AI CLIs…", true, null) ?: return
        val dialog = ChooseCliDialog(scan)
        if (!dialog.showAndGet()) return
        val chosenPath = dialog.browsedPath
        val chosen = dialog.selected()
        when {
            chosenPath != null -> {
                val name = java.nio.file.Path.of(chosenPath).fileName.toString()
                val known = AiCli.byBinary(name)
                if (known != null && !known.isCustom) {
                    useCli(known, chosenPath)
                } else {
                    cliId = Id.CUSTOM_CLI
                    if (work.customCliBinary != name) work.customCliArgs = ""
                    work.customCliBinary = name
                    work.customCliPath = chosenPath
                    loadCli()
                }
            }
            chosen != null && chosen.found() -> useCli(chosen.cli(), chosen.path() ?: "")
            else -> return
        }
        updateVisibility()
    }

    private fun useCli(cli: AiCli, path: String) {
        when (cli) {
            AiCli.CODEX -> work.codexCliPath = path
            AiCli.CLAUDE -> work.claudeCliPath = path
            AiCli.GH -> work.ghCliPath = path
            AiCli.LLM -> work.llmCliPath = path
            else -> {
                if (work.customCliBinary != cli.binaryName()) work.customCliArgs = ""
                work.customCliBinary = cli.binaryName()
                work.customCliPath = path
            }
        }
        cliId = cli.providerId()
        loadCli()
    }

    private fun refreshCliSummary() {
        val cli = cliOf(cliId, customBinaryField.text.trim())
        val binary = if (cliId == Id.CUSTOM_CLI) customBinaryField.text.trim().ifEmpty { "sahaa" } else cli.binaryName()
        val name = if (cliId == Id.CUSTOM_CLI) AiCli.byBinary(binary)?.displayName() ?: binary else cli.displayName()
        val configured = cliPathField.text.trim()
        cliSummary.text = "$name  ·  checking…"
        cliSummary.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = CliPathResolver.resolveOrNull(configured, binary)
            val version = if (path == null) "" else AiCliDetector.version(path)
            ApplicationManager.getApplication().invokeLater({
                if (path == null) {
                    cliSummary.text = "$name  ·  not found. Use Choose CLI… or set the path under Advanced."
                    cliSummary.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                } else {
                    cliSummary.text = "$name  ·  $path" + if (version.isBlank()) "" else "  ·  $version"
                    cliSummary.foreground = UIUtil.getLabelForeground()
                }
            }, ModalityState.any())
        }
    }

    private fun refreshModels() {
        val cli = cliOf(cliId, customBinaryField.text.trim())
        val binary = if (cliId == Id.CUSTOM_CLI) customBinaryField.text.trim().ifEmpty { "sahaa" } else cli.binaryName()
        val configured = cliPathField.text.trim()
        val listArgs = if (cliId == Id.CUSTOM_CLI) listModelsArgsField.text.trim() else null
        val models = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, Exception>(
            { AiCliModels.list(cli, CliPathResolver.resolveOrNull(configured, binary), listArgs) },
            "Fetching models…", true, null)
        val current = (cliModelCombo.editor.item as? String).orEmpty()
        if (models.isNullOrEmpty()) {
            modelsStatus.text = "No models reported; type a name."
            return
        }
        cliModelCombo.model = DefaultComboBoxModel(models.toTypedArray())
        cliModelCombo.editor.item = current
        cliModelCombo.selectedItem = current
        modelsStatus.text = "${models.size} models"
    }

    private fun refreshCodexStatus() {
        codexStatus.text = "checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = ChatGPTOAuthFlow.getStatusText()
            ApplicationManager.getApplication().invokeLater({
                codexStatus.text = text ?: "not signed in"
                codexStatus.foreground = if (text == null) JBColor.namedColor("Label.errorForeground", JBColor.RED)
                else UIUtil.getLabelForeground()
            }, ModalityState.any())
        }
    }

    private fun codexSignIn() {
        stashCliInto(work)
        saved.codexCliPath = work.codexCliPath // the login runs the configured binary
        codexStatus.text = "opening a terminal…"
        ChatGPTOAuthFlow.startSignIn { refreshCodexStatus() }
    }

    private fun refreshIdeStatus() {
        val id = PluginId.getId("com.intellij.ml.llm")
        val ok = runCatching { PluginManagerCore.isPluginInstalled(id) && !PluginManagerCore.isDisabled(id) }.getOrDefault(false)
        ideStatus.text = if (ok) "JetBrains AI Assistant is installed and enabled."
        else "JetBrains AI Assistant is not installed or is disabled."
        ideStatus.foreground = if (ok) UIUtil.getLabelForeground() else JBColor.namedColor("Label.errorForeground", JBColor.RED)
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    private fun testConnection() {
        if (isModified) apply()
        val id = runCatching { Id.valueOf(saved.selectedProvider) }.getOrDefault(Id.OPENAI)
        testResult.foreground = UIUtil.getContextHelpForeground()
        testResult.text = "Testing…"
        testResult.toolTipText = null
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { pendingKeySave?.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            val result = runCatching {
                AiOutputSanitizer.cleanOrThrow(CommitMessageGeneratorService.providerFor(id)
                    .generate("You are a test. Reply with exactly one word: OK", "Test prompt."))
            }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess {
                    testResult.foreground = UIUtil.getLabelSuccessForeground()
                    testResult.text = "Connected. Reply: " + AiOutputSanitizer.truncate(it, 60)
                }.onFailure {
                    val msg = it.message ?: it.toString()
                    testResult.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                    testResult.text = "Failed: " + AiOutputSanitizer.truncate(msg.lineSequence().first(), 120)
                    testResult.toolTipText = "<html><pre>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(
                        AiOutputSanitizer.truncate(msg, 2000)) + "</pre></html>"
                }
            }, ModalityState.any())
        }
    }

    /** Installed CLIs with path, version and sign-in state; "Browse…" picks an unlisted binary. */
    private class ChooseCliDialog(private val rows: List<AiCliDetector.DetectedCli>) : DialogWrapper(true) {
        private val table = JBTable(object : DefaultTableModel(
            rows.map { d ->
                arrayOf<Any>(
                    d.cli().displayName(),
                    if (d.found()) d.path() ?: "" else "not installed",
                    d.version(),
                    if (d.found()) d.auth() else d.cli().installHint())
            }.toTypedArray(),
            arrayOf("CLI", "Path", "Version", "Sign-in")) {
            override fun isCellEditable(row: Int, column: Int) = false
        })
        var browsedPath: String? = null
            private set

        init {
            title = "Choose CLI"
            setOKButtonText("Use Selected")
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            table.setShowGrid(false)
            rows.indexOfFirst { it.found() }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
            table.selectionModel.addListSelectionListener { isOKActionEnabled = selected()?.found() == true }
            isOKActionEnabled = selected()?.found() == true
            init()
        }

        fun selected(): AiCliDetector.DetectedCli? = table.selectedRow.takeIf { it >= 0 }?.let { rows[it] }

        override fun createCenterPanel(): JComponent = panel {
            row {
                cell(JBScrollPane(table).apply { preferredSize = JBUI.size(720, 240) })
                    .align(com.intellij.ui.dsl.builder.Align.FILL)
            }.resizableRow()
            row { comment("Not listed? Use Browse… to pick any CLI executable.") }
        }

        override fun createLeftSideActions(): Array<Action> = arrayOf(object : AbstractAction("Browse…") {
            override fun actionPerformed(e: ActionEvent?) {
                val file = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select CLI Executable"),
                    contentPanel, null, null) ?: return
                browsedPath = file.path
                close(OK_EXIT_CODE)
            }
        })
    }
}
