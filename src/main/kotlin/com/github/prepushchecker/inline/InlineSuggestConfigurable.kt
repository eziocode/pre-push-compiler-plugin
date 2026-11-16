package com.github.prepushchecker.inline

import com.github.prepushchecker.commitgen.CommitMessageProvider.Id
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.*
import javax.swing.JList

/** Settings → Tools → Pre-Push Checker → Inline Suggestions. */
class InlineSuggestConfigurable : BoundSearchableConfigurable(
    "Inline Suggestions", "prepushchecker.inline", "prepushchecker.inline") {
    private val state get() = InlineSuggestSettings.getInstance().settings()

    override fun createPanel() = panel {
        lateinit var enabled: Cell<com.intellij.ui.components.JBCheckBox>
        row {
            enabled = checkBox("Suggest code inline while typing (ghost text, Tab to accept)")
                .bindSelected({ state.enabled }, { state.enabled = it })
                .comment("Sends code around the caret to the provider below. Off by default. If another inline completion plugin is active, both may show suggestions.")
        }
        group("Provider") {
            lateinit var provider: Cell<com.intellij.openapi.ui.ComboBox<Id>>
            row("Provider:") {
                provider = comboBox(Id.values().toList(), object : SimpleListCellRenderer<Id>() {
                    override fun customize(list: JList<out Id>, value: Id?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        text = value?.displayName().orEmpty()
                    }
                }).bindItem({ InlineSuggestSettings.providerOf(state) }, { state.provider = it?.name ?: "OLLAMA" })
            }
            row("Model:") {
                textField().bindText({ state.model }, { state.model = it })
                    .applyToComponent { emptyText.text = "Provider default" }
            }
            row {
                link("Configure provider...") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, "AI Provider")
                }
            }
            val slowNote = row { comment("This provider is too slow for automatic suggestions. Use the shortcut (Alt+\\).") }
            fun updateNote() { slowNote.visible(!InlineSuggestSettings.isFast(provider.component.item ?: Id.OLLAMA)) }
            provider.component.addActionListener { updateNote() }
            updateNote()
        }.enabledIf(enabled.selected)
        group("Trigger") {
            buttonsGroup {
                row {
                    radioButton("Automatically after a typing pause", InlineSuggestSettings.Trigger.AUTO)
                    intTextField(100..2000).bindIntText({ state.debounceMs }, { state.debounceMs = it })
                    label("ms")
                }
                row { radioButton("Only on shortcut (Alt+\\)", InlineSuggestSettings.Trigger.MANUAL) }
            }.bind({ InlineSuggestSettings.Trigger.valueOf(state.trigger) }, { state.trigger = it.name })
        }.enabledIf(enabled.selected)
        group("Suggestion") {
            lateinit var multi: Cell<com.intellij.ui.components.JBCheckBox>
            row { multi = checkBox("Multi-line").bindSelected({ state.multiLine }, { state.multiLine = it }) }
            row("Max lines:") {
                intTextField(1..30).bindIntText({ state.maxLines }, { state.maxLines = it }).enabledIf(multi.selected)
            }
        }.enabledIf(enabled.selected)
        group("Context") {
            row { checkBox("Include project classes and open files").bindSelected({ state.useProjectContext }, { state.useProjectContext = it }) }
            row("Budget:") {
                intTextField(0..20000).bindIntText({ state.contextChars }, { state.contextChars = it })
                label("characters")
            }
        }.enabledIf(enabled.selected)
        row("Disabled for file extensions:") {
            textField().bindText({ state.disabledExtensions }, { state.disabledExtensions = it })
                .comment("Comma-separated, e.g. md, txt")
        }.enabledIf(enabled.selected)
        row("Extra instructions:") {
            textArea().rows(3).bindText({ state.extraInstructions }, { state.extraInstructions = it })
        }.enabledIf(enabled.selected)
    }

    override fun apply() {
        super.apply()
        InlineSuggestSettings.clamp(state)
        InlineSuggestService.getInstance().settingsChanged()
    }
}
