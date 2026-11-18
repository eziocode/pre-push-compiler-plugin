package com.github.prepushchecker.commitgen

import com.github.prepushchecker.commitgen.CommitMessageProvider.Id
import com.github.prepushchecker.commitgen.cli.AiCli
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import javax.swing.JList

/** Small provider helpers shared by settings pages that mirror the AI Provider page. */
internal object ProviderUi {
    val API_IDS = listOf(Id.OPENAI, Id.ANTHROPIC, Id.GEMINI, Id.OLLAMA)
    val CLI_IDS = listOf(Id.CODEX_CLI, Id.CLAUDE_CLI, Id.GH_COPILOT, Id.LLM_CLI, Id.CUSTOM_CLI)

    fun apiName(id: Id) = when (id) {
        Id.OPENAI -> "OpenAI"
        Id.ANTHROPIC -> "Anthropic"
        Id.GEMINI -> "Google Gemini"
        Id.OLLAMA -> "Ollama (local)"
        else -> id.displayName()
    }

    fun cliOf(id: Id, binary: String?): AiCli = when (id) {
        Id.CODEX_CLI -> AiCli.CODEX
        Id.CLAUDE_CLI -> AiCli.CLAUDE
        Id.GH_COPILOT -> AiCli.GH
        Id.LLM_CLI -> AiCli.LLM
        else -> AiCli.byBinary(binary) ?: AiCli.SAHAA
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
