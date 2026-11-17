package com.github.prepushchecker.inline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageProvider.Id;
import com.github.prepushchecker.inline.InlineSuggestSettings.Trigger;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class InlineSuggestSettingsTest {

    @Test public void defaults() {
        var s = new InlineSuggestSettings.State();
        assertFalse(s.enabled);
        assertEquals(CommitMessageProvider.Id.OLLAMA, InlineSuggestSettings.providerOf(s));
        assertEquals(Trigger.AUTO, InlineSuggestSettings.effectiveTrigger(s));
    }

    @Test public void providerHonorsSelectedTrigger() {
        var s = new InlineSuggestSettings.State();
        for (var id : CommitMessageProvider.Id.values()) {
            s.provider = id.name();
            boolean fast = EnumSet.of(Id.OPENAI, Id.ANTHROPIC, Id.GEMINI, Id.OLLAMA).contains(id);
            assertEquals(fast, InlineSuggestSettings.isFast(id));
            assertEquals(Trigger.AUTO, InlineSuggestSettings.effectiveTrigger(s));
            s.trigger = "MANUAL";
            assertEquals(Trigger.MANUAL, InlineSuggestSettings.effectiveTrigger(s));
            s.trigger = "AUTO";
        }
    }

    @Test public void clampFixesBadValues() {
        var s = new InlineSuggestSettings.State();
        s.debounceMs = 5; s.maxLines = 99; s.contextChars = -1; s.provider = "NOPE"; s.trigger = "x";
        InlineSuggestSettings.clamp(s);
        assertEquals(100, s.debounceMs); assertEquals(30, s.maxLines); assertEquals(0, s.contextChars);
        assertEquals("OLLAMA", s.provider); assertEquals("AUTO", s.trigger);
    }

    @Test public void disabledExtensions() {
        var s = new InlineSuggestSettings.State();
        s.disabledExtensions = " md, .TXT ,";
        assertTrue(InlineSuggestSettings.isExtensionDisabled(s, "MD"));
        assertTrue(InlineSuggestSettings.isExtensionDisabled(s, "txt"));
        assertFalse(InlineSuggestSettings.isExtensionDisabled(s, "java"));
        assertFalse(InlineSuggestSettings.isExtensionDisabled(s, null));
    }

    @Test public void legacyModelMigratesToStoredProvider() {
        var s = new InlineSuggestSettings.State();
        s.provider = "CLAUDE_CLI"; s.model = " opus ";
        InlineSuggestSettings.clamp(s);
        assertEquals("opus", InlineSuggestSettings.modelFor(s, Id.CLAUDE_CLI));
        assertEquals("", InlineSuggestSettings.modelFor(s, Id.CODEX_CLI));
        assertEquals("opus", s.model);
    }

    @Test public void modelMapWinsAndModelMirrorsSelectedProvider() {
        var s = new InlineSuggestSettings.State();
        s.provider = "CODEX_CLI"; s.model = "stale";
        s.models.put("CODEX_CLI", "gpt-5"); s.models.put("CLAUDE_CLI", "opus");
        s.models.put("BOGUS", "x"); s.models.put("OPENAI", "  ");
        InlineSuggestSettings.clamp(s);
        assertEquals("gpt-5", s.model);
        assertEquals(2, s.models.size());
    }

    @Test public void switchingProviderKeepsEachModel() {
        var s = new InlineSuggestSettings.State();
        Map<String, String> work = new HashMap<>();
        work.put("CLAUDE_CLI", "opus"); work.put("CODEX_CLI", "gpt-5");
        InlineSuggestSettings.select(s, Id.CLAUDE_CLI, work);
        assertEquals("opus", s.model);
        InlineSuggestSettings.select(s, Id.CODEX_CLI, s.models);
        assertEquals("gpt-5", s.model);
        assertEquals("opus", InlineSuggestSettings.modelFor(s, Id.CLAUDE_CLI));
        InlineSuggestSettings.select(s, Id.OLLAMA, s.models);
        assertEquals("", s.model);
        assertEquals("gpt-5", InlineSuggestSettings.modelFor(s, Id.CODEX_CLI));
    }

    @Test public void blankModelClearsOnlyThatProvider() {
        var s = new InlineSuggestSettings.State();
        Map<String, String> work = new HashMap<>();
        work.put("CLAUDE_CLI", "opus"); work.put("CODEX_CLI", " ");
        InlineSuggestSettings.select(s, Id.CODEX_CLI, work);
        assertEquals("", s.model);
        assertEquals("opus", InlineSuggestSettings.modelFor(s, Id.CLAUDE_CLI));
    }
}
