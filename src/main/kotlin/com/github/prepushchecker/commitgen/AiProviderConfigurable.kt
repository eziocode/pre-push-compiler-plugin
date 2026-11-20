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
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowsRange
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * Settings → Tools → Pre-Push Checker → AI Provider: which AI the commit message generator and
 * the code review use. Pick a source (API key, local CLI, JetBrains AI); for a CLI, pick it in
 * the CLI combo (installed CLIs are detected in the background), and the details sit under Advanced.
 *
 * No modal window is opened while choosing a CLI or refreshing models: since 2026.3 the Settings
 * dialog resets pages it saw as unmodified when it gets focus back, which used to throw the
 * freshly chosen CLI and model away ("resetUnmodifiedOnWindowFocus").
 */
class AiProviderConfigurable : SearchableConfigurable {

    companion object {
        /** Kept from the pre-2.10 page so existing links to it still open this page. */
        const val ID = "com.github.prepushchecker.commitgen.CommitMessageSettingsConfigurable"
        private const val MODAL_GUARD_MS = 3_000L
        private val API_IDS = listOf(Id.OPENAI, Id.ANTHROPIC, Id.GEMINI, Id.OLLAMA)
        private val CLI_IDS = setOf(Id.CODEX_CLI, Id.CLAUDE_CLI, Id.GH_COPILOT, Id.LLM_CLI, Id.CUSTOM_CLI)

        private fun apiName(id: Id) = when (id) {
            Id.OPENAI -> "OpenAI"
            Id.ANTHROPIC -> "Anthropic"
            Id.GEMINI -> "Google Gemini"
            Id.OLLAMA -> "Ollama (local)"
            else -> id.displayName()
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
    private val cliCombo = ComboBox<ProviderUi.CliChoice>()
    /** Last scan of installed CLIs (shared with the Inline Suggestions page); `null` until one ran. */
    @Volatile private var scan: List<AiCliDetector.DetectedCli>? = AiCliDetector.lastScan()
    private val detectResult = JBLabel()
    private val cliModelCombo = ComboBox<String>().apply { isEditable = true }
    private val cliPathField = TextFieldWithBrowseButton(JBTextField().apply { emptyText.text = "blank = find on PATH" }).apply {
        addBrowseFolderListener(TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select CLI Executable")))
    }
    private val effortCombo = ComboBox(DefaultComboBoxModel(ProviderUi.EFFORTS.toTypedArray())).apply {
        renderer = ProviderUi.effortRenderer()
    }
    private val speedHint = JBLabel()
    private val extraArgsField = JBTextField()
    private val templateField = JBTextField()
    private val listModelsArgsField = JBTextField()
    private val cliKeyField = JBPasswordField()
    private val codexStatus = JBLabel()

    // Add custom CLI (inline form, no dialog: see class doc)
    private val addNameField = JBTextField().apply { emptyText.text = "e.g. mycli" }
    private val addPathField = JBTextField().apply { emptyText.text = "blank = find the name on PATH" }
    private val addArgsField = JBTextField().apply { emptyText.text = "-p {prompt}" }
    private val addError = JBLabel()
    private val modelsStatus = JBLabel()

    // JetBrains AI
    private val ideStatus = JBLabel()

    private val testResult = JBLabel()

    private var apiRange: RowsRange? = null
    private var cliRange: RowsRange? = null
    private var ideRange: RowsRange? = null
    private var keyRow: Row? = null
    private var urlRow: Row? = null
    private var addRange: RowsRange? = null
    private var removeCustomRow: Row? = null
    private var extraArgsRow: Row? = null
    private var effortRow: Row? = null
    private var speedHintRow: Row? = null
    private var templateRow: Row? = null
    private var listArgsRow: Row? = null
    private var cliKeyRow: Row? = null
    private var codexRow: Row? = null
    private var component: JComponent? = null
    private var loading = false
    private var cliSummaryGeneration = 0L
    private var detectGeneration = 0L
    private var modelsGeneration = 0L
    private var listenersInstalled = false
    /** Until this time a reset() from the platform is ignored when the page has unsaved edits: see class doc. */
    private var ownModalUntil = 0L
    @Volatile private var pendingKeySave: java.util.concurrent.Future<*>? = null

    override fun getId() = ID

    override fun getDisplayName() = "AI Provider"

    override fun createComponent(): JComponent {
        // The platform may dispose and recreate the page: the widgets are fields, so listeners go on once.
        if (!listenersInstalled) {
            listenersInstalled = true
            installListeners()
        }
        val p = buildPanel()
        component = p
        reset()
        detectClis()
        return p
    }

    private fun installListeners() {
        listOf(apiRadio to Source.API, cliRadio to Source.CLI, ideRadio to Source.IDE).forEach { (radio, s) ->
            radio.addActionListener { if (radio.isSelected && !loading) { source = s; updateVisibility() } }
        }
        // Subclass instead of SimpleListCellRenderer.create(...): both create overloads are deprecated in 2026.3.
        apiCombo.renderer = object : SimpleListCellRenderer<Id>() {
            override fun customize(list: JList<out Id>, value: Id?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let { apiName(it) } ?: ""
            }
        }
        apiCombo.addActionListener {
            if (loading) return@addActionListener
            stashApi()
            apiId = apiCombo.selectedItem as Id
            loadApi()
            updateVisibility()
        }
        cliCombo.renderer = ProviderUi.cliChoiceRenderer { scan }
        cliModelCombo.addActionListener { updateSpeedHint() }
        (cliModelCombo.editor.editorComponent as? javax.swing.text.JTextComponent)?.document?.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateSpeedHint()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateSpeedHint()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateSpeedHint()
            })
        cliCombo.addActionListener {
            if (loading) return@addActionListener
            val choice = cliCombo.selectedItem as? ProviderUi.CliChoice ?: return@addActionListener
            chooseCli(choice)
        }
    }

    private fun buildPanel(): JComponent {
        return panel {
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
                    cell(cliCombo)
                    link("Detect Local CLIs") { detectClis(explicit = true) }
                    link("Browse…") { browseCli() }
                }
                row("") { cell(detectResult) }
                addRange = rowsRange {
                    row("Name:") {
                        cell(addNameField).columns(COLUMNS_MEDIUM)
                            .comment("Shown in the CLI list. Without a path it must be the command name on PATH.")
                    }
                    row("Path:") {
                        cell(addPathField).columns(COLUMNS_LARGE)
                        link("Browse…") { browseAddPath() }
                    }
                    row("Arguments:") {
                        cell(addArgsField).columns(COLUMNS_LARGE)
                            .comment("Placeholders: {prompt}, {model}, {model?…}. Without {prompt} the prompt goes on stdin.")
                    }
                    row("") {
                        button("Add") { addCustomCli() }
                        link("Cancel") { showAddForm(false) }
                        cell(addError)
                    }
                }
                row("") { cell(cliSummary) }
                removeCustomRow = row("") { link("Remove this custom CLI") { removeCustomCli() } }
                row("Model:") {
                    cell(cliModelCombo).columns(COLUMNS_MEDIUM)
                    link("Refresh models") { refreshModels() }
                    cell(modelsStatus)
                }
                speedHintRow = row("") { cell(speedHint) }
                effortRow = row("Reasoning effort:") {
                    cell(effortCombo)
                    comment("Commit messages only. Low is fastest; review and fix keep the CLI default.")
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
    }

    private fun advancedCliRows(panel: Panel) = with(panel) {
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
        if (component != null && System.currentTimeMillis() < ownModalUntil && isModified) return
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
        showAddForm(false)
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
        work = XmlSerializerUtil.createCopy(saved) // saved now owns the custom CLI list
        // Paths may have changed: the next AI call looks the CLI up again.
        CliPathResolver.clearResolveCache()
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
        cliSummaryGeneration++
        detectGeneration++
        modelsGeneration++
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
        s.claudeCliPath, s.claudeModel, s.claudeExtraArgs, s.claudeEffort, s.codexEffort, s.customCliBinary, s.customCliPath, s.customCliArgs,
        s.customCliModel, s.customCliModelsArgs, s.customClis.orEmpty().toList())

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
            Id.CODEX_CLI -> {
                s.codexCliPath = path; s.codexModel = model; s.codexExtraArgs = extraArgsField.text.trim()
                s.codexEffort = effortCombo.selectedItem as? String ?: ""
            }
            Id.CLAUDE_CLI -> {
                s.claudeCliPath = path; s.claudeModel = model; s.claudeExtraArgs = extraArgsField.text.trim()
                s.claudeEffort = effortCombo.selectedItem as? String ?: ""
            }
            Id.GH_COPILOT -> { s.ghCliPath = path; s.ghModel = model }
            Id.LLM_CLI -> { s.llmCliPath = path; s.llmModel = model }
            Id.CUSTOM_CLI -> {
                s.customCliPath = path
                s.customCliModel = model
                s.customCliArgs = templateField.text.trim()
                s.customCliModelsArgs = listModelsArgsField.text.trim()
                CommitMessageSettings.rememberActiveCustom(s)
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
            templateField.text = work.customCliArgs.orEmpty()
            templateField.emptyText.text = presetHint()
            listModelsArgsField.text = work.customCliModelsArgs.orEmpty()
            val cli = ProviderUi.cliOf(cliId, work.customCliBinary)
            rebuildCliCombo()
            cliModelCombo.model = DefaultComboBoxModel(AiCliModels.staticModels(cli).toTypedArray())
            cliModelCombo.editor.item = model.orEmpty()
            cliModelCombo.selectedItem = model.orEmpty()
            cliKeyField.text = typedCliKeys[cliId].orEmpty()
            effortCombo.selectedItem = when (cliId) {
                Id.CODEX_CLI -> work.codexEffort
                Id.CLAUDE_CLI -> work.claudeEffort
                else -> ""
            }.orEmpty()
            modelsStatus.text = ""
        } finally {
            loading = wasLoading
        }
        updateKeyHints()
        refreshCliSummary()
        if (cliId == Id.CODEX_CLI) refreshCodexStatus()
    }

    private fun updateSpeedHint() {
        val model = (cliModelCombo.editor.item as? String ?: cliModelCombo.selectedItem as? String ?: "").trim()
        val slow = ProviderUi.slowModelHint(cliId, model)
        speedHint.text = slow ?: ProviderUi.fastModelsHint(cliId).orEmpty()
        speedHint.foreground = if (slow != null) JBColor.namedColor("Label.errorForeground", JBColor.RED)
            else UIUtil.getContextHelpForeground()
    }

    private fun presetHint(): String {
        val preset = AiCli.byBinary(work.customCliBinary?.trim())?.takeIf { it.isCustom }?.defaultArgsTemplate()
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
            removeCustomRow?.visible(currentChoice() is ProviderUi.CliChoice.Custom)
            addRange?.visible(addFormShown)
            templateRow?.visible(custom)
            listArgsRow?.visible(custom)
            extraArgsRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            effortRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            speedHintRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            updateSpeedHint()
            cliKeyRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            codexRow?.visible(cliId == Id.CODEX_CLI)
        }
    }

