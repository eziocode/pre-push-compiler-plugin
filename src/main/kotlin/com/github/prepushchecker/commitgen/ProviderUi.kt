package com.github.prepushchecker.commitgen

import com.github.prepushchecker.commitgen.CommitMessageProvider.Id
import com.github.prepushchecker.commitgen.cli.AiCli
import com.github.prepushchecker.commitgen.cli.AiCliDetector
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import javax.swing.JList

/** Small provider helpers shared by settings pages that mirror the AI Provider page. */
object ProviderUi {
    val API_IDS = listOf(Id.OPENAI, Id.ANTHROPIC, Id.GEMINI, Id.OLLAMA)
    val CLI_IDS = listOf(Id.CODEX_CLI, Id.CLAUDE_CLI, Id.GH_COPILOT, Id.LLM_CLI, Id.CUSTOM_CLI)

    fun apiName(id: Id) = when (id) {
        Id.OPENAI -> "OpenAI"
        Id.ANTHROPIC -> "Anthropic"
        Id.GEMINI -> "Google Gemini"
        Id.OLLAMA -> "Ollama (local)"
        else -> id.displayName()
    }

    /** The catalog entry for [id]; for the "Other CLI" provider the one named [binary], or `null` (user-added). */
    fun cliOf(id: Id, binary: String?): AiCli? = when (id) {
        Id.CODEX_CLI -> AiCli.CODEX
        Id.CLAUDE_CLI -> AiCli.CLAUDE
        Id.GH_COPILOT -> AiCli.GH
        Id.LLM_CLI -> AiCli.LLM
        else -> AiCli.byBinary(binary)
    }

    /** One row of the CLI combo: a detected catalog CLI, a user-added CLI, or the "Add custom CLI…" action. */
    sealed class CliChoice {
        abstract val label: String
        abstract val providerId: Id

        data class Catalog(val cli: AiCli) : CliChoice() {
            override val label get() = cli.displayName()
            override val providerId get() = cli.providerId()
        }

        data class Custom(val name: String) : CliChoice() {
            override val label get() = "$name  (custom)"
            override val providerId get() = Id.CUSTOM_CLI
        }

        object AddCustom : CliChoice() {
            override val label get() = "Add custom CLI…"
            override val providerId get() = Id.CUSTOM_CLI
        }

        override fun toString() = label
    }

    const val NO_CLI_TEXT = "No local AI CLI found \u2014 click Detect or Add custom CLI"

    /** The choice that stands for provider [id] with the active custom CLI [customBinary]. */
    fun choiceOf(id: Id, customBinary: String?, s: CommitMessageSettings.State): CliChoice? {
        if (id != Id.CUSTOM_CLI) return cliOf(id, null)?.let { CliChoice.Catalog(it) }
        val name = customBinary?.trim().orEmpty()
        if (name.isEmpty()) return null
        val cli = AiCli.byBinary(name)
        val entry = CommitMessageSettings.findCustom(s, name)
        return if (cli != null && (entry == null || !entry.userAdded)) CliChoice.Catalog(cli) else CliChoice.Custom(name)
    }

    /**
     * Combo rows shared by the AI Provider and Inline Suggestions pages: catalog CLIs found on this
     * machine ([detected]; before the first scan nothing is known), the [current] choice even when it
     * was not found, user-added CLIs ([onlyActiveCustom]: just the active one, since Inline uses the
     * AI Provider page's custom CLI), then "Add custom CLI…" when [withAdd].
     */
    fun cliChoices(s: CommitMessageSettings.State, detected: List<AiCliDetector.DetectedCli>?, current: CliChoice?,
                   onlyActiveCustom: Boolean, withAdd: Boolean): List<CliChoice> {
        val out = LinkedHashSet<CliChoice>()
        val found = detected.orEmpty().filter { it.found() }.map { it.cli() }.toSet()
        val active = s.customCliBinary?.trim().orEmpty()
        for (cli in AiCli.values()) {
            if (cli !in found) continue
            // Inline only offers the AI Provider page's active custom CLI.
            if (onlyActiveCustom && cli.isCustom && cli.binaryName() != active) continue
            out += CliChoice.Catalog(cli)
        }
        for (e in CommitMessageSettings.userCustomClis(s)) {
            if (!onlyActiveCustom || e.name.trim() == active) out += CliChoice.Custom(e.name.trim())
        }
        if (current != null && current !is CliChoice.AddCustom && current !in out) {
            if (current is CliChoice.Catalog && current.cli.isCustom && onlyActiveCustom && current.cli.binaryName() != active) {
                // Inline only offers the AI Provider page's active custom CLI.
            } else {
                out += current
            }
        }
        val list = out.sortedBy { if (it is CliChoice.Custom) 1 else 0 }.toMutableList()
        if (withAdd) list += CliChoice.AddCustom
        return list
    }

