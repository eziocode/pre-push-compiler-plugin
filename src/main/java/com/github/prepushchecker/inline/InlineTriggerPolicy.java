package com.github.prepushchecker.inline;

import org.jetbrains.annotations.NotNull;

/** Pure decision table: whether an editor event may request, reuse a cached, or skip a suggestion. */
public final class InlineTriggerPolicy {

    public enum Event { TYPED, ENTER, BACKSPACE, OTHER }

    public enum Decision { REQUEST, CACHE_ONLY, NONE }

    /** Editor state gathered on the EDT at the moment of the event. */
    public record EditorFacts(boolean focused, int caretCount, boolean hasSelection, boolean writable,
                              boolean lookupActive, boolean templateActive, boolean inProject,
                              boolean extensionDisabled, boolean sensitive) {
    }

    private InlineTriggerPolicy() {
    }

    public static boolean eligible(@NotNull EditorFacts f) {
        return f.focused() && f.caretCount() == 1 && !f.hasSelection() && f.writable()
            && !f.lookupActive() && !f.templateActive() && f.inProject()
            && !f.extensionDisabled() && !f.sensitive();
    }

    public static @NotNull Decision decide(@NotNull Event e, @NotNull EditorFacts f, boolean enabled,
                                           @NotNull InlineSuggestSettings.Trigger effective, boolean paused) {
        if (!enabled || !eligible(f)) {
            return Decision.NONE;
        }
        return switch (e) {
            case BACKSPACE -> Decision.CACHE_ONLY;
            case TYPED, ENTER -> effective == InlineSuggestSettings.Trigger.AUTO && !paused
                ? Decision.REQUEST : Decision.CACHE_ONLY;
            case OTHER -> Decision.NONE;
        };
    }
}
