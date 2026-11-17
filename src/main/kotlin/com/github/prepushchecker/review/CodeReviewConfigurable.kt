package com.github.prepushchecker.review

import com.github.prepushchecker.PrePushCheckerSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.util.ui.UIUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Settings → Tools → Pre-Push Checker → Code Review. */
class CodeReviewConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Code Review", "prepushchecker.review", "prepushchecker.review") {

    private val status = JBLabel()
    private var pathField: JBTextField? = null

    override fun createPanel() = panel {
        lateinit var enabled: com.intellij.ui.dsl.builder.Cell<com.intellij.ui.components.JBCheckBox>
        row {
            enabled = checkBox("Review changes with AI against the repository's rule file")
                .bindSelected({ ReviewSettings.isEnabled(project) }, { ReviewSettings.setEnabled(project, it) })
                .comment("Uses the AI provider chosen on the AI Provider page. Only files the rule file covers are sent.")
        }

        group("Rule File") {
            row("Path:") {
                pathField = textField()
                    .columns(COLUMNS_MEDIUM)
                    .bindText({ ReviewSettings.ruleFilePath(project) }, { ReviewSettings.setRuleFilePath(project, it) })
                    .applyToComponent {
                        emptyText.text = ReviewRules.DEFAULT_FILE_NAME
                        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                            override fun textChanged(e: javax.swing.event.DocumentEvent) = updateStatus()
                        })
                    }
                    .comment("Relative to each repository root. Commit it so the whole team uses the same rules.")
                    .component
            }
            row {
                cell(status)
                link("Create rule file") { createRuleFile() }
                link("Open") { openRuleFile() }
            }
        }.enabledIf(enabled.selected)

        group("When to Review") {
            buttonsGroup {
                row {
                    radioButton("On demand", ReviewSettings.Mode.ON_DEMAND)
                        .comment("You run Review from the commit panel (next to the commit message button) or the Code Review tab. The push only checks that it was done.")
                }
                row {
                    radioButton("On push", ReviewSettings.Mode.ON_PUSH)
                        .comment("The push reviews any covered file that was not reviewed yet. Pushes wait for the AI.")
                }
            }.bind({ ReviewSettings.mode(project) }, { ReviewSettings.setMode(project, it) })
            row {
                checkBox("Ignore changes brought in by merges")
                    .bindSelected(
                        { ReviewSettings.isIgnoreMergeChanges(project) },
                        { ReviewSettings.setIgnoreMergeChanges(project, it) })
                    .comment("After 'git merge main' (or pulling a remote branch in), only your own commits and conflict resolutions are reviewed, not the merged-in code that is already on the remote.")
            }
            row {
                comment("Content that was reviewed and has not changed since is never reviewed again. Whitespace-only edits in code files are not sent to the AI.")
            }
        }.enabledIf(enabled.selected)

        group("Mandate") {
            row {
                checkBox("Block pushes until covered files are reviewed and every finding is resolved")
                    .bindSelected(
                        { ReviewSettings.isMandatoryFallback(project) },
                        { ReviewSettings.setMandatoryFallback(project, it) })
                    .comment("Applies to your pushes. 'mandatory: true' in the rule file turns it on for everyone, whatever this box says. Terminal pushes are blocked too when IntelliJ is closed.")
            }
        }.enabledIf(enabled.selected)

        updateStatus()
    }

    override fun apply() {
        super.apply()
        updateStatus()
        ApplicationManager.getApplication().executeOnPooledThread {
            PrePushCheckerSettings.syncSettingsFile(project)
        }
    }

    private fun currentPath(): String = pathField?.text?.trim().orEmpty()

    private fun baseRoot(): Path? = project.basePath?.let { Path.of(it) }

    private fun updateStatus() {
        val root = baseRoot()
        if (root == null) {
            status.text = ""
            return
        }
        val file = ReviewRules.ruleFile(root, currentPath())
        val rules = ReviewRules.load(root, currentPath())
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = when {
            !Files.isRegularFile(file) -> "No rule file yet: review is off for this repository."
            rules == null -> "Rule file is empty."
            else -> buildString {
                append("Found. ")
                append(if (rules.includeGlobs().isEmpty()) "Covers every file" else "Covers ${rules.includeGlobs().joinToString(", ")}")
                when (rules.mandatory()) {
                    true -> append(" · mandatory for everyone (set in the file)")
                    else -> {}
                }
            }
        }
    }

    private fun createRuleFile() {
        val root = baseRoot() ?: return
        val file = ReviewRules.ruleFile(root, currentPath())
        if (Files.exists(file)) {
            openRuleFile()
            return
        }
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, ReviewRules.template(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Create Rule File")
            return
        }
        updateStatus()
        openRuleFile()
    }

    private fun openRuleFile() {
        val root = baseRoot() ?: return
        val file = ReviewRules.ruleFile(root, currentPath())
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
        OpenFileDescriptor(project, vf).navigate(true)
    }
}
