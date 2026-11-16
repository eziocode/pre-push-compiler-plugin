package com.github.prepushchecker

import com.github.prepushchecker.stats.SharedStats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

/**
 * Settings → Tools → Pre-Push Checker: what the push check does. The AI pages are children of
 * this one. Before 2.10.0 these controls lived inside the Compilation Checker tool window.
 */
class PrePushGeneralConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Pre-Push Checker", "prepushchecker.settings", ID) {

    companion object {
        const val ID = "prepushchecker.settings"
    }

    private var ignoredFiles: IgnoredFilesEditor? = null

    override fun createPanel() = panel {
        group("Push Check") {
            row {
                checkBox("Strict A/B dependency guard")
                    .bindSelected(
                        { PrePushCheckerSettings.isStrictSnapshotGuardEnabled(project) },
                        { PrePushCheckerSettings.setStrictSnapshotGuardEnabled(project, it) })
                    .comment("Compiles the committed HEAD in a clean worktree when local edits could hide a broken push.")
            }
            row {
                checkBox("Smart background build")
                    .bindSelected(
                        { PrePushCheckerSettings.isSmartBuildEnabled(project) },
                        { PrePushCheckerSettings.setSmartBuildEnabled(project, it) })
                    .comment("Compiles changes while the IDE is idle, so the push can reuse the result.")
            }
            row {
                checkBox("Skip the check when IntelliJ is closed")
                    .bindSelected(
                        { PrePushCheckerSettings.isBuildToolFallbackDisabled(project) },
                        { PrePushCheckerSettings.setBuildToolFallbackDisabled(project, it) })
                    .comment("Terminal pushes go through without a Maven/Gradle build. A mandatory AI review still blocks.")
            }
            row {
                checkBox("Cross-layer import check")
                    .bindSelected(
                        { PrePushCheckerSettings.isLayerCheckEnabled(project) },
                        { PrePushCheckerSettings.setLayerCheckEnabled(project, it) })
                    .comment("Uses ${LayerRules.FILE_NAME} at the repository root. Edit layers from the Layers menu in the Compilation Checker tool window.")
            }
        }

        group("Commit SHA") {
            lateinit var copy: com.intellij.ui.dsl.builder.Cell<com.intellij.ui.components.JBCheckBox>
            row {
                copy = checkBox("Copy the commit SHA to the clipboard")
                    .bindSelected(
                        { PrePushCheckerSettings.isCopyCommitShaEnabled(project) },
                        { PrePushCheckerSettings.setCopyCommitShaEnabled(project, it) })
            }
            indent {
                buttonsGroup {
                    row("Format:") {
                        radioButton("Full", PrePushCheckerSettings.ShaFormat.FULL)
                        radioButton("Short (7)", PrePushCheckerSettings.ShaFormat.SHORT)
                    }
                }.bind(
                    { PrePushCheckerSettings.getCopyCommitShaFormat(project) },
                    { PrePushCheckerSettings.setCopyCommitShaFormat(project, it) })
                buttonsGroup {
                    row("Copy after:") {
                        radioButton("Push", PrePushCheckerSettings.ShaTrigger.AFTER_PUSH)
                        radioButton("Commit", PrePushCheckerSettings.ShaTrigger.AFTER_COMMIT)
                    }
                }.bind(
                    { PrePushCheckerSettings.getCopyCommitShaTrigger(project) },
                    { PrePushCheckerSettings.setCopyCommitShaTrigger(project, it) })
            }.enabledIf(copy.selected)
        }

        group("Ignored Files") {
            row {
                comment("Checked files still compile, but their errors do not block pushes. Changes here are saved immediately.")
            }
            row {
                val editor = IgnoredFilesEditor(project)
                ignoredFiles = editor
                cell(editor.component).align(Align.FILL)
            }.resizableRow()
        }

        group("Usage Statistics") {
            row {
                checkBox("Share anonymous counts")
                    .bindSelected(
                        { SharedStats.getInstance().consent == SharedStats.Consent.YES },
                        { SharedStats.getInstance().setConsent(it) })
                    .comment("Adds this IDE's Push Stats counts to the public total: checks passed, pushes blocked, plugin and IDE version. No code, file names or paths.")
            }
        }
    }

    override fun reset() {
        super.reset()
        ignoredFiles?.refresh()
    }

    override fun apply() {
        super.apply()
        SmartBuildService.getInstance(project).settingChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            PrePushCheckerSettings.syncSettingsFile(project)
        }
    }

    override fun disposeUIResources() {
        ignoredFiles = null
        super.disposeUIResources()
    }
}
