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
    private val cliCombo = ComboBox(DefaultComboBoxModel(AiCli.values()))
    @Volatile private var detected: Map<AiCli, AiCliDetector.DetectedCli> = emptyMap()
    private val cliModelCombo = ComboBox<String>().apply { isEditable = true }
    private val cliPathField = TextFieldWithBrowseButton(JBTextField().apply { emptyText.text = "blank = find on PATH" }).apply {
        addBrowseFolderListener(TextBrowseFolderListener(
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Select CLI Executable")))
    }
    private val effortCombo = ComboBox(DefaultComboBoxModel(ProviderUi.EFFORTS.toTypedArray())).apply {
        renderer = ProviderUi.effortRenderer()
    }
    private val speedHint = JBLabel()
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
        customBinaryField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                if (!loading) templateField.emptyText.text = presetHint()
            }
        })
        cliCombo.renderer = object : SimpleListCellRenderer<AiCli>() {
            override fun customize(list: JList<out AiCli>, value: AiCli?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) { text = ""; return }
                val d = detected[value]
                text = when {
                    d == null -> value.displayName()
                    d.found() -> value.displayName()
                    else -> value.displayName() + "  (not installed)"
                }
                if (d != null && !d.found() && !selected) foreground = UIUtil.getContextHelpForeground()
            }
        }
        cliModelCombo.addActionListener { updateSpeedHint() }
        (cliModelCombo.editor.editorComponent as? javax.swing.text.JTextComponent)?.document?.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateSpeedHint()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateSpeedHint()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateSpeedHint()
            })
        cliCombo.addActionListener {
            if (loading) return@addActionListener
            val cli = cliCombo.selectedItem as? AiCli ?: return@addActionListener
            stashCliInto(work)
            selectCli(cli)
            updateVisibility()
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
                    link("Browse…") { browseCli() }
                }
                row("") { cell(cliSummary) }
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
            cliCombo.selectedItem = cli
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
            effortRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            speedHintRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            updateSpeedHint()
            cliKeyRow?.visible(cliId == Id.CODEX_CLI || cliId == Id.CLAUDE_CLI)
            codexRow?.visible(cliId == Id.CODEX_CLI)
        }
    }

    // ── CLI: choose, summary, models, sign-in ─────────────────────────────────

    /** Background scan of every CLI; the combo shows which are installed and the summary their sign-in. */
    private fun detectClis() {
        val generation = ++detectGeneration
        val snapshot = XmlSerializerUtil.createCopy(work)
        ApplicationManager.getApplication().executeOnPooledThread {
            val scan = runCatching { AiCliDetector.scanAll(snapshot) }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (generation != detectGeneration) return@invokeLater
                detected = scan.associateBy { it.cli() }
                cliCombo.repaint()
            }, ModalityState.any())
        }
    }

    /** Switches to [cli], keeping each CLI's own path and model. */
    private fun selectCli(cli: AiCli) {
        if (cli.isCustom) {
            if (work.customCliBinary != cli.binaryName()) {
                // The "Other CLI" fields are shared by every custom binary: drop the previous binary's values.
                work.customCliArgs = ""
                work.customCliModel = ""
                work.customCliModelsArgs = ""
                work.customCliPath = detected[cli]?.path().orEmpty()
            }
            work.customCliBinary = cli.binaryName()
        }
        cliId = cli.providerId()
        loadCli()
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
            cliId = Id.CUSTOM_CLI
            if (work.customCliBinary != name) {
                work.customCliArgs = ""
                work.customCliModel = ""
                work.customCliModelsArgs = ""
            }
            work.customCliBinary = name
            work.customCliPath = chosenPath
        }
        loadCli()
        updateVisibility()
        ownModalUntil = System.currentTimeMillis() + MODAL_GUARD_MS
    }

    private fun refreshCliSummary(): java.util.concurrent.Future<*> {
        val generation = ++cliSummaryGeneration
        val cli = cliOf(cliId, customBinaryField.text.trim())
        val binary = if (cliId == Id.CUSTOM_CLI) customBinaryField.text.trim().ifEmpty { "sahaa" } else cli.binaryName()
        val name = if (cliId == Id.CUSTOM_CLI) AiCli.byBinary(binary)?.displayName() ?: binary else cli.displayName()
        val configured = cliPathField.text.trim()
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
                    val auth = detected[cli]?.takeIf { it.found() }?.auth().orEmpty()
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
        val cli = cliOf(cliId, customBinaryField.text.trim())
        val binary = if (cliId == Id.CUSTOM_CLI) customBinaryField.text.trim().ifEmpty { "sahaa" } else cli.binaryName()
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
