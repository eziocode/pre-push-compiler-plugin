package com.github.prepushchecker.commitgen

import com.github.prepushchecker.commitgen.CommitMessageProvider.Id
import com.github.prepushchecker.commitgen.cli.AiCli
import com.intellij.ui.SimpleListCellRenderer
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
}