    // ── CLI: choose, summary, models, sign-in ─────────────────────────────────

    /**
     * Background scan of every catalog CLI; the combo then lists the installed ones. [explicit]
     * ("Detect Local CLIs") also forgets cached paths and probes, and shows what was found.
     */
    private fun detectClis(explicit: Boolean = false) {
        val generation = ++detectGeneration
        stashCliInto(work)
        val snapshot = XmlSerializerUtil.createCopy(work)
        if (explicit) {
            detectResult.text = "Detecting…"
            detectResult.foreground = UIUtil.getContextHelpForeground()
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                if (explicit) AiCliDetector.rescan(snapshot) else AiCliDetector.scanAll(snapshot)
            }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (generation != detectGeneration) return@invokeLater
                scan = result
                rebuildCliCombo()
                if (explicit) {
                    detectResult.text = ProviderUi.detectSummary(result)
                    detectResult.foreground = UIUtil.getLabelForeground()
                }
                refreshCliSummary()
            }, ModalityState.any())
        }
    }

    private fun currentChoice(): ProviderUi.CliChoice? = ProviderUi.choiceOf(cliId, work.customCliBinary, work)

    /** Lists installed catalog CLIs, user-added CLIs and "Add custom CLI…"; keeps the current choice selected. */
    private fun rebuildCliCombo() {
        val current = currentChoice()
        val items = ProviderUi.cliChoices(work, scan, current, onlyActiveCustom = false, withAdd = true)
        val wasLoading = loading
        loading = true
        try {
            cliCombo.model = DefaultComboBoxModel(items.toTypedArray())
            cliCombo.selectedItem = current
        } finally {
            loading = wasLoading
        }
        removeCustomRow?.visible(source == Source.CLI && current is ProviderUi.CliChoice.Custom)
    }

    /** Switches to [choice] (also used by tests); "Add custom CLI…" opens the inline form instead. */
    fun chooseCli(choice: ProviderUi.CliChoice) {
        if (choice is ProviderUi.CliChoice.AddCustom) {
            showAddForm(true)
            rebuildCliCombo() // keep the real CLI selected
            return
        }
        stashCliInto(work)
        when (choice) {
            is ProviderUi.CliChoice.Catalog -> {
                if (choice.cli.isCustom) CommitMessageSettings.activateCustom(work, choice.cli.binaryName())
                cliId = choice.cli.providerId()
            }
            is ProviderUi.CliChoice.Custom -> {
                CommitMessageSettings.activateCustom(work, choice.name)
                cliId = Id.CUSTOM_CLI
            }
            else -> {}
        }
        loadCli()
        updateVisibility()
    }

    private var addFormShown = false

    private fun showAddForm(show: Boolean) {
        addFormShown = show
        if (show) {
            addNameField.text = ""
            addPathField.text = ""
            addArgsField.text = ""
            addError.text = ""
        }
        addRange?.visible(show && source == Source.CLI)
    }

    /** Adds (or updates) a user CLI from the inline form and selects it. */
    fun addCustomCli() {
        val name = addNameField.text.trim()
        val path = addPathField.text.trim()
        val args = addArgsField.text.trim()
        val error = customCliError(name, path)
        if (error != null) {
            addError.text = error
            addError.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
            return
        }
        stashCliInto(work)
        if (CommitMessageSettings.findCustom(work, name) == null) {
            work.customClis.add(CommitMessageSettings.CustomCliEntry(name, path, args, "", "", true))
        }
        CommitMessageSettings.activateCustom(work, name)
        CommitMessageSettings.findCustom(work, name)?.let {
            it.path = path
            it.argsTemplate = args
            it.userAdded = true
        }
        work.customCliPath = path
        work.customCliArgs = args
        cliId = Id.CUSTOM_CLI
        showAddForm(false)
        loadCli()
        updateVisibility()
    }

    private fun customCliError(name: String, path: String): String? {
        if (name.isEmpty()) return "Enter a name."
        val known = AiCli.byBinary(name)
        if (known != null && !known.isCustom) return "${known.displayName()} has its own entry: use Detect Local CLIs."
        if (path.isEmpty() && !name.matches(Regex("[A-Za-z0-9._+-]+"))) {
            return "Without a path the name must be a command name (letters, digits, . _ + -)."
        }
        if (path.isNotEmpty()) {
            val p = runCatching { java.nio.file.Path.of(path) }.getOrNull() ?: return "Not a valid path."
            if (p.isAbsolute && !java.nio.file.Files.isRegularFile(p)) return "No file at this path."
        }
        return null
    }

    private fun removeCustomCli() {
        val name = work.customCliBinary?.trim().orEmpty()
        if (name.isEmpty()) return
        work.customClis.removeIf { it.name.trim() == name }
        work.customCliBinary = ""
        work.customCliPath = ""
        work.customCliArgs = ""
        work.customCliModel = ""
        work.customCliModelsArgs = ""
        val next = scan.orEmpty().firstOrNull { it.found() && !it.cli().isCustom }?.cli()
        if (next != null) cliId = next.providerId()
        loadCli()
        updateVisibility()
    }

    /** Picks the executable for the Add custom CLI form. The file chooser is modal: see [ownModalUntil]. */
    private fun browseAddPath() {
        val file = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select CLI Executable"),
            component, null, null)
        ownModalUntil = System.currentTimeMillis() + MODAL_GUARD_MS
        if (file == null) return
        addPathField.text = file.path
        if (addNameField.text.isBlank()) addNameField.text = file.name
        ownModalUntil = System.currentTimeMillis() + MODAL_GUARD_MS
    }

    /** Picks an unlisted executable. The file chooser is modal: see [ownModalUntil]. */
    private fun browseCli() {
        stashCliInto(work)
        val file = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select CLI Executable"),
            component, null, null)
        ownModalUntil = System.currentTimeMillis() + MODAL_GUARD_MS
        if (file == null) return
        val chosenPath = file.path
        val name = java.nio.file.Path.of(chosenPath).fileName.toString()
        val known = AiCli.byBinary(name)
        if (known != null && !known.isCustom) {
            when (known) {
                AiCli.CODEX -> work.codexCliPath = chosenPath
                AiCli.CLAUDE -> work.claudeCliPath = chosenPath
                AiCli.GH -> work.ghCliPath = chosenPath
                AiCli.LLM -> work.llmCliPath = chosenPath
                else -> {}
            }
            cliId = known.providerId()
        } else {
            if (known == null && CommitMessageSettings.findCustom(work, name) == null) {
                work.customClis.add(CommitMessageSettings.CustomCliEntry(name, chosenPath, "", "", "", true))
            }
            CommitMessageSettings.activateCustom(work, name)
            work.customCliPath = chosenPath
            cliId = Id.CUSTOM_CLI
        }
        loadCli()
        updateVisibility()
        ownModalUntil = System.currentTimeMillis() + MODAL_GUARD_MS
    }

    private fun refreshCliSummary(): java.util.concurrent.Future<*> {
        val generation = ++cliSummaryGeneration
        val customName = work.customCliBinary?.trim().orEmpty()
        val cli = ProviderUi.cliOf(cliId, customName)
        val binary = if (cliId == Id.CUSTOM_CLI) customName else cli?.binaryName().orEmpty()
        val name = cli?.displayName() ?: binary
        val configured = cliPathField.text.trim()
        if (binary.isEmpty()) {
            cliSummary.text = ProviderUi.NO_CLI_TEXT
            cliSummary.foreground = UIUtil.getContextHelpForeground()
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        }
        cliSummary.text = "checking…"
        cliSummary.foreground = UIUtil.getLabelForeground()
        return ApplicationManager.getApplication().executeOnPooledThread {
            val path = CliPathResolver.resolveOrNull(configured, binary)
            val version = if (path == null) "" else AiCliDetector.version(path)
            ApplicationManager.getApplication().invokeLater({
                // A slower probe for the previous CLI must not replace the current selection.
                if (generation != cliSummaryGeneration) return@invokeLater
                if (path == null) {
                    cliSummary.text = "$name not found. Install it, use Browse…, or set the path under Advanced."
                    cliSummary.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                } else {
                    val auth = scan.orEmpty().firstOrNull { it.cli() == cli && it.found() }?.auth().orEmpty()
                    cliSummary.text = "$path" + (if (version.isBlank()) "" else "  ·  $version") +
                        (if (auth.isBlank()) "" else "  ·  $auth")
                    cliSummary.foreground = UIUtil.getLabelForeground()
                }
            }, ModalityState.any())
        }
    }

    /** Lists models off the UI thread (no modal progress: see class doc) and keeps the typed model. */
    private fun refreshModels() {
        val generation = ++modelsGeneration
        val forCli = cliId
        val customName = work.customCliBinary?.trim().orEmpty()
        val cli = ProviderUi.cliOf(cliId, customName)
        val binary = if (cliId == Id.CUSTOM_CLI) customName else cli?.binaryName().orEmpty()
        if (binary.isEmpty()) {
            modelsStatus.text = "Pick a CLI first."
            return
        }
        val configured = cliPathField.text.trim()
        val listArgs = if (cliId == Id.CUSTOM_CLI) listModelsArgsField.text.trim() else null
        modelsStatus.text = "loading…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val models = runCatching {
                AiCliModels.list(cli, CliPathResolver.resolveOrNull(configured, binary), listArgs)
            }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (generation != modelsGeneration || forCli != cliId) return@invokeLater
                if (models.isNullOrEmpty()) {
                    modelsStatus.text = "No models reported; type a name."
                    return@invokeLater
                }
                val current = (cliModelCombo.editor.item as? String).orEmpty()
                val wasLoading = loading
                loading = true
                try {
                    cliModelCombo.model = DefaultComboBoxModel(models.toTypedArray())
                    cliModelCombo.editor.item = current
                    cliModelCombo.selectedItem = current
                } finally {
                    loading = wasLoading
                }
                modelsStatus.text = "${models.size} models"
            }, ModalityState.any())
        }
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
        ownModalUntil = System.currentTimeMillis() + MODAL_GUARD_MS
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
            val started = System.currentTimeMillis()
            runCatching { pendingKeySave?.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            val result = runCatching {
                AiOutputSanitizer.cleanOrThrow(CommitMessageGeneratorService.providerFor(id)
                    .generate("You are a test. Reply with exactly one word: OK", "Test prompt."))
            }
            ApplicationManager.getApplication().invokeLater({
                ProviderUi.showTestResult(testResult, result, System.currentTimeMillis() - started)
            }, ModalityState.any())
        }
    }
}
