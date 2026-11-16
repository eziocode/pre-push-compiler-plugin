package com.github.prepushchecker.inline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.prepushchecker.commitgen.CommitMessageProvider;
import com.github.prepushchecker.commitgen.CommitMessageProvider.Id;
import com.github.prepushchecker.inline.InlineSuggestSettings.Trigger;
import java.util.EnumSet;
import org.junit.Test;

public class InlineSuggestSettingsTest {

    @Test public void defaults() {
        var s = new InlineSuggestSettings.State();
        assertFalse(s.enabled);
        assertEquals(CommitMessageProvider.Id.OLLAMA, InlineSuggestSettings.providerOf(s));
        assertEquals(Trigger.AUTO, InlineSuggestSettings.effectiveTrigger(s));
    }

    @Test public void slowProviderForcesManual() {
        var s = new InlineSuggestSettings.State();
        for (var id : CommitMessageProvider.Id.values()) {
            s.provider = id.name();
            boolean fast = EnumSet.of(Id.OPENAI, Id.ANTHROPIC, Id.GEMINI, Id.OLLAMA).contains(id);
            assertEquals(fast, InlineSuggestSettings.isFast(id));
            assertEquals(fast ? Trigger.AUTO : Trigger.MANUAL, InlineSuggestSettings.effectiveTrigger(s));
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
}
