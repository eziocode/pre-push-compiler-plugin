package com.github.prepushchecker.commitgen

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.Path

/** Settings → Tools → Pre-Push Checker → Commit Message: how generated messages look. */
class CommitMessageConfigurable :
    BoundSearchableConfigurable("Commit Message", "prepushchecker.commitMessage", "prepushchecker.commitMessage") {

    private val state get() = CommitMessageSettings.getInstance().settings()
    private val rulesStatus = JBLabel()
    private var rulesField: JBTextField? = null

    override fun createPanel() = panel {
        group("Format") {
            row {
                checkBox("Conventional Commits (feat:, fix:, chore:, …)")
                    .bindSelected({ state.useConventionalCommits }, { state.useConventionalCommits = it })
            }
            row {
                checkBox("Derive the scope from changed file paths")
                    .bindSelected({ state.autoDetectScope }, { state.autoDetectScope = it })
            }
            row("Max subject length:") {
                spinner(0..500, 1).bindIntValue({ state.maxSubjectLength }, { state.maxSubjectLength = it })
                comment("characters, 0 = no limit")
            }
            row("Prefix template:") {
                textField().columns(COLUMNS_MEDIUM)
                    .bindText({ state.prefixTemplate.orEmpty() }, { state.prefixTemplate = it })
                    .applyToComponent { emptyText.text = "e.g. [JIRA-{branch}]" }
            }
            row("Language / tone:") {
                textField().columns(COLUMNS_SHORT)
                    .bindText({ state.language.orEmpty() }, { state.language = it })
            }
        }

        group("Team Rules") {
            row("Rules file:") {
                rulesField = textField().columns(COLUMNS_MEDIUM)
                    .bindText({ state.customRulesFilePath.orEmpty() }, { state.customRulesFilePath = it })
                    .applyToComponent {
                        emptyText.text = "auto: .github/commit-instructions.md, COMMIT_RULES.md"
                        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                            override fun textChanged(e: javax.swing.event.DocumentEvent) = updateRulesStatus()
                        })
                    }
                    .component
            }
            row { cell(rulesStatus) }
            row("Default branch:") {
                textField().columns(COLUMNS_SHORT)
                    .bindText({ state.defaultBranchName.orEmpty() }, { state.defaultBranchName = it })
                    .applyToComponent { emptyText.text = "auto (origin/HEAD)" }
                    .comment("Branch the rules file's branch gate treats as default.")
            }
            row {
                label("Extra instructions:")
            }
            row {
                textArea().rows(4).align(Align.FILL)
                    .bindText({ state.extraInstructions.orEmpty() }, { state.extraInstructions = it })
                    .applyToComponent { lineWrap = true; wrapStyleWord = true }
                    .comment("Appended to every commit message prompt.")
            }
        }
        updateRulesStatus()
    }

    private fun updateRulesStatus() {
        val project = firstProject()
        val path = rulesField?.text?.trim().orEmpty()
        rulesStatus.foreground = UIUtil.getContextHelpForeground()
        rulesStatus.text = when {
            project == null -> ""
            path.isEmpty() -> CommitMessagePromptBuilder.resolvedRuleFileName(project, "")
                ?.let { "Using $it" } ?: "No rules file found: only the settings above apply."
            else -> {
                val p = Path.of(path).let { if (it.isAbsolute) it else Path.of(project.basePath ?: "").resolve(it) }
                if (Files.isRegularFile(p)) "Found $p" else "Not found: $path"
            }
        }
    }

    private fun firstProject(): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDefault && it.basePath != null }
}
