package com.github.prepushchecker.inline

import com.github.prepushchecker.commitgen.AiOutputSanitizer
import com.github.prepushchecker.commitgen.AiProviderConfigurable
import com.github.prepushchecker.commitgen.AiRequest
import com.github.prepushchecker.commitgen.CommitMessageGeneratorService
import com.github.prepushchecker.commitgen.CliPathResolver
import com.github.prepushchecker.commitgen.CommitMessageProvider.Id
import com.github.prepushchecker.commitgen.CommitMessageSettings
import com.github.prepushchecker.commitgen.ProviderUi
import com.github.prepushchecker.commitgen.cli.AiCli
import com.github.prepushchecker.commitgen.cli.AiCliDetector
import com.github.prepushchecker.commitgen.cli.AiCliModels
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.UIUtil
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * Settings → Tools → Pre-Push Checker → Inline Suggestions. The provider block mirrors the
 * AI Provider page (source, provider, model combo for CLIs); keys and paths stay there.
 *
 * Each provider keeps its own model: the model widgets show the model of the provider currently
 * selected and [work] holds every provider's model until Apply.
 */
class InlineSuggestConfigurable : BoundSearchableConfigurable(
    "Inline Suggestions", "prepushchecker.inline", "prepushchecker.inline") {

    private enum class Source { API, CLI, IDE }

    private val state get() = InlineSuggestSettings.getInstance().settings()

    private val work = mutableMapOf<String, String>()
    private var source = Source.API
    private var apiId = Id.OPENAI
    private var cliId = Id.CODEX_CLI
    private var loading = false
    private var built = false
    private var summaryGeneration = 0L
    private var modelsGeneration = 0L

    private val apiRadio = JBRadioButton("API key")
    private val cliRadio = JBRadioButton("Local CLI")
    private val ideRadio = JBRadioButton("JetBrains AI")
    private val apiCombo = ComboBox(DefaultComboBoxModel(ProviderUi.API_IDS.toTypedArray()))
    private val cliCombo = ComboBox<ProviderUi.CliChoice>()
    private val apiModelField = JBTextField().apply { emptyText.text = PROVIDER_DEFAULT }
    private val cliModelCombo = ComboBox<String>().apply { isEditable = true }
    private val cliSummary = JBLabel()
    private val modelsStatus = JBLabel()
    private val testResult = JBLabel()

    private var apiRange: RowsRange? = null
    private var cliRange: RowsRange? = null
    private var ideRange: RowsRange? = null

    private companion object {
        const val PROVIDER_DEFAULT = "Provider default"
    }

    override fun createPanel(): DialogPanel {
        listOf(apiRadio to Source.API, cliRadio to Source.CLI, ideRadio to Source.IDE).forEach { (radio, s) ->
            radio.addActionListener { if (radio.isSelected && !loading) { source = s; updateVisibility() } }
        }
        apiCombo.renderer = ProviderUi.renderer { ProviderUi.apiName(it) }
        cliCombo.renderer = ProviderUi.cliChoiceRenderer { AiCliDetector.lastScan() }
        apiCombo.addActionListener {
            if (loading) return@addActionListener
            stashModels()
            apiId = apiCombo.selectedItem as Id
            loadApiModel()
        }
        cliCombo.addActionListener {
            if (loading) return@addActionListener
            val choice = cliCombo.selectedItem as? ProviderUi.CliChoice ?: return@addActionListener
            stashModels()
            cliId = choice.providerId
            loadCli()
        }
        (cliModelCombo.editor.editorComponent as? JBTextField)?.emptyText?.text = PROVIDER_DEFAULT

        val p = panel {
            lateinit var enabled: Cell<JBCheckBox>
            row {
                enabled = checkBox("Suggest code inline while typing (ghost text, ${InlineShortcuts.accept()} to accept)")
                    .bindSelected({ state.enabled }, { state.enabled = it })
                    .comment("Sends code around the caret to the provider below. Off by default. " +
                        "If another inline completion plugin is active, both may show suggestions.")
            }

            group("Provider") {
                buttonsGroup {
                    row("Source:") {
                        cell(apiRadio)
                        cell(cliRadio)
                        cell(ideRadio)
                    }.bottomGap(BottomGap.SMALL)
                }
                apiRange = rowsRange {
                    row("Provider:") { cell(apiCombo) }
                    row("Model:") {
                        cell(apiModelField).columns(COLUMNS_MEDIUM)
                            .comment("Blank uses the provider default.")
                    }
                }
                cliRange = rowsRange {
                    row("CLI:") { cell(cliCombo) }
                    row("") { cell(cliSummary) }
                    row("Model:") {
                        cell(cliModelCombo).columns(COLUMNS_MEDIUM)
                        link("Refresh models") { refreshModels() }
                        cell(modelsStatus)
                    }
                    row("") { comment("Blank uses the model set for this CLI on the AI Provider page.") }
                }
                ideRange = rowsRange {
                    row { comment("Uses the JetBrains AI Assistant plugin and your signed-in JetBrains account.") }
                }
                row {
                    link("Configure provider…") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(null, AiProviderConfigurable::class.java)
                    }.comment("API keys, CLI paths and sign-in are set on the AI Provider page.")
                }
                row {
                    link("Test") { testProvider() }
                    cell(testResult)
                }
            }.enabledIf(enabled.selected)

            group("Trigger") {
                buttonsGroup {
                    row {
                        radioButton("Automatically after a typing pause", InlineSuggestSettings.Trigger.AUTO)
                        intTextField(100..2000).columns(5).bindIntText({ state.debounceMs }, { state.debounceMs = it })
                        label("ms")
                    }
                    row { radioButton("Only on shortcut (${InlineShortcuts.suggest()})", InlineSuggestSettings.Trigger.MANUAL) }
                }.bind({ InlineSuggestSettings.Trigger.valueOf(state.trigger) }, { state.trigger = it.name })
            }.enabledIf(enabled.selected)

            group("Suggestion") {
                lateinit var multi: Cell<JBCheckBox>
                row { multi = checkBox("Multi-line").bindSelected({ state.multiLine }, { state.multiLine = it }) }
                row("Max lines:") {
                    intTextField(1..30).columns(5).bindIntText({ state.maxLines }, { state.maxLines = it })
                        .enabledIf(multi.selected)
                }
            }.enabledIf(enabled.selected)

            group("Context") {
                row { checkBox("Include project classes and open files").bindSelected({ state.useProjectContext }, { state.useProjectContext = it }) }
                row("Budget:") {
                    intTextField(0..20000).columns(7).bindIntText({ state.contextChars }, { state.contextChars = it })
                    label("characters")
                }
            }.enabledIf(enabled.selected)

            group("Filters") {
                row("Disabled extensions:") {
                    textField().columns(COLUMNS_MEDIUM).bindText({ state.disabledExtensions }, { state.disabledExtensions = it })
                        .comment("Comma-separated, e.g. md, txt")
                }
                row("Extra instructions:") {
                    textArea().rows(3).columns(COLUMNS_LARGE).bindText({ state.extraInstructions }, { state.extraInstructions = it })
                        .comment("Added to every inline suggestion request.")
                }
            }.enabledIf(enabled.selected)
        }
        built = true
        loadFromState()
        scanIfNeeded()
        return p
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    /** Sends a tiny completion request with the provider and model chosen here (unsaved edits are applied first). */
    private fun testProvider() {
        if (isModified) apply()
        val s = state
        val id = InlineSuggestSettings.providerOf(s)
        val model = s.model?.trim()?.takeIf { it.isNotEmpty() }
        val timeout = if (InlineSuggestSettings.isFast(id)) AiRequest.COMPLETION_FAST_TIMEOUT
            else AiRequest.COMPLETION_SLOW_TIMEOUT
        testResult.foreground = UIUtil.getContextHelpForeground()
        testResult.text = "Testing…"
        testResult.toolTipText = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val started = System.currentTimeMillis()
            val result = runCatching {
                val raw = CommitMessageGeneratorService.providerFor(id).generate(
                    AiRequest.completion("You are a test. Reply with exactly one word: OK", "Test prompt.", model, timeout))
                AiOutputSanitizer.cleanCompletion(raw).ifBlank { throw IllegalStateException("The provider returned an empty reply.") }
            }
            ApplicationManager.getApplication().invokeLater({
                ProviderUi.showTestResult(testResult, result, System.currentTimeMillis() - started)
            }, ModalityState.any())
        }
    }

    // ── Load / store ──────────────────────────────────────────────────────────

    override fun reset() {
        super.reset()
        if (built) loadFromState()
    }

    override fun isModified(): Boolean {
        if (super.isModified()) return true
        if (!built) return false
        stashModels()
        val s = state
        return currentId().name != s.provider || cleaned(work) != savedModels()
    }

    override fun apply() {
        super.apply()
        if (built) {
            stashModels()
            InlineSuggestSettings.select(state, currentId(), work)
        }
        InlineSuggestSettings.clamp(state)
        InlineSuggestService.getInstance().settingsChanged()
    }

    override fun disposeUIResources() {
        summaryGeneration++
        built = false
        super.disposeUIResources()
    }

    private fun currentId(): Id = when (source) {
        Source.API -> apiId
        Source.CLI -> cliId
        Source.IDE -> Id.INTELLIJ_AI
    }

    private fun cleaned(m: Map<String, String>): Map<String, String> =
        m.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    /** Saved models with the legacy single `model` migrated to the stored provider. */
    private fun savedModels(): Map<String, String> {
        val tmp = InlineSuggestSettings.State()
        tmp.provider = state.provider
        tmp.model = state.model
        tmp.models = HashMap(state.models ?: emptyMap())
        InlineSuggestSettings.normalizeModels(tmp)
        return tmp.models
    }

    private fun loadFromState() {
        work.clear()
        work.putAll(savedModels())
        val selected = InlineSuggestSettings.providerOf(state)
        source = when (selected) {
            Id.INTELLIJ_AI -> Source.IDE
            in ProviderUi.CLI_IDS -> Source.CLI
            else -> Source.API
        }
        apiId = if (selected in ProviderUi.API_IDS) selected else Id.OLLAMA
        cliId = if (selected in ProviderUi.CLI_IDS) selected else Id.CODEX_CLI
        loading = true
        try {
            apiRadio.isSelected = source == Source.API
            cliRadio.isSelected = source == Source.CLI
            ideRadio.isSelected = source == Source.IDE
            apiCombo.selectedItem = apiId
            rebuildCliCombo()
            loadApiModel()
            loadCli()
        } finally {
            loading = false
        }
        updateVisibility()
    }

    /** Both model widgets always hold their own provider's text, so stash both. */
    private fun stashModels() {
        work[apiId.name] = apiModelField.text.trim()
        work[cliId.name] = cliModelText()
        work.entries.removeIf { it.value.isBlank() }
    }

    private fun cliModelText(): String =
        ((cliModelCombo.editor.item as? String) ?: (cliModelCombo.selectedItem as? String) ?: "").trim()

    private fun loadApiModel() {
        apiModelField.text = work[apiId.name].orEmpty()
    }

    private fun cliModelsOf(cli: AiCli?): List<String> = AiCliModels.staticModels(cli)

    /** Same rows as the AI Provider page (installed CLIs, its active custom CLI), without "Add custom CLI…". */
    private fun rebuildCliCombo() {
        val s = commitState() ?: CommitMessageSettings.State()
        val current = ProviderUi.choiceOf(cliId, s.customCliBinary, s)
        val items = ProviderUi.cliChoices(s, AiCliDetector.lastScan(), current, onlyActiveCustom = true, withAdd = false)
        val wasLoading = loading
        loading = true
        try {
            cliCombo.model = DefaultComboBoxModel(items.toTypedArray())
            cliCombo.selectedItem = current
        } finally {
            loading = wasLoading
        }
    }

    /** First visit before the AI Provider page ran a scan: scan in the background, then list what was found. */
    private fun scanIfNeeded() {
        if (AiCliDetector.lastScan() != null) return
        val s = commitState()?.let { com.intellij.util.xmlb.XmlSerializerUtil.createCopy(it) } ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { AiCliDetector.scanAll(s) }
            ApplicationManager.getApplication().invokeLater({ if (built) rebuildCliCombo() }, ModalityState.any())
        }
    }

    private fun loadCli() {
        val wasLoading = loading
        loading = true
        try {
            val model = work[cliId.name].orEmpty()
            val cli = ProviderUi.cliOf(cliId, commitState()?.customCliBinary)
            cliModelCombo.model = DefaultComboBoxModel(cliModelsOf(cli).toTypedArray())
            cliModelCombo.editor.item = model
            cliModelCombo.selectedItem = model
            modelsStatus.text = ""
        } finally {
            loading = wasLoading
        }
        refreshCliSummary()
    }

    private fun updateVisibility() {
        apiRange?.visible(source == Source.API)
        cliRange?.visible(source == Source.CLI)
        ideRange?.visible(source == Source.IDE)
    }

    // ── CLI summary and models (same behaviour as the AI Provider page) ───────

    private fun commitState(): CommitMessageSettings.State? =
        runCatching { CommitMessageSettings.getInstance()?.settings() }.getOrNull()

    private fun cliBinary(cli: AiCli?): String =
        if (cliId == Id.CUSTOM_CLI) commitState()?.customCliBinary?.trim().orEmpty() else cli?.binaryName().orEmpty()

    private fun configuredPath(cli: AiCli?): String =
        if (cliId == Id.CUSTOM_CLI) commitState()?.customCliPath.orEmpty()
        else commitState()?.let { cli?.configuredPath(it) }.orEmpty()

    private fun refreshCliSummary() {
        val generation = ++summaryGeneration
        val cli = ProviderUi.cliOf(cliId, commitState()?.customCliBinary)
        val binary = cliBinary(cli)
        val name = cli?.displayName() ?: binary
        val configured = configuredPath(cli)
        if (binary.isEmpty()) {
            cliSummary.text = ProviderUi.NO_CLI_TEXT
            cliSummary.foreground = UIUtil.getContextHelpForeground()
            return
        }
        cliSummary.text = "$name  ·  checking…"
        cliSummary.foreground = UIUtil.getLabelForeground()
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = CliPathResolver.resolveOrNull(configured, binary)
            val version = if (path == null) "" else AiCliDetector.version(path)
            ApplicationManager.getApplication().invokeLater({
                // A slower probe for the previous CLI must not replace the current selection.
                if (generation != summaryGeneration) return@invokeLater
                if (path == null) {
                    cliSummary.text = "$name  ·  not found. Set its path on the AI Provider page."
                    cliSummary.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                } else {
                    cliSummary.text = "$name  ·  $path" + if (version.isBlank()) "" else "  ·  $version"
                    cliSummary.foreground = UIUtil.getLabelForeground()
                }
            }, ModalityState.any())
        }
    }

    /** Lists models off the UI thread: a modal progress makes 2026.3 reset this page on focus return. */
    private fun refreshModels() {
        val generation = ++modelsGeneration
        val forCli = cliId
        val cli = ProviderUi.cliOf(cliId, commitState()?.customCliBinary)
        val binary = cliBinary(cli)
        val configured = configuredPath(cli)
        val listArgs = if (cliId == Id.CUSTOM_CLI) commitState()?.customCliModelsArgs?.trim() else null
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
                val current = cliModelText()
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
}
