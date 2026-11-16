package com.github.prepushchecker.inline;

import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Application-level persistent settings for inline AI suggestions. */
@State(name = "PrePushInlineSuggestSettings", storages = @Storage("prePushInlineSuggest.xml"))
public final class InlineSuggestSettings
        implements PersistentStateComponent<InlineSuggestSettings.State> {

    public enum Trigger { AUTO, MANUAL }

    /** Providers that answer fast enough for automatic (as-you-type) suggestions. */
    private static final Set<CommitMessageProvider.Id> FAST = EnumSet.of(
        CommitMessageProvider.Id.OPENAI,
        CommitMessageProvider.Id.ANTHROPIC,
        CommitMessageProvider.Id.GEMINI,
        CommitMessageProvider.Id.OLLAMA);

    public static final class State {
        public boolean enabled = false;
        public String provider = CommitMessageProvider.Id.OLLAMA.name();
        public String model = "";
        public String trigger = Trigger.AUTO.name();
        public int debounceMs = 350;
        public boolean multiLine = true;
        public int maxLines = 8;
        public boolean useProjectContext = true;
        public int contextChars = 6000;
        public String disabledExtensions = "";
        public String extraInstructions = "";
    }

    private State state = new State();

    public static InlineSuggestSettings getInstance() {
        return ApplicationManager.getApplication().getService(InlineSuggestSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State s) {
        clamp(s);
        this.state = s;
    }

    public @NotNull State settings() {
        return state;
    }

    public static void clamp(@NotNull State s) {
        s.debounceMs = Math.max(100, Math.min(2000, s.debounceMs));
        s.maxLines = Math.max(1, Math.min(30, s.maxLines));
        s.contextChars = Math.max(0, Math.min(20000, s.contextChars));
        if (parseProvider(s.provider) == null) s.provider = CommitMessageProvider.Id.OLLAMA.name();
        if (parseTrigger(s.trigger) == null) s.trigger = Trigger.AUTO.name();
    }

    public static @NotNull CommitMessageProvider.Id providerOf(@NotNull State s) {
        CommitMessageProvider.Id id = parseProvider(s.provider);
        return id != null ? id : CommitMessageProvider.Id.OLLAMA;
    }

    public static boolean isFast(@NotNull CommitMessageProvider.Id id) {
        return FAST.contains(id);
    }

    public static @NotNull Trigger effectiveTrigger(@NotNull State s) {
        if (!isFast(providerOf(s))) return Trigger.MANUAL;
        Trigger t = parseTrigger(s.trigger);
        return t != null ? t : Trigger.AUTO;
    }

    public static boolean isExtensionDisabled(@NotNull State s, @Nullable String ext) {
        if (ext == null || s.disabledExtensions == null) return false;
        String want = normalizeExt(ext);
        if (want.isEmpty()) return false;
        for (String part : s.disabledExtensions.split(",")) {
            if (want.equals(normalizeExt(part))) return true;
        }
        return false;
    }

    private static String normalizeExt(String e) {
        String t = e.trim().toLowerCase(Locale.ROOT);
        return t.startsWith(".") ? t.substring(1) : t;
    }

    private static @Nullable CommitMessageProvider.Id parseProvider(@Nullable String v) {
        if (v == null) return null;
        try {
            return CommitMessageProvider.Id.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable Trigger parseTrigger(@Nullable String v) {
        if (v == null) return null;
        try {
            return Trigger.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