    /** Renderer: "(not found)" for a saved choice that the last scan did not find. */
    fun cliChoiceRenderer(detected: () -> List<AiCliDetector.DetectedCli>?): SimpleListCellRenderer<CliChoice> =
        object : SimpleListCellRenderer<CliChoice>() {
            override fun customize(list: JList<out CliChoice>, value: CliChoice?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) { text = ""; return }
                val scan = detected()
                val missing = value is CliChoice.Catalog && scan != null && scan.none { it.cli() == value.cli && it.found() }
                text = value.label + if (missing) "  (not found)" else ""
                if ((missing || value is CliChoice.AddCustom) && !selected) foreground = UIUtil.getContextHelpForeground()
            }
        }

    /** "Found: claude 2.1.3, codex 0.170.0" for the Detect result label. */
    fun detectSummary(scan: List<AiCliDetector.DetectedCli>): String {
        val found = scan.filter { it.found() }
        if (found.isEmpty()) return "No local AI CLI found."
        return "Found: " + found.joinToString(", ") { d ->
            val v = Regex("\\d+(\\.\\d+)+").find(d.version())?.value
            d.cli().binaryName() + (if (v != null) " $v" else "")
        }
    }

    /** Subclass instead of SimpleListCellRenderer.create(...): both overloads are deprecated. */
    fun renderer(name: (Id) -> String): SimpleListCellRenderer<Id> = object : SimpleListCellRenderer<Id>() {
        override fun customize(list: JList<out Id>, value: Id?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = value?.let(name) ?: ""
        }
    }

    /** Reasoning-effort values both Claude and Codex CLIs accept; blank = the CLI's own default. */
    val EFFORTS = listOf("", "low", "medium", "high", "xhigh", "max")

    fun effortLabel(value: String?): String = when (value.orEmpty()) {
        "" -> "CLI default"
        "low" -> "Low (fastest, recommended)"
        "medium" -> "Medium"
        "high" -> "High"
        "xhigh" -> "Extra high"
        "max" -> "Max (slowest)"
        else -> value.orEmpty()
    }

    /** Subclass instead of SimpleListCellRenderer.create(...): both overloads are deprecated. */
    fun effortRenderer(): SimpleListCellRenderer<String> = object : SimpleListCellRenderer<String>() {
        override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = effortLabel(value)
        }
    }

    /** Warning for models that take long to write a commit message; {@code null} when none applies. */
    fun slowModelHint(id: Id, model: String?): String? {
        val m = model.orEmpty().trim().lowercase()
        return when {
            id == Id.CLAUDE_CLI && (m.contains("opus") || m.contains("fable")) ->
                "Slow for commit messages. Try haiku or sonnet, or set the effort to Low."
            id == Id.CODEX_CLI && m.contains("astra") ->
                "Slow for commit messages. Try gpt-6.2-sol or gpt-6-luna, or set the effort to Low."
            else -> null
        }
    }

    fun fastModelsHint(id: Id): String? = when (id) {
        Id.CLAUDE_CLI -> "Faster for commit messages: haiku, sonnet."
        Id.CODEX_CLI -> "Faster for commit messages: gpt-6.2-sol, gpt-6-luna."
        else -> null
    }

    /** Shows a test outcome in [label]; call on the EDT. */
    fun showTestResult(label: JBLabel, result: Result<String>, elapsedMs: Long) {
        result.onSuccess {
            label.foreground = UIUtil.getLabelSuccessForeground()
            val secs = String.format("%.1f", elapsedMs / 1000.0)
            label.text = "Connected in ${secs}s. Reply: " + AiOutputSanitizer.truncate(it, 60)
            label.toolTipText = null
        }.onFailure {
            val msg = it.message ?: it.toString()
            label.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
            label.text = "Failed: " + AiOutputSanitizer.truncate(msg.lineSequence().first(), 120)
            label.toolTipText = "<html><pre>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(
                AiOutputSanitizer.truncate(msg, 2000)) + "</pre></html>"
        }
    }
}
